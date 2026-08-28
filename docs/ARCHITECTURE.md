# Architecture: URL Shortener Service

## 1. System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Clients / Browsers                            │
└──────────────────────┬────────────────────────┬────────────────────────┘
                       │ POST /api/v1/urls       │ GET /{shortCode}
                       │ DELETE /api/v1/urls/…   │ GET /api/v1/…/stats
                       ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        Spring Boot Application                          │
│                                                                         │
│   UrlController              RedirectController                         │
│   (create / stats / delete)  (302 redirect)                             │
│          │                          │                                   │
│          └──────────── UrlService ──┘                                   │
│                             │                                           │
│             ┌───────────────┼─────────────────────┐                    │
│             ▼               ▼                     ▼                    │
│        UrlRepository   UrlCacheService      UrlClickEventProducer       │
│        (Postgres)      (Redis)              (Kafka publish)             │
│                                                                         │
│   UrlClickEventConsumer (Kafka @KafkaListener)                          │
│          │                                                               │
│          └──► UrlClickRepository (Postgres url_clicks)                 │
└──────┬──────────────────────┬────────────────────────────────────────── ┘
       │                      │
       ▼                      ▼
 PostgreSQL 16           Redis 7 / Kafka 3.7
```

---

## 2. Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| `RedirectController` | Routes `GET /{shortCode}` → delegates to `UrlService.resolveUrl()`; returns 302 |
| `UrlController` | CRUD surface: create, deactivate, stats |
| `UrlServiceImpl` | Business logic: idempotency check, SSRF validation, cache read-through, Kafka publish |
| `UrlCacheService` | Redis wrapper: `get`, `put` (with TTL capping), `evict` |
| `UrlValidator` | Rejects malformed URLs and private/reserved IP ranges (SSRF guard) |
| `Base62Encoder` | Converts DB-generated ID → short code; bijective, no collision possible |
| `UrlClickEventProducer` | Fire-and-forget Kafka send; uses `shortCode` as partition key |
| `UrlClickEventConsumer` | `@KafkaListener` → persists one `UrlClick` row per message; only writer to `url_clicks` |
| `GlobalExceptionHandler` | Maps domain exceptions to RFC 9457 ProblemDetail responses |

---

## 3. Data Model

```sql
urls (
  id              bigserial PK,
  short_code      varchar(20) UNIQUE NOT NULL,   -- base62(id) or custom alias
  long_url        text NOT NULL,
  created_at      timestamptz NOT NULL,
  expires_at      timestamptz,                   -- NULL = never expires
  is_active       boolean NOT NULL DEFAULT true, -- false = soft-deleted (410)
  idempotency_key varchar(255) UNIQUE            -- NULL for keyless requests
)

