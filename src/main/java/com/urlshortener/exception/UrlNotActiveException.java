package com.urlshortener.exception;

/**
 * Thrown when a URL is deactivated (soft-deleted) or past its expiry date.
 * Maps to HTTP 410 Gone — intentionally different from 404 so clients can
 * distinguish "never existed" from "existed but is no longer available".
 */
public class UrlNotActiveException extends RuntimeException {

    public UrlNotActiveException(String shortCode) {
        super("URL is no longer active: " + shortCode);
    }
}
