package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/**
 * Request body for POST /api/v1/urls.
 * A record guarantees immutability; Jackson 2.15 (bundled in Spring Boot 3.x)
 * deserialises records via the canonical constructor.
 */
public record CreateUrlRequest(
        @NotBlank(message = "longUrl must not be blank") String longUrl,
        String customAlias,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime expiresAt
) {}
