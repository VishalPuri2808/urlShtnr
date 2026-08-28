package com.urlshortener.controller;

import com.urlshortener.service.UrlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final UrlService urlService;

    // Spring auto-injects the single constructor; explicit to avoid Lombok/JDK-25 AP issue
    public RedirectController(UrlService urlService) {
        this.urlService = urlService;
    }

    /**
     * Resolves a short code and issues a 302 redirect.
     * The Referer header is forwarded to the service for analytics (TASK 4).
     * Cache lookup and Kafka event publishing happen inside the service (TASK 3 / TASK 4).
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortCode,
            HttpServletRequest request) {

        String referrer = request.getHeader(HttpHeaders.REFERER);
        String longUrl  = urlService.resolveUrl(shortCode, referrer);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, longUrl)
                .build();
    }
}
