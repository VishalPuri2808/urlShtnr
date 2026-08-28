package com.urlshortener.cache;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.exception.UrlNotActiveException;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the cache-invalidation contract stated in the spec:
 *   "A deactivated link must return 410 even if it was cached moments before."
 *
 * Uses real Postgres (Testcontainers), real Redis (Testcontainers), and an
 * embedded Kafka broker (to satisfy KafkaAutoConfiguration without a real cluster).
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"url.click.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers(disabledWithoutDocker = true)
class CacheInvalidationTest {

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

    @Autowired private UrlService      urlService;
    @Autowired private UrlCacheService urlCacheService;

    @Test
    void deactivatedUrl_returns410_evenIfCachedMomentsBefore() {
        // 1. Create a short URL
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null, null), null);
        String shortCode = created.shortCode();

        // 2. First resolve primes the Redis cache
        assertThat(urlService.resolveUrl(shortCode, null)).isEqualTo("https://example.com");
        assertThat(urlCacheService.get(shortCode))
                .as("entry must be in Redis after first resolve")
                .isPresent();

        // 3. Deactivation must synchronously remove the cache entry
        urlService.deactivateUrl(shortCode);
        assertThat(urlCacheService.get(shortCode))
                .as("cache must be empty immediately after deactivation")
                .isEmpty();

        // 4. The very next redirect must return 410 — never the stale cached 302
        assertThatThrownBy(() -> urlService.resolveUrl(shortCode, null))
                .as("deactivated URL must throw 410 on the next request")
                .isInstanceOf(UrlNotActiveException.class);
    }
}
