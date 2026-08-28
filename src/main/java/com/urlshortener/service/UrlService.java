package com.urlshortener.service;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;

public interface UrlService {

    /**
     * Creates a new short URL.
     * If idempotencyKey is non-null and matches an existing record, returns that
     * record unchanged rather than creating a duplicate.
     */
    CreateUrlResponse createUrl(CreateUrlRequest request, String idempotencyKey);

    /**
     * Resolves a short code to its destination long URL.
     * Throws UrlNotFoundException (404) if the code doesn't exist.
     * Throws UrlNotActiveException (410) if deactivated or expired.
     * Also publishes a UrlClickedEvent to Kafka (added in TASK 4).
     */
    String resolveUrl(String shortCode, String referrer);

    /**
     * Soft-deletes the URL and synchronously invalidates its Redis cache entry.
     * After this call any cached redirect must return 410, not the stale long URL.
     */
    void deactivateUrl(String shortCode);

    /** Returns aggregated click stats driven by the url_clicks table. */
    UrlStatsResponse getStats(String shortCode);
}
