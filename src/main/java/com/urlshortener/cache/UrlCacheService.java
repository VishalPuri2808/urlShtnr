package com.urlshortener.cache;

import com.urlshortener.config.AppProperties;
import com.urlshortener.dto.CachedUrl;
import com.urlshortener.model.Url;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around RedisTemplate that owns key-prefix conventions and TTL logic.
 * Keeping cache operations here lets UrlServiceImpl stay focused on business rules
 * and makes the cache layer independently testable.
 */
@Component
public class UrlCacheService {

    static final String KEY_PREFIX    = "url:";
    static final String QR_KEY_PREFIX  = "qrcode:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final AppProperties                 appProperties;

    public UrlCacheService(RedisTemplate<String, Object> redisTemplate,
                           AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.appProperties = appProperties;
    }

    public Optional<CachedUrl> get(String shortCode) {
        Object value = redisTemplate.opsForValue().get(KEY_PREFIX + shortCode);
        // GenericJackson2JsonRedisSerializer uses @class metadata to reconstruct the type
        if (value instanceof CachedUrl cached) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    public void put(Url url) {
        CachedUrl cached = new CachedUrl(
                url.getId(),
                url.getShortCode(),
                url.getLongUrl(),
                url.getCreatedAt(),
                url.getExpiresAt(),
                url.isActive()
        );
        redisTemplate.opsForValue().set(
                KEY_PREFIX + url.getShortCode(),
                cached,
                computeTtlSeconds(url),
                TimeUnit.SECONDS
        );
    }

    public Optional<byte[]> getQrCode(String shortCode, int size) {
        Object value = redisTemplate.opsForValue().get(QR_KEY_PREFIX + shortCode + ":" + size);
        if (value instanceof byte[] bytes) return Optional.of(bytes);
        return Optional.empty();
    }

    public void putQrCode(String shortCode, int size, byte[] png) {
        redisTemplate.opsForValue().set(
                QR_KEY_PREFIX + shortCode + ":" + size,
                png,
                appProperties.getCache().getTtlSeconds(),
                TimeUnit.SECONDS
        );
    }

    /** Removes the entry; called synchronously on deactivate/delete. */
    public void evict(String shortCode) {
        redisTemplate.delete(KEY_PREFIX + shortCode);
        // Invalidate all cached QR sizes for this code via the same path — no second invalidation path.
        // KEYS is O(N); acceptable for dev. Use SCAN-based delete in high-traffic production.
        Set<String> qrKeys = redisTemplate.keys(QR_KEY_PREFIX + shortCode + ":*");
        if (qrKeys != null && !qrKeys.isEmpty()) {
            redisTemplate.delete(qrKeys);
        }
    }

    /** Caps the TTL at time-to-expiry so Redis self-evicts when the link expires. */
    private long computeTtlSeconds(Url url) {
        long configured = appProperties.getCache().getTtlSeconds();
        if (url.getExpiresAt() == null) return configured;
        long untilExpiry = ChronoUnit.SECONDS.between(OffsetDateTime.now(), url.getExpiresAt());
        return Math.min(configured, Math.max(1L, untilExpiry));
    }
}
