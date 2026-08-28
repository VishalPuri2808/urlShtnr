package com.urlshortener.integration;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
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
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end QR code tests: verifies PNG validity, ZXing decodability, 404/410 rules,
 * and cache invalidation on deactivation (shares the existing evict() path).
 * Skipped when Docker is unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = {"url.click.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers(disabledWithoutDocker = true)
class QrCodeIntegrationTest {

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

    @Autowired private MockMvc                       mockMvc;
    @Autowired private UrlService                    urlService;
    @Autowired private UrlRepository                 urlRepository;
    @Autowired private UrlClickRepository            urlClickRepository;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void cleanUp() {
        urlClickRepository.deleteAllInBatch();
        urlRepository.deleteAllInBatch();
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    // ── Quality gate: valid PNG that ZXing can decode ───────────────────────

    @Test
    void qrCode_validShortCode_returnsPngDecodableToShortUrl() throws Exception {
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null, null), null);
        String shortCode = created.shortCode();

        byte[] png = mockMvc.perform(get("/api/v1/urls/" + shortCode + "/qrcode"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        // Validate PNG magic bytes
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 0x50); // P
        assertThat(png[2]).isEqualTo((byte) 0x4E); // N
        assertThat(png[3]).isEqualTo((byte) 0x47); // G

        // Decode the QR with ZXing and verify it contains the short URL
        BufferedImage image  = ImageIO.read(new ByteArrayInputStream(png));
        BinaryBitmap bitmap  = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        Result decoded = new QRCodeReader().decode(bitmap);

        assertThat(decoded.getText())
                .as("QR content must encode the short URL")
                .endsWith("/" + shortCode);
    }

    @Test
    void qrCode_customSize_returnsPng() throws Exception {
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null, null), null);

        byte[] png = mockMvc.perform(
                        get("/api/v1/urls/" + created.shortCode() + "/qrcode?size=400"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(png[0]).isEqualTo((byte) 0x89); // PNG header
    }

    // ── Quality gate: 404 / 410 behaviour ───────────────────────────────────

    @Test
    void qrCode_unknownShortCode_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/urls/doesnotexist/qrcode"))
                .andExpect(status().isNotFound());
    }

    @Test
    void qrCode_deactivatedUrl_returns410() throws Exception {
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null, null), null);
        urlService.deactivateUrl(created.shortCode());

        mockMvc.perform(get("/api/v1/urls/" + created.shortCode() + "/qrcode"))
                .andExpect(status().isGone());
    }

    // ── Quality gate: QR cache invalidated when URL is deactivated ──────────

    @Test
    void qrCode_cachedEntry_evictedOnDeactivation() throws Exception {
        CreateUrlResponse created = urlService.createUrl(
                new CreateUrlRequest("https://example.com", null, null), null);
        String code = created.shortCode();

        // Prime the QR cache
        mockMvc.perform(get("/api/v1/urls/" + code + "/qrcode"))
                .andExpect(status().isOk());
        assertThat(redisTemplate.keys("qrcode:" + code + ":*"))
                .as("QR cache must be populated after first request")
                .isNotEmpty();

        // Deactivate — must evict both redirect and QR cache entries
        mockMvc.perform(delete("/api/v1/urls/" + code))
                .andExpect(status().isNoContent());
        assertThat(redisTemplate.keys("qrcode:" + code + ":*"))
                .as("QR cache must be empty after deactivation")
                .isEmpty();

        // Subsequent QR request must return 410
        mockMvc.perform(get("/api/v1/urls/" + code + "/qrcode"))
                .andExpect(status().isGone());
    }
}
