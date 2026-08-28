package com.urlshortener.service;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.repository.UrlClickRepository;
import com.urlshortener.repository.UrlRepository;
import org.springframework.stereotype.Service;

/**
 * Implementation shell — business logic is added task by task:
 *   TASK 2: createUrl, resolveUrl (base62, idempotency, SSRF validation)
 *   TASK 3: Redis read-through cache + synchronous invalidation
 *   TASK 4: Kafka event publishing inside resolveUrl; getStats
 */
@Service
public class UrlServiceImpl implements UrlService {

    private final UrlRepository      urlRepository;
    private final UrlClickRepository urlClickRepository;

    public UrlServiceImpl(UrlRepository urlRepository, UrlClickRepository urlClickRepository) {
        this.urlRepository      = urlRepository;
        this.urlClickRepository = urlClickRepository;
    }

    @Override
    public CreateUrlResponse createUrl(CreateUrlRequest request, String idempotencyKey) {
        throw new UnsupportedOperationException("Implemented in TASK 2");
    }

    @Override
    public String resolveUrl(String shortCode, String referrer) {
        throw new UnsupportedOperationException("Implemented in TASK 2");
    }

    @Override
    public void deactivateUrl(String shortCode) {
        throw new UnsupportedOperationException("Implemented in TASK 2");
    }

    @Override
    public UrlStatsResponse getStats(String shortCode) {
        throw new UnsupportedOperationException("Implemented in TASK 4");
    }
}
