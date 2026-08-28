package com.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.cache.UrlCacheService;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.repository.UrlClickRepository;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full integration test suite — exercises every quality-gate scenario end-to-end.
 * Skipped automatically when Docker is unavailable (Docker Desktop not running).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = {"url.click.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers(disabledWithoutDocker = true)
class UrlShortenerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host",     redis::getHost);
        r.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
    }

    @Autowired private MockMvc                      mockMvc;
    @Autowired private ObjectMapper                 objectMapper;
    @Autowired private UrlService                   urlService;
    @Autowired private UrlCacheService              urlCacheService;
    @Autowired private UrlRepository                urlRepository;
    @Autowired private UrlClickRepository           urlClickRepository;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void cleanUp() {
        // Delete in FK order; late-arriving Kafka messages for deleted urls are handled
        // gracefully by the consumer's ifPresentOrElse guard (logs warning, no exception).
        urlClickRepository.deleteAllInBatch();
        urlRepository.deleteAllInBatch();
        Set<String> cacheKeys = redisTemplate.keys("url:*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redisTemplate.delete(cacheKeys);
        }
    }

    // ── Quality gate: create → redirect happy path ──────────────────────────

    @Test
    void createAndRedirect_happyPath_returns201Then302() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateUrlRequest("https://example.com", null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.shortUrl").isNotEmpty())
                .andExpect(jsonPath("$.longUrl").value("https://example.com"))
                .andReturn();

        String shortCode = shortCode(result);

        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.com"));
    }

    // ── Quality gate: cache invalidation on deactivate ───────────────────────

    @Test
    void cacheInvalidation_deactivatedUrl_returns410EvenIfCachedMomentsBefore() throws Exception {
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null, null), null);
        String code = created.shortCode();

        // Warm the cache
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        assertThat(urlCacheService.get(code)).as("must be in Redis after first redirect").isPresent();

        // Deactivate — cache must be synchronously evicted before this call returns
        mockMvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isNoContent());
        assertThat(urlCacheService.get(code)).as("cache must be empty after deactivation").isEmpty();

        // The very next redirect must return 410, never the stale cached 302
        mockMvc.perform(get("/" + code)).andExpect(status().isGone());
    }

    // ── Quality gate: duplicate idempotency key ──────────────────────────────

    @Test
    void createUrl_duplicateIdempotencyKey_returnsOriginalResultWithoutCreatingDuplicate()
            throws Exception {
        String key = "idem-" + System.nanoTime();

        MvcResult first = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(json(new CreateUrlRequest("https://original.com", null, null))))
                .andExpect(status().isCreated())
                .andReturn();

        // Different body, same key — must return the original record unchanged
        MvcResult second = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(json(new CreateUrlRequest("https://different.com", null, null))))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(shortCode(first))
                .as("idempotent repeat must return the original short code")
                .isEqualTo(shortCode(second));
        assertThat(urlRepository.count())
                .as("only one URL row must exist for one idempotency key")
                .isEqualTo(1);
    }

    // ── Quality gate: expired link returns 410 ───────────────────────────────

    @Test
    void redirect_expiredUrl_returns410() throws Exception {
        // expiresAt in the past — creation succeeds, redirect must return 410
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null,
                        OffsetDateTime.now().minusSeconds(10)), null);

        mockMvc.perform(get("/" + created.shortCode())).andExpect(status().isGone());
    }

    // ── Additional edge cases ─────────────────────────────────────────────────

    @Test
    void redirect_unknownShortCode_returns404() throws Exception {
        mockMvc.perform(get("/definitelynotexists99")).andExpect(status().isNotFound());
    }

    @Test
    void createUrl_ssrfPrivateIp_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateUrlRequest("http://192.168.1.1/internal", null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUrl_customAliasAlreadyTaken_returns409() throws Exception {
        String alias = "alias-" + System.nanoTime();
        String body  = json(new CreateUrlRequest("https://example.com", alias, null));

        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void deactivateUrl_returns204_thenRedirectReturns410() throws Exception {
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null, null), null);

        mockMvc.perform(delete("/api/v1/urls/" + created.shortCode()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/" + created.shortCode())).andExpect(status().isGone());
    }

    @Test
    void stats_afterRedirect_reflectsKafkaAggregatedClickCount() throws Exception {
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null, null), null);
        String code = created.shortCode();

        mockMvc.perform(get("/" + code)).andExpect(status().isFound());

        // Wait for the Kafka consumer to persist the UrlClick row asynchronously
        await().atMost(10, SECONDS)
               .pollInterval(Duration.ofMillis(200))
               .until(() -> urlService.getStats(code).totalClicks() >= 1);

        mockMvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(code))
                .andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private String shortCode(MvcResult result) throws Exception {
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), CreateUrlResponse.class)
                .shortCode();
    }
}
