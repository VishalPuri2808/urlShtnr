package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;

/** Response envelope returned by POST /api/v1/urls (new creation or idempotency hit). */
public record CreateUrlResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime expiresAt
) {}
