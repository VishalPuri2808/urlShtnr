package com.urlshortener.service;

import com.urlshortener.cache.UrlCacheService;
import com.urlshortener.config.AppProperties;
import com.urlshortener.dto.CachedUrl;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.event.UrlClickedEvent;
import com.urlshortener.exception.CustomAliasConflictException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.exception.UrlNotActiveException;
import com.urlshortener.kafka.UrlClickEventProducer;
import com.urlshortener.model.Url;
import com.urlshortener.repository.UrlClickRepository;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.util.Base62Encoder;
import com.urlshortener.util.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceImplTest {

    @Mock private UrlRepository        urlRepository;
    @Mock private UrlClickRepository   urlClickRepository;
    @Mock private Base62Encoder        base62Encoder;
    @Mock private UrlValidator         urlValidator;
    @Mock private AppProperties        appProperties;
    @Mock private UrlCacheService      urlCacheService;
    @Mock private UrlClickEventProducer clickEventProducer;

    private UrlServiceImpl urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlServiceImpl(
                urlRepository, urlClickRepository, base62Encoder, urlValidator,
                appProperties, urlCacheService, clickEventProducer);
        // lenient: some tests don't reach toResponse(); we don't want spurious failures
        lenient().when(appProperties.getBaseUrl()).thenReturn("http://localhost:8080");
        // Default: cache miss so resolveUrl tests fall through to the DB mock
        lenient().when(urlCacheService.get(anyString())).thenReturn(Optional.empty());
    }

    // ── createUrl — auto-generated short code ───────────────────────────────

    @Test
    void createUrl_noKey_insertsAndUpdatesShortCode() {
        // Simulate IDENTITY generation: first save sets id, second save finalises shortCode
        when(urlRepository.save(any(Url.class))).thenAnswer(inv -> {
            Url u = inv.getArgument(0);
            if (u.getId() == null) u.setId(1L);
            return u;
        });
        when(base62Encoder.encode(1L)).thenReturn("1");

        CreateUrlRequest req = request("https://example.com", null);
        CreateUrlResponse resp = urlService.createUrl(req, null);

        assertThat(resp.shortCode()).isEqualTo("1");
        assertThat(resp.shortUrl()).isEqualTo("http://localhost:8080/1");
        // INSERT (temp code) + UPDATE (real base62 code)
        verify(urlRepository, times(2)).save(any(Url.class));
    }

    // ── createUrl — idempotency ──────────────────────────────────────────────

    @Test
    void createUrl_duplicateKey_returnsExistingWithoutInsert() {
        Url existing = activeUrl(42L, "abc", "https://example.com");
        when(urlRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        // Even if the caller sends a different URL, the original result is returned
        CreateUrlResponse resp = urlService.createUrl(request("https://other.com", null), "key-1");

        assertThat(resp.shortCode()).isEqualTo("abc");
    }

    @Test
    void createUrl_newKey_createsRecord() {
        when(urlRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
        when(urlRepository.save(any(Url.class))).thenAnswer(inv -> {
            Url u = inv.getArgument(0);
            if (u.getId() == null) u.setId(2L);
            return u;
        });
        when(base62Encoder.encode(2L)).thenReturn("2");

        CreateUrlResponse resp = urlService.createUrl(request("https://example.com", null), "key-2");

        assertThat(resp.shortCode()).isEqualTo("2");
        verify(urlRepository, times(2)).save(any(Url.class));
    }

    // ── createUrl — custom alias ─────────────────────────────────────────────

    @Test
    void createUrl_customAlias_noConflict_usesAlias() {
        when(urlRepository.findByShortCode("myalias")).thenReturn(Optional.empty());
        when(urlRepository.save(any(Url.class))).thenAnswer(inv -> {
            Url u = inv.getArgument(0);
            if (u.getId() == null) u.setId(5L);
            return u;
        });

        CreateUrlResponse resp = urlService.createUrl(request("https://example.com", "myalias"), null);

        assertThat(resp.shortCode()).isEqualTo("myalias");
        // Custom alias: only one save (no second UPDATE needed)
        verify(urlRepository, times(1)).save(any(Url.class));
    }

    @Test
    void createUrl_customAlias_conflict_throws() {
        when(urlRepository.findByShortCode("taken"))
                .thenReturn(Optional.of(activeUrl(1L, "taken", "https://other.com")));

        assertThatThrownBy(() -> urlService.createUrl(request("https://example.com", "taken"), null))
                .isInstanceOf(CustomAliasConflictException.class);
        verify(urlRepository, never()).save(any(Url.class));
    }

    // ── resolveUrl — cache behaviour ───────────────────────────────────────

    @Test
    void resolveUrl_cacheHit_skipsDb() {
        CachedUrl hit = new CachedUrl(1L, "abc", "https://example.com",
                OffsetDateTime.now(), null, true);
        when(urlCacheService.get("abc")).thenReturn(Optional.of(hit));

        assertThat(urlService.resolveUrl("abc", null)).isEqualTo("https://example.com");
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void resolveUrl_cacheMiss_populatesCache() {
        Url url = activeUrl(1L, "abc", "https://example.com");
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        urlService.resolveUrl("abc", null);

        verify(urlCacheService).put(url);
    }

    @Test
    void resolveUrl_cachedButInactive_throws410WithoutDbQuery() {
        // Defensive: should never happen in practice due to synchronous eviction,
        // but we check the flag anyway in case Redis has a stale entry.
        CachedUrl inactive = new CachedUrl(1L, "abc", "https://example.com",
                OffsetDateTime.now(), null, false);
        when(urlCacheService.get("abc")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> urlService.resolveUrl("abc", null))
                .isInstanceOf(UrlNotActiveException.class);
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    // ── resolveUrl — DB paths (cache miss) ─────────────────────────────

    // (setUp configures urlCacheService.get() to return empty by default)

    @Test
    void resolveUrl_activeNonExpired_returnsLongUrl() {
        when(urlRepository.findByShortCode("abc"))
                .thenReturn(Optional.of(activeUrl(1L, "abc", "https://example.com")));

        assertThat(urlService.resolveUrl("abc", null)).isEqualTo("https://example.com");
    }

    @Test
    void resolveUrl_notFound_throws404() {
        when(urlRepository.findByShortCode("xyz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveUrl("xyz", null))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolveUrl_deactivated_throws410() {
        Url url = activeUrl(1L, "abc", "https://example.com");
        url.setActive(false);
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> urlService.resolveUrl("abc", null))
                .isInstanceOf(UrlNotActiveException.class);
    }

    @Test
    void resolveUrl_expired_throws410() {
        Url url = activeUrl(1L, "abc", "https://example.com");
        url.setExpiresAt(OffsetDateTime.now().minusDays(1)); // expired yesterday
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> urlService.resolveUrl("abc", null))
                .isInstanceOf(UrlNotActiveException.class);
    }

    @Test
    void resolveUrl_futureExpiry_passes() {
        Url url = activeUrl(1L, "abc", "https://example.com");
        url.setExpiresAt(OffsetDateTime.now().plusDays(1));
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        assertThatCode(() -> urlService.resolveUrl("abc", null)).doesNotThrowAnyException();
    }
    // ── resolveUrl — click event publishing ───────────────────────────────

    @Test
    void resolveUrl_activeUrl_publishesClickEvent() {
        Url url = activeUrl(1L, "abc", "https://example.com");
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        urlService.resolveUrl("abc", "https://referrer.com");

        verify(clickEventProducer).publish(any(UrlClickedEvent.class));
    }

    @Test
    void resolveUrl_notFound_doesNotPublish() {
        when(urlRepository.findByShortCode("xyz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveUrl("xyz", null))
                .isInstanceOf(UrlNotFoundException.class);
        verify(clickEventProducer, never()).publish(any());
    }

    // ── getStats ───────────────────────────────────────────────────────

    @Test
    void getStats_returnsAggregatedData() {
        Url url = activeUrl(1L, "abc", "https://example.com");
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));
        when(urlClickRepository.countByUrl_Id(1L)).thenReturn(42L);
        when(urlClickRepository.findLastClickedAtByUrlId(1L))
                .thenReturn(Optional.of(OffsetDateTime.now()));

        UrlStatsResponse stats = urlService.getStats("abc");

        assertThat(stats.shortCode()).isEqualTo("abc");
        assertThat(stats.longUrl()).isEqualTo("https://example.com");
        assertThat(stats.totalClicks()).isEqualTo(42L);
        assertThat(stats.lastClickedAt()).isNotNull();
    }

    @Test
    void getStats_urlNotFound_throws() {
        when(urlRepository.findByShortCode("xyz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.getStats("xyz"))
                .isInstanceOf(UrlNotFoundException.class);
    }
    // ── deactivateUrl ────────────────────────────────────────────────────────

    @Test
    void deactivateUrl_found_setsActiveFalseAndEvictsCache() {
        Url url = activeUrl(1L, "abc", "https://example.com");
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        urlService.deactivateUrl("abc");

        assertThat(url.isActive()).isFalse();
        verify(urlRepository).save(url);
        verify(urlCacheService).evict("abc");
    }

    @Test
    void deactivateUrl_notFound_throws() {
        when(urlRepository.findByShortCode("xyz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.deactivateUrl("xyz"))
                .isInstanceOf(UrlNotFoundException.class);
        verify(urlRepository, never()).save(any(Url.class));
    }

    // ── resolveShortUrl ──────────────────────────────────────────────────────

    @Test
    void resolveShortUrl_activeUrl_returnsShortUrl() {
        when(urlRepository.findByShortCode("abc"))
                .thenReturn(Optional.of(activeUrl(1L, "abc", "https://example.com")));

        assertThat(urlService.resolveShortUrl("abc")).isEqualTo("http://localhost:8080/abc");
        verify(clickEventProducer, never()).publish(any());
    }

    @Test
    void resolveShortUrl_notFound_throws404() {
        when(urlRepository.findByShortCode("xyz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveShortUrl("xyz"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolveShortUrl_deactivated_throws410() {
        Url url = activeUrl(1L, "abc", "https://example.com");
        url.setActive(false);
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> urlService.resolveShortUrl("abc"))
                .isInstanceOf(UrlNotActiveException.class);
        verify(clickEventProducer, never()).publish(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CreateUrlRequest request(String longUrl, String alias) {
        return new CreateUrlRequest(longUrl, alias, null);
    }

    private Url activeUrl(long id, String shortCode, String longUrl) {
        return Url.builder()
                .id(id)
                .shortCode(shortCode)
                .longUrl(longUrl)
                .createdAt(OffsetDateTime.now())
                .active(true)
                .build();
    }
}
