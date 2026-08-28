package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    /**
     * Creates a short URL. Supplying an Idempotency-Key header makes the
     * operation safe to retry — duplicate keys return the original result (HTTP 201).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUrlResponse createUrl(
            @Valid @RequestBody CreateUrlRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return urlService.createUrl(request, idempotencyKey);
    }

    /** Returns total clicks, last-clicked timestamp, and creation date. */
    @GetMapping("/{shortCode}/stats")
    public UrlStatsResponse getStats(@PathVariable String shortCode) {
        return urlService.getStats(shortCode);
    }

    /** Soft-deletes the URL; subsequent redirects return 410 Gone. */
    @DeleteMapping("/{shortCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateUrl(@PathVariable String shortCode) {
        urlService.deactivateUrl(shortCode);
    }
}
