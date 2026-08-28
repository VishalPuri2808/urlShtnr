package com.urlshortener.service;

import com.urlshortener.config.AppProperties;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.exception.CustomAliasConflictException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.exception.UrlNotActiveException;
import com.urlshortener.model.Url;
import com.urlshortener.repository.UrlClickRepository;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.util.Base62Encoder;
import com.urlshortener.util.UrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * TASK 2: createUrl, resolveUrl, deactivateUrl
 * TASK 3: Redis cache layered onto resolveUrl + synchronous invalidation in deactivateUrl
 * TASK 4: Kafka event published in resolveUrl; getStats implemented
 */
@Service
public class UrlServiceImpl implements UrlService {

    private final UrlRepository      urlRepository;
    private final UrlClickRepository urlClickRepository;
    private final Base62Encoder      base62Encoder;
    private final UrlValidator       urlValidator;
    private final AppProperties      appProperties;

    public UrlServiceImpl(
            UrlRepository urlRepository,
            UrlClickRepository urlClickRepository,
            Base62Encoder base62Encoder,
            UrlValidator urlValidator,
            AppProperties appProperties) {
        this.urlRepository      = urlRepository;
        this.urlClickRepository = urlClickRepository;
        this.base62Encoder      = base62Encoder;
        this.urlValidator       = urlValidator;
        this.appProperties      = appProperties;
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

        url = urlRepository.save(url); // IDENTITY INSERT — url.getId() is populated immediately

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
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (!url.isActive()) {
            throw new UrlNotActiveException(shortCode);
        }
        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new UrlNotActiveException(shortCode);
        }
        return url.getLongUrl();
    }

    @Override
    @Transactional
    public void deactivateUrl(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        url.setActive(false);
        urlRepository.save(url);
    }

    @Override
    @Transactional(readOnly = true)
    public UrlStatsResponse getStats(String shortCode) {
        throw new UnsupportedOperationException("Implemented in TASK 4");
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
