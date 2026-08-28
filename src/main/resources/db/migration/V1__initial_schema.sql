-- V1: initial schema for URL Shortener service
-- All timestamps use TIMESTAMPTZ (timezone-aware) to avoid DST ambiguity.

CREATE TABLE urls (
    id              BIGSERIAL       PRIMARY KEY,
    short_code      VARCHAR(20)     NOT NULL,
    long_url        TEXT            NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,                                   -- NULL means no expiry
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,         -- FALSE = soft-deleted, returns 410
    idempotency_key VARCHAR(255),                                  -- NULL for requests without a key

    CONSTRAINT uq_urls_short_code      UNIQUE (short_code),
    CONSTRAINT uq_urls_idempotency_key UNIQUE (idempotency_key)
);

-- Primary read path: redirect lookup by short code
CREATE INDEX idx_urls_short_code ON urls (short_code);

-- Partial index: only rows with a key need to be checked for idempotency
CREATE INDEX idx_urls_idempotency_key ON urls (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE url_clicks (
    id          BIGSERIAL   PRIMARY KEY,
    url_id      BIGINT      NOT NULL REFERENCES urls (id),
    clicked_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    referrer    TEXT                                               -- NULL when Referer header absent
);

-- Aggregation queries for /stats scan by url_id
CREATE INDEX idx_url_clicks_url_id     ON url_clicks (url_id);
-- Range queries for time-series analytics
CREATE INDEX idx_url_clicks_clicked_at ON url_clicks (clicked_at);