url_clicks (
  id          bigserial PK,
  url_id      bigint NOT NULL REFERENCES urls(id),
  clicked_at  timestamptz NOT NULL,
  referrer    text                               -- HTTP Referer header, nullable
)
```

**Indexes:**
- `idx_urls_short_code` — primary read path (redirect lookup)
- `idx_urls_idempotency_key WHERE idempotency_key IS NOT NULL` — sparse, fast key check
- `idx_url_clicks_url_id` — stats aggregation
- `idx_url_clicks_clicked_at` — time-range analytics

---

## 4. Key Design Decisions

### 4.1 Short Code Generation: base62 of DB-generated ID

**Decision:** Short codes are produced by base62-encoding the PostgreSQL `BIGSERIAL` primary key.

**Alphabet:** `0-9a-zA-Z` (62 symbols). ID=1 → `"1"`, ID=62 → `"10"`, ID=62⁶ − 1 → `"ZZZZZZ"` (6 chars covers 56 billion URLs).

**Rationale:**
- Guaranteed zero collision — different IDs always produce different codes (bijective mapping).
- No retry loop; no application-level locking.
- O(log₆₂ n) code length — short for typical ID ranges.

**Trade-off — known risk (sequential/guessable):**
Short codes are monotonically increasing and therefore enumerable. A motivated actor can discover all valid short codes by iterating `1, 2, 3, …`. This is an inherent property of sequential ID encoding. Mitigations if enumeration prevention is required: (a) add a Hashids/shuffle layer on top without changing the storage model; (b) add authentication to redirect or stats endpoints. Neither mitigation is implemented in this codebase.

---

### 4.2 Redis Read-Through Cache

**Decision:** `resolveUrl()` checks Redis first; on a cache miss it loads from Postgres and populates the cache. Cache entries expire at `min(configured_ttl, time_to_expiry_of_url)`.

**Rationale:** Redirects are the highest-throughput path. Serving from Redis (sub-millisecond) vs Postgres avoids database saturation under load.

**Cache invalidation on deactivate:**
`deactivateUrl()` calls `urlCacheService.evict(shortCode)` *synchronously* before returning — the cache entry is guaranteed gone before the deactivation response is sent. This prevents the following race:
```
T1: deactivateUrl("abc")  → DB update active=false  (committed)
T2: resolveUrl("abc")     → cache HIT → returns stale 302  ← prevented
T1:                       → evict("abc")              (after T2 already served)
```

**Trade-off:** If the Redis `DEL` fails after the DB commit (Redis crash, network partition), the cache retains a stale active entry. On the next cache hit the `active` flag in `CachedUrl` is checked, but it was captured as `true` at cache-population time. A restart of the app (or Redis TTL expiry) would self-heal. For high-availability deployments, consider writing `active=false` into the cache entry rather than evicting — that way a stale entry still returns 410.

---

### 4.3 Kafka Async Click Analytics

**Decision:** `resolveUrl()` publishes a `UrlClickedEvent` to the `url.click.events` Kafka topic and returns immediately. A separate `@KafkaListener` consumer aggregates events into `url_clicks`.

**Rationale:** Click counting must never add latency to the redirect path. Kafka decouples the hot path from the write path and enables fan-out (multiple consumers could process the same event for different analytics purposes).

**Partition key:** `shortCode` — all clicks for the same URL go to the same partition, preserving per-URL ordering.

**Trade-off — at-least-once delivery:** The Kafka producer does not use idempotent mode (`enable.idempotence=true`) or transactions. A producer crash mid-send can result in a lost click event. Conversely, a consumer failure before committing its offset can result in a duplicate `url_clicks` row. For precise counts, enable idempotent producer and consider a deduplication strategy on the consumer.

---

### 4.4 Idempotency Key

**Decision:** An optional `Idempotency-Key` request header enables safe retries. The key is stored in the `idempotency_key` column (unique constraint). Duplicate keys return the original record without a new insert.

**Rationale:** Standard pattern for POST APIs (Stripe, Adyen, etc.) — allows clients to retry on network timeout without creating duplicate short URLs.

**TOCTOU race (fixed in TASK 5):** The check-then-insert pattern is inherently racy. If two concurrent requests with the same key both pass the `findByIdempotencyKey` check, the second INSERT hits the unique constraint and receives a `DataIntegrityViolationException`. For custom-alias races, this is caught and translated to `CustomAliasConflictException` (409). The idempotency-key race (same key, two threads) is partially mitigated by the unique constraint; full mitigation would require a retry-then-lookup pattern in the catch block.

---

### 4.5 SSRF Guardrail

**Decision:** `UrlValidator` resolves the submitted hostname at creation time and rejects URLs that resolve to loopback, RFC-1918 private, link-local (169.254.x.x), any-local, or multicast addresses.

**Rationale:** A URL shortener that issues 302 redirects is an SSRF amplifier — a stored URL pointing at `http://169.254.169.254/latest/meta-data/` would forward cloud clients to the instance-metadata endpoint.

**Known limitations:**
1. **DNS rebinding** — we resolve once at creation time. The DNS record could change to a private IP after validation. Full mitigation requires re-resolving at redirect time (not implemented — this service issues 302 to clients, not server-side fetches, so the risk is lower than a server-side proxy).
2. **IPv4-mapped IPv6** (`::ffff:127.0.0.1`) — explicitly detected and blocked.
3. **Unresolvable hostnames** — allowed (staging URLs, future-valid CDN names).

---

### 4.6 Analytics Retention & Short Code Reuse Policy (TASK 6 decisions)

