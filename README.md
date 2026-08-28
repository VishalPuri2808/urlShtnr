# URL Shortener Service

A production-shaped URL shortener built with Java 21, Spring Boot 3.x, PostgreSQL, Redis, and Kafka.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | Project compiles to `--release 21`; JDK 25 works with Lombok disabled (see below) |
| Maven | 3.9+ | |
| Docker Desktop | any recent | Required for `docker-compose` and Testcontainers integration tests |

> **JDK note**: The project was developed against JDK 25 (latest Adoptium at time of writing).
> Lombok's code-generation annotations (`@Getter`, `@Builder`, etc.) are broken on JDK 23+,
> so all entity/DTO code uses explicit Java instead.  On JDK 21 (the spec target), Lombok works
> normally and could be re-introduced.

---

## Quick Start with Docker Compose

```bash
cp .env.example .env          # review and fill in secrets for non-dev environments
docker-compose up --build     # starts Postgres 16, Redis 7, Kafka 3.7 (KRaft), and the app
```

The service is ready at **http://localhost:8080** once the `app` container passes its health check.

---

## Running Locally (app only, infra in Docker)

```bash
# Start only the infrastructure services
docker-compose up postgres redis kafka

# In a second terminal, run the app against the local infra
mvn spring-boot:run
```

---

## Running Tests

```bash
# Unit tests — no Docker required
mvn test

# Full suite including Testcontainers integration tests — Docker must be running
mvn test   # @Testcontainers(disabledWithoutDocker=true) skips gracefully if Docker is down
```

---

## API Reference

### POST /api/v1/urls — Create a short URL

```bash
# Minimal request
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://www.example.com"}' | jq .
```

```bash
# With custom alias, expiry, and idempotency key (safe to retry)
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-confirmation-9f3a" \
  -d '{
    "longUrl":      "https://www.example.com/long/path?ref=email",
    "customAlias":  "promo-aug",
    "expiresAt":    "2026-12-31T23:59:59Z"
  }' | jq .
```

**Response 201:**
```json
{
  "shortCode": "promo-aug",
  "shortUrl":  "http://localhost:8080/promo-aug",
  "longUrl":   "https://www.example.com/long/path?ref=email",
  "createdAt": "2026-08-27T10:00:00Z",
  "expiresAt": "2026-12-31T23:59:59Z"
}
```

| Status | Meaning |
|--------|---------|
| 201 | Created (or idempotency hit — returns original) |
| 400 | Malformed URL or SSRF-blocked target |
| 409 | Custom alias already in use |

---

### GET /{shortCode} — Redirect

```bash
curl -v http://localhost:8080/promo-aug
# < HTTP/1.1 302 Found
# < Location: https://www.example.com/long/path?ref=email
```

| Status | Meaning |
|--------|---------|
| 302 | Found — `Location` header contains the destination |
| 404 | Short code does not exist |
| 410 | URL was deactivated or has passed its expiry |

---

### GET /api/v1/urls/{shortCode}/stats — Click analytics

```bash
curl -s http://localhost:8080/api/v1/urls/promo-aug/stats | jq .
```

**Response 200:**
```json
{
  "shortCode":    "promo-aug",
  "longUrl":      "https://www.example.com/long/path?ref=email",
  "totalClicks":  42,
  "lastClickedAt":"2026-08-27T15:30:00Z",
  "createdAt":    "2026-08-27T10:00:00Z"
}
```

> Stats are populated asynchronously by the Kafka consumer; there is a brief lag between a
> redirect and the click appearing in `totalClicks`.

---

### DELETE /api/v1/urls/{shortCode} — Deactivate

```bash
curl -s -X DELETE http://localhost:8080/api/v1/urls/promo-aug
# HTTP 204 No Content
```

Subsequent redirects to `/promo-aug` return **410 Gone**.
Historical analytics are **retained indefinitely** (policy decision — see ARCHITECTURE.md).

---

### GET /api/v1/urls/{shortCode}/qrcode — QR code image

```bash
# Default 300×300 px PNG
curl -s http://localhost:8080/api/v1/urls/promo-aug/qrcode --output qr.png
open qr.png   # macOS; use xdg-open on Linux or start on Windows

# Custom size (clamped server-side to 100–1000 px)
curl -s "http://localhost:8080/api/v1/urls/promo-aug/qrcode?size=500" --output qr-500.png
```

The QR encodes the full short URL (e.g. `http://localhost:8080/promo-aug`). Any QR scanner
will redirect the user the same way a browser would.

| Status | Meaning |
|--------|--------|
| 200 | `Content-Type: image/png` — QR PNG in the response body |
| 400 | Out-of-range `size` is **not** rejected — it is silently clamped to [100, 1000] |
| 404 | Short code does not exist |
| 410 | URL was deactivated or expired |

---

## Web UI

A built-in dashboard is served at **http://localhost:8080**.

- **Shorten a URL** — paste any URL, optional custom alias and expiry, one-click Copy
- **Stats & Management** — look up click count, last-click time, creation date; Deactivate button with confirm prompt
- **QR code** — use the API endpoint directly: `GET /api/v1/urls/{shortCode}/qrcode`

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `urlshortener` | Database name |
| `DB_USER` | `urlshortener` | Database user |
| `DB_PASSWORD` | `urlshortener` | **Change in production** |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka bootstrap address (port 9094 = external Docker listener for host access) |
| `SERVER_PORT` | `8080` | HTTP port |
| `BASE_URL` | `http://localhost:8080` | Prefix used to build `shortUrl` in responses |
| `CACHE_TTL_SECONDS` | `3600` | Redis TTL for short-code entries |
| `KAFKA_CLICK_EVENTS_TOPIC` | `url.click.events` | Kafka topic for click events |

Copy `.env.example` to `.env` and set values — the file is git-ignored.

---

## Project Structure

```
src/
  main/java/com/urlshortener/
    cache/         UrlCacheService (Redis read-through)
    config/        AppProperties, KafkaConfig, RedisConfig
    controller/    RedirectController, UiController, UrlController
    dto/           CreateUrlRequest, CreateUrlResponse, UrlStatsResponse, CachedUrl (records)
    event/         UrlClickedEvent (Kafka payload)
    exception/     domain exceptions + GlobalExceptionHandler
    kafka/         UrlClickEventProducer, UrlClickEventConsumer
    model/         Url, UrlClick (JPA entities)
    repository/    UrlRepository, UrlClickRepository
    service/       UrlService interface, UrlServiceImpl, QrCodeService
    util/          Base62Encoder, UrlValidator
  main/resources/
    application.yml
    static/index.html                    (built-in Web UI)
    db/migration/V1__initial_schema.sql  (Flyway)
  test/java/com/urlshortener/
    cache/         CacheInvalidationTest (Testcontainers)
    concurrency/   ConcurrencyBugTest, ConcurrencyIntegrationTest (Testcontainers)
    integration/   QrCodeIntegrationTest, UrlShortenerIntegrationTest (Testcontainers)
    kafka/         UrlClickEventConsumerTest
    service/       QrCodeServiceTest, UrlServiceImplTest
    util/          Base62EncoderTest, UrlValidatorTest
```
