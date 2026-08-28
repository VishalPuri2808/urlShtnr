package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** Aggregated analytics returned by GET /api/v1/urls/{shortCode}/stats. */
@Data
@Builder
public class UrlStatsResponse {

    private String shortCode;
    private String longUrl;
    private long   totalClicks;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime lastClickedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime createdAt;
}
