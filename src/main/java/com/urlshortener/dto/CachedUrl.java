package com.urlshortener.dto;

import java.time.OffsetDateTime;

/**
 * Lightweight value object stored in Redis.
 * Decouples the cache layer from the JPA entity so schema changes don't
 * silently corrupt cached data — only this record and its mapper need updating.
 */
public record CachedUrl(
        Long           id,
        String         shortCode,
        String         longUrl,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        boolean        active
) {}
