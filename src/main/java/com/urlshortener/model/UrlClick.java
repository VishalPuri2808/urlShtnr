package com.urlshortener.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

// Written exclusively by the Kafka consumer — never in the synchronous redirect path
@Entity
@Table(name = "url_clicks")
public class UrlClick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "url_id", nullable = false)
    private Url url;

    @Column(name = "clicked_at", nullable = false)
    private OffsetDateTime clickedAt;

    /** Null when the HTTP Referer header was absent. */
    @Column(name = "referrer", columnDefinition = "TEXT")
    private String referrer;

    protected UrlClick() {}

    private UrlClick(Builder b) {
        this.id        = b.id;
        this.url       = b.url;
        this.clickedAt = b.clickedAt;
        this.referrer  = b.referrer;
    }

    public Long           getId()        { return id; }
    public Url            getUrl()       { return url; }
    public OffsetDateTime getClickedAt() { return clickedAt; }
    public String         getReferrer()  { return referrer; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long id;
        private Url url;
        private OffsetDateTime clickedAt;
        private String referrer;

        public Builder id(Long id)                           { this.id = id; return this; }
        public Builder url(Url url)                          { this.url = url; return this; }
        public Builder clickedAt(OffsetDateTime clickedAt)   { this.clickedAt = clickedAt; return this; }
        public Builder referrer(String referrer)             { this.referrer = referrer; return this; }
        public UrlClick build() { return new UrlClick(this); }
    }
}