**Analytics retention — Option A (retain forever):**
`url_clicks` rows are never deleted. `deactivateUrl()` performs a soft delete on `urls` only. The `/stats` endpoint returns data for deactivated and expired URLs. Rationale: analytics are audit data; accidental deactivations should be recoverable; disk cost is negligible.

**Short code reuse — Option A (never reuse):**
Expired short codes remain permanently reserved — the `urls` row stays in Postgres, keeping the `short_code` under the unique constraint. A `POST` with a `customAlias` matching an expired code returns 409. Rationale: reusing a code that has been bookmarked, printed, or embedded in an email would silently redirect users to an unintended destination.

---

## 5. Control Flow Diagrams

### 5.1 Create Short URL

```
POST /api/v1/urls
        │
        ├── Idempotency-Key present?
        │       └── Yes → findByIdempotencyKey → found? → return existing record (201)
        │
        ├── UrlValidator.validate(longUrl)          ← rejects SSRF / malformed
        │
        ├── customAlias provided?
        │       └── Yes → findByShortCode → found? → throw CustomAliasConflictException (409)
        │
        ├── INSERT urls (temp UUID code) → get DB-generated id
        │       └── DataIntegrityViolationException → CustomAliasConflictException (409)
        │
        ├── if no custom alias: UPDATE short_code = base62(id)
        │
        └── return CreateUrlResponse (201)
```

### 5.2 Redirect

```
GET /{shortCode}
        │
        ├── UrlCacheService.get(shortCode)
        │       └── HIT → check active + expiry → publish UrlClickedEvent → return 302
        │
        ├── MISS → UrlRepository.findByShortCode
        │       └── not found → 404
        │
        ├── is_active=false OR expiresAt < now → 410
        │
        ├── UrlCacheService.put(url)   ← only caches active, non-expired URLs
        │
        ├── UrlClickEventProducer.publish(UrlClickedEvent)   ← fire-and-forget, non-blocking
        │
        └── return 302 Location: longUrl
```

### 5.3 Deactivate

```
DELETE /api/v1/urls/{shortCode}
        │
        ├── UrlRepository.findByShortCode → not found → 404
        │
        ├── url.setActive(false) → UrlRepository.save()   ← DB commit
        │
        ├── UrlCacheService.evict(shortCode)               ← synchronous Redis DEL
        │
        └── return 204 No Content
```

### 5.4 Click Event Flow (async)

```
resolveUrl()                       Kafka broker            UrlClickEventConsumer
     │                                  │                         │
     ├── publish(UrlClickedEvent) ──►  topic: url.click.events    │
     │                                  │                         │
     └── return longUrl                 ├──────────── poll ◄──────┤
         (immediately)                  │                         │
                                        └── deliver event ──────► consume()
                                                                   │
                                                             findById(urlId)
                                                                   │
                                                       urlClickRepository.save()
                                                    (url_clicks row inserted)
```

---

## 6. Non-Production Gaps (items to address before going live)

| Gap | Risk | Mitigation |
|-----|------|-----------|
| Sequential short codes are enumerable | Information disclosure | Add Hashids shuffle layer or access control on redirect |
| Idempotency-key TOCTOU not fully closed | Duplicate creates on concurrent identical keys | Add retry-then-lookup in `DataIntegrityViolationException` catch for idempotency_key column |
| Redis eviction failure after DB commit | Stale 302 served for a deactivated URL | Write `active=false` into cache instead of deleting; or use Redis transactions |
| Kafka at-least-once | Duplicate `url_clicks` rows | Enable `enable.idempotence=true`; add consumer-side deduplication |
| No authentication / rate limiting | Anyone can create/enumerate URLs | Add API key, JWT, or OAuth2; add rate limiter (e.g., Bucket4j + Redis) |
| Single Kafka replica (`replicas=1`) | Data loss if broker restarts | Set `replicas=3`, `acks=all`, `min.insync.replicas=2` for production |
| DNS rebinding not prevented at redirect time | SSRF via time-of-check-time-of-use | Re-validate destination IP in a server-side proxy mode if needed |
