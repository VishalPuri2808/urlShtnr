package com.urlshortener.exception;

/** Thrown when a requested custom alias is already in use; maps to HTTP 409 Conflict. */
public class CustomAliasConflictException extends RuntimeException {

    public CustomAliasConflictException(String alias) {
        super("Short code is already in use: " + alias);
    }
}
