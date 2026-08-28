package com.urlshortener.service;

import com.urlshortener.cache.UrlCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QrCodeServiceTest {

    @Mock private UrlCacheService urlCacheService;

    private QrCodeService qrCodeService;

    @BeforeEach
    void setUp() {
        qrCodeService = new QrCodeService(urlCacheService);
    }

    // ── size clamping ────────────────────────────────────────────────────────

    @Test
    void clamp_belowMin_returnedAsMin() {
        assertThat(QrCodeService.clamp(50)).isEqualTo(100);
        assertThat(QrCodeService.clamp(1)).isEqualTo(100);
        assertThat(QrCodeService.clamp(-999)).isEqualTo(100);
    }

    @Test
    void clamp_aboveMax_returnedAsMax() {
        assertThat(QrCodeService.clamp(5000)).isEqualTo(1000);
        assertThat(QrCodeService.clamp(Integer.MAX_VALUE)).isEqualTo(1000);
    }

    @Test
    void clamp_inRange_unchanged() {
        assertThat(QrCodeService.clamp(100)).isEqualTo(100);
        assertThat(QrCodeService.clamp(300)).isEqualTo(300);
        assertThat(QrCodeService.clamp(1000)).isEqualTo(1000);
    }

    // ── generation + caching ─────────────────────────────────────────────────

    @Test
    void getOrGenerate_defaultSize_returnsPngAndCachesResult() {
        when(urlCacheService.getQrCode("abc", 300)).thenReturn(Optional.empty());

        byte[] png = qrCodeService.getOrGenerate("http://localhost:8080/abc", "abc", 300);

        assertThat(png).isNotEmpty();
        assertThat(isPng(png)).as("bytes must start with the PNG magic number").isTrue();
        verify(urlCacheService).putQrCode("abc", 300, png);
    }

    @Test
    void getOrGenerate_customSize_usesClampedSizeForCacheKey() {
        when(urlCacheService.getQrCode("abc", 500)).thenReturn(Optional.empty());

        byte[] png = qrCodeService.getOrGenerate("http://localhost:8080/abc", "abc", 500);

        assertThat(isPng(png)).isTrue();
        verify(urlCacheService).putQrCode(eq("abc"), eq(500), any());
    }

    @Test
    void getOrGenerate_oversizeRequest_clampedTo1000() {
        when(urlCacheService.getQrCode("abc", 1000)).thenReturn(Optional.empty());

        qrCodeService.getOrGenerate("http://localhost:8080/abc", "abc", 9999);

        // Clamped size must be used for both generation and cache key
        verify(urlCacheService).putQrCode(eq("abc"), eq(1000), any());
    }

    @Test
    void getOrGenerate_undersizeRequest_clampedTo100() {
        when(urlCacheService.getQrCode("abc", 100)).thenReturn(Optional.empty());

        qrCodeService.getOrGenerate("http://localhost:8080/abc", "abc", 10);

        verify(urlCacheService).putQrCode(eq("abc"), eq(100), any());
    }

    @Test
    void getOrGenerate_cacheHit_returnsCachedBytesWithoutGenerating() {
        byte[] cached = buildFakePng();
        when(urlCacheService.getQrCode("abc", 300)).thenReturn(Optional.of(cached));

        byte[] result = qrCodeService.getOrGenerate("http://localhost:8080/abc", "abc", 300);

        assertThat(result).isSameAs(cached);
        // Must not write back to cache when already cached
        verify(urlCacheService, never()).putQrCode(any(), anyInt(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50  // \x89 P
                && bytes[2] == 0x4E         && bytes[3] == 0x47  // N G
                && bytes[4] == 0x0D         && bytes[5] == 0x0A  // \r \n
                && bytes[6] == 0x1A         && bytes[7] == 0x0A; // \x1a \n
    }

    private byte[] buildFakePng() {
        return new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }
}
