package com.urlshortener.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves index.html directly for GET / so Spring Boot's WelcomePageHandlerMapping
 * never forwards to /index.html, which would otherwise be intercepted by
 * RedirectController's @GetMapping("/{shortCode}") and return 404.
 */
@Controller
public class UiController {

    @GetMapping(value = "/", produces = "text/html")
    @ResponseBody
    public ResponseEntity<Resource> home() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/index.html"));
    }
}
