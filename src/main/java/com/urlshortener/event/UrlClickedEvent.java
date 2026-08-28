package com.urlshortener.event;

import java.time.OffsetDateTime;

/**
 * Emitted by the redirect handler and consumed by the analytics writer.
 * A record guarantees immutability and gives us equals/hashCode for free,
 * which simplifies test assertions.
 */
public record UrlClickedEvent(
        Long           urlId,
        String         shortCode,
        String         referrer,      // null when Referer header was absent
        OffsetDateTime clickedAt
) {}
