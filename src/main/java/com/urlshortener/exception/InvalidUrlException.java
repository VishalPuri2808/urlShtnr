package com.urlshortener.exception;

/**
 * Thrown when a submitted long URL is syntactically malformed or targets a
 * private/internal IP range (SSRF guardrail). Maps to HTTP 400 Bad Request.
 */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String message) {
        super(message);
    }
}
