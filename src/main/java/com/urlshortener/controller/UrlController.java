package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.service.QrCodeService;
import com.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private final UrlService    urlService;
    private final QrCodeService qrCodeService;

    public UrlController(UrlService urlService, QrCodeService qrCodeService) {
        this.urlService    = urlService;
        this.qrCodeService = qrCodeService;
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

    /**
     * Returns a QR code PNG encoding the short URL.
     * size is clamped to [100, 1000]; out-of-range values are normalised, not rejected,
     * because pixel count is a display preference not a semantic error.
     */
    @GetMapping(value = "/{shortCode}/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQrCode(
            @PathVariable String shortCode,
            @RequestParam(defaultValue = "300") int size) {
        String shortUrl = urlService.resolveShortUrl(shortCode);
        byte[] png = qrCodeService.getOrGenerate(shortUrl, shortCode, size);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    /** Soft-deletes the URL; subsequent redirects return 410 Gone. */
    @DeleteMapping("/{shortCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateUrl(@PathVariable String shortCode) {
        urlService.deactivateUrl(shortCode);
    }
}
