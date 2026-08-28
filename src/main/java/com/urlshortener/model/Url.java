package com.urlshortener.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

// Represents a shortened URL mapping; soft-deleted via is_active rather than hard-deleted
@Entity
@Table(name = "urls")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Url {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 20)
    private String shortCode;

    @Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
    private String longUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Null means the link never expires. */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /**
     * FALSE triggers a 410 Gone on redirect. We never hard-delete so historical
     * analytics rows in url_clicks remain intact (see TASK 6 for the policy discussion).
     */
    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Null for requests that omit the Idempotency-Key header. */
    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
