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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * TASK 2: createUrl, resolveUrl, deactivateUrl
 * TASK 3: Redis cache layered onto resolveUrl + synchronous invalidation in deactivateUrl
 * TASK 4: Kafka event published in resolveUrl; getStats implemented
 */
@Service
public class UrlServiceImpl implements UrlService {

    private final UrlRepository        urlRepository;
    private final UrlClickRepository   urlClickRepository;
    private final Base62Encoder        base62Encoder;
    private final UrlValidator         urlValidator;
    private final AppProperties        appProperties;
    private final UrlCacheService      urlCacheService;
    private final UrlClickEventProducer clickEventProducer;

    public UrlServiceImpl(
            UrlRepository urlRepository,
            UrlClickRepository urlClickRepository,
            Base62Encoder base62Encoder,
            UrlValidator urlValidator,
            AppProperties appProperties,
            UrlCacheService urlCacheService,
            UrlClickEventProducer clickEventProducer) {
        this.urlRepository       = urlRepository;
        this.urlClickRepository  = urlClickRepository;
        this.base62Encoder       = base62Encoder;
        this.urlValidator        = urlValidator;
        this.appProperties       = appProperties;
        this.urlCacheService     = urlCacheService;
        this.clickEventProducer  = clickEventProducer;
    }

    @Override
    @Transactional
    public CreateUrlResponse createUrl(CreateUrlRequest request, String idempotencyKey) {
        // Return the original result for duplicate keys without touching the DB again
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return urlRepository.findByIdempotencyKey(idempotencyKey)
                    .map(this::toResponse)
                    .orElseGet(() -> doCreateUrl(request, idempotencyKey));
        }
        return doCreateUrl(request, null);
    }

    private CreateUrlResponse doCreateUrl(CreateUrlRequest request, String idempotencyKey) {
        urlValidator.validate(request.longUrl());

        String alias = request.customAlias();
        boolean hasCustomAlias = alias != null && !alias.isBlank();

        if (hasCustomAlias && urlRepository.findByShortCode(alias).isPresent()) {
            throw new CustomAliasConflictException(alias);
        }

        // UUID-derived placeholder ensures no two concurrent inserts collide on the temp code.
        // It is replaced with base62(id) in the same transaction before commit.
        String tempCode = hasCustomAlias
                ? alias
                : "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);

        Url url = Url.builder()
                .shortCode(tempCode)
                .longUrl(request.longUrl())
                .expiresAt(request.expiresAt())
                .idempotencyKey(idempotencyKey)
                .active(true)
                .build();

        try {
            url = urlRepository.save(url); // IDENTITY INSERT — url.getId() is populated immediately
        } catch (DataIntegrityViolationException e) {
            // TOCTOU fix: another transaction committed the same alias between our pre-check and
            // this INSERT. The DB unique constraint is the true last guard; translate it cleanly.
            if (hasCustomAlias) {
                throw new CustomAliasConflictException(alias);
            }
            // For auto-generated codes base62(unique_id) should never collide — propagate.
            throw e;
        }

        if (!hasCustomAlias) {
            // Replace placeholder with the real base62-encoded DB ID
            url.setShortCode(base62Encoder.encode(url.getId()));
            url = urlRepository.save(url);
        }

        return toResponse(url);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolveUrl(String shortCode, String referrer) {
        // Read-through cache: serve from Redis when possible
        Optional<CachedUrl> cached = urlCacheService.get(shortCode);
        if (cached.isPresent()) {
            CachedUrl c = cached.get();
            if (!c.active()) throw new UrlNotActiveException(shortCode);
            if (c.expiresAt() != null && c.expiresAt().isBefore(OffsetDateTime.now())) {
                throw new UrlNotActiveException(shortCode);
            }
            // Publish asynchronously — redirect returns before Kafka ack
            clickEventProducer.publish(
                    new UrlClickedEvent(c.id(), c.shortCode(), referrer, OffsetDateTime.now()));
            return c.longUrl();
        }

        // Cache miss — load from DB and populate for active, non-expired URLs only
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (!url.isActive()) throw new UrlNotActiveException(shortCode);
        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new UrlNotActiveException(shortCode);
        }

        urlCacheService.put(url);
        clickEventProducer.publish(
                new UrlClickedEvent(url.getId(), url.getShortCode(), referrer, OffsetDateTime.now()));
        return url.getLongUrl();
    }

    @Override
    @Transactional
    public void deactivateUrl(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        url.setActive(false);
        urlRepository.save(url);
        // Synchronous eviction — must complete before this method returns so that the
        // very next resolveUrl call sees 410 even if the entry was cached moments ago.
        urlCacheService.evict(shortCode);
    }

    @Override
    @Transactional(readOnly = true)
    public UrlStatsResponse getStats(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        long totalClicks = urlClickRepository.countByUrl_Id(url.getId());
        OffsetDateTime lastClickedAt = urlClickRepository
                .findLastClickedAtByUrlId(url.getId())
                .orElse(null);

        return new UrlStatsResponse(
                url.getShortCode(),
                url.getLongUrl(),
                totalClicks,
                lastClickedAt,
                url.getCreatedAt()
        );
    }

    private CreateUrlResponse toResponse(Url url) {
        return new CreateUrlResponse(
                url.getShortCode(),
                appProperties.getBaseUrl() + "/" + url.getShortCode(),
                url.getLongUrl(),
                url.getCreatedAt(),
                url.getExpiresAt()
        );
    }
}
