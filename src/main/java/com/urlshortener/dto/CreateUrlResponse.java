package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** Response envelope returned by POST /api/v1/urls (new creation or idempotency hit). */
@Data
@Builder
public class CreateUrlResponse {

    private String shortCode;
    private String shortUrl;
    private String longUrl;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime expiresAt;
}
