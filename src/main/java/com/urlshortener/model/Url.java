package com.urlshortener.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

// Represents a shortened URL mapping; soft-deleted via is_active rather than hard-deleted
@Entity
@Table(name = "urls")
public class Url {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Policy (TASK 6-A): once assigned, a short code is permanently reserved — expired codes are
    // never recycled so bookmarked links never silently redirect to unintended destinations.
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
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Null for requests that omit the Idempotency-Key header. */
    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    /** Required by JPA; use builder() for all other construction. */
    protected Url() {}

    private Url(Builder b) {
        this.id            = b.id;
        this.shortCode     = b.shortCode;
        this.longUrl       = b.longUrl;
        this.createdAt     = b.createdAt;
        this.expiresAt     = b.expiresAt;
        this.active        = b.active;
        this.idempotencyKey = b.idempotencyKey;
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    public Long            getId()             { return id; }
    public String          getShortCode()      { return shortCode; }
    public String          getLongUrl()        { return longUrl; }
    public OffsetDateTime  getCreatedAt()      { return createdAt; }
    public OffsetDateTime  getExpiresAt()      { return expiresAt; }
    public boolean         isActive()          { return active; }
    public String          getIdempotencyKey() { return idempotencyKey; }

    // ── Setters for mutable fields ────────────────────────────────────────────
    public void setId(Long id)                           { this.id = id; }
    public void setShortCode(String shortCode)           { this.shortCode = shortCode; }
    public void setActive(boolean active)                { this.active = active; }
    public void setExpiresAt(OffsetDateTime expiresAt)   { this.expiresAt = expiresAt; }
    public void setCreatedAt(OffsetDateTime createdAt)   { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long id;
        private String shortCode;
        private String longUrl;
        private OffsetDateTime createdAt;
        private OffsetDateTime expiresAt;
        private boolean active = true;
        private String idempotencyKey;

        public Builder id(Long id)                             { this.id = id; return this; }
        public Builder shortCode(String shortCode)             { this.shortCode = shortCode; return this; }
        public Builder longUrl(String longUrl)                 { this.longUrl = longUrl; return this; }
        public Builder createdAt(OffsetDateTime createdAt)     { this.createdAt = createdAt; return this; }
        public Builder expiresAt(OffsetDateTime expiresAt)     { this.expiresAt = expiresAt; return this; }
        public Builder active(boolean active)                  { this.active = active; return this; }
        public Builder idempotencyKey(String idempotencyKey)   { this.idempotencyKey = idempotencyKey; return this; }
        public Url build() { return new Url(this); }
    }
}
