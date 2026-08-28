package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;

/** Aggregated analytics returned by GET /api/v1/urls/{shortCode}/stats. */
public record UrlStatsResponse(
        String shortCode,
        String longUrl,
        long totalClicks,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime lastClickedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime createdAt
) {}
