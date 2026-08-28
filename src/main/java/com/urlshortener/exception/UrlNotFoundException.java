package com.urlshortener.exception;

/** Thrown when a short code does not exist in the database; maps to HTTP 404. */
public class UrlNotFoundException extends RuntimeException {

    public UrlNotFoundException(String shortCode) {
        super("No URL found for short code: " + shortCode);
    }
}
