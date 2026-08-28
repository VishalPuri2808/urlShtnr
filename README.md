<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:1d4ed8,100:6366f1&height=200&section=header&text=URL%20Shortener&fontSize=52&fontColor=ffffff&fontAlignY=38&desc=Production-grade%20link%20shortener%20with%20analytics%20%26%20QR%20codes&descAlignY=58&descSize=18" width="100%" />

<br/>

[![Version](https://img.shields.io/badge/version-1.0.0-1d4ed8?style=flat-square&logo=github)](https://github.com)
[![Backend](https://img.shields.io/badge/backend-Spring%20Boot%203.3-6db33f?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/java-21-f89820?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21)
[![Database](https://img.shields.io/badge/database-PostgreSQL%2016-336791?style=flat-square&logo=postgresql&logoColor=white)](https://postgresql.org)
[![Cache](https://img.shields.io/badge/cache-Redis%207-dc382d?style=flat-square&logo=redis&logoColor=white)](https://redis.io)
[![Messaging](https://img.shields.io/badge/messaging-Kafka%203.7-231f20?style=flat-square&logo=apachekafka&logoColor=white)](https://kafka.apache.org)
[![Build](https://img.shields.io/badge/build-Maven-c71a36?style=flat-square&logo=apachemaven&logoColor=white)](https://maven.apache.org)
[![Tests](https://img.shields.io/badge/tests-81%20passing-10b981?style=flat-square&logo=junit5&logoColor=white)](https://junit.org/junit5)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

<br/>

**Shorten long URLs, redirect with sub-millisecond Redis cache, track every click asynchronously via Kafka, and generate scannable QR codes — production-ready out of the box.**

[🚀 Quick Start](#-quick-start) · [📖 API Reference](#-api-reference) · [🏗 Architecture](docs/ARCHITECTURE.md) · [🐛 Report Bug](../../issues) · [✨ Request Feature](../../issues)

</div>

---

## 📋 Table of Contents

- [Overview](#-overview)
- [Request Pipeline](#-request-pipeline)
- [Features](#-features)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Quick Start](#-quick-start)
- [Web UI](#-web-ui)
- [API Reference](#-api-reference)
- [Database Schema](#-database-schema)
- [Deployment](#-deployment)
- [Contributing](#-contributing)

---

## 🧠 Overview

This service takes the "production-shaped" URL shortener spec and implements every constraint end-to-end — from SSRF-safe input validation through a Redis read-through cache to asynchronous Kafka click analytics, all backed by Flyway-managed PostgreSQL schema.

| Metric | Value |
|---|---|
| ⚡ Redirect latency (cache hit) | < 1 ms (Redis) |
| ⚡ Redirect latency (cache miss) | ~5 ms (Postgres) |
| 🔤 Short code scheme | base62 of DB BIGSERIAL — zero collisions by design |
| 🗂️ Short code space | 62⁶ ≈ **56 billion** 6-character codes |
| 📊 Click analytics | Async via Kafka — redirect never waits for write |
| 🖼️ QR code sizes | On-the-fly via ZXing, 100–1000 px, Redis-cached |
| 🧪 Test count | 81 tests (unit + Testcontainers integration) |
| 🛢️ Database | PostgreSQL 16 via Flyway migrations |

---

## 🔄 Request Pipeline

Each path through the service is optimised for its use case:

```
─────────────── Create Short URL (POST /api/v1/urls) ─────────────────

📥  Client Request
        │
        ▼
🛡️  SSRF Validation        ← UrlValidator: rejects private IPs, loopback,
        │                     link-local (169.254.x.x), RFC-1918 ranges
        ▼
🔑  Idempotency Check      ← findByIdempotencyKey → return existing if hit
        │
        ▼
💾  INSERT urls (temp)     ← IDENTITY INSERT → DB-generated BIGSERIAL ID
        │
        ▼
🔢  base62(id) → shortCode ← UPDATE short_code (same transaction)
        │
        ▼
✅  201 Created response


─────────────── Redirect (GET /{shortCode}) ──────────────────────────

📥  GET /{shortCode}
        │
        ▼
⚡  Redis Cache Lookup      ← UrlCacheService.get("url:{code}")
        │
        ├─ HIT  ──► active? expiry check ──► 302 / 410
        │
        └─ MISS ──► Postgres findByShortCode
                        │
                        ▼
                    active? expiry? ──► 404 / 410
                        │
                        ▼
                    Populate Redis  ← TTL = min(configured, time-to-expiry)
                        │
                        ▼
                    Publish UrlClickedEvent  ← fire-and-forget, non-blocking
                        │
                        ▼
                    302 Location: longUrl


─────────────── Analytics (async, Kafka consumer) ────────────────────

📨  UrlClickedEvent on url.click.events
        │
        ▼
📥  UrlClickEventConsumer  ← @KafkaListener, @Transactional
        │
        ▼
💾  INSERT url_clicks       ← only writer to this table
```

---

## ✨ Features

- **Three creation modes** — custom alias, auto-generated base62 code, or idempotent retry via `Idempotency-Key` header
- **SSRF guardrail** — rejects URLs pointing at private/reserved IP ranges at creation time; IPv4-mapped IPv6 (`::ffff:127.0.0.1`) explicitly handled
- **Redis read-through cache** — redirects served from Redis on the hot path; TTL capped at URL expiry time
- **Synchronous cache invalidation** — deactivating a URL evicts both the redirect cache and all QR code cache entries before returning 204
- **Async click analytics** — `UrlClickedEvent` published to Kafka; consumer writes `url_clicks` rows — redirect latency is never affected
- **QR code generation** — `/qrcode?size=N` endpoint using ZXing; size clamped to [100, 1000]; results Redis-cached under `qrcode:{code}:{size}`
- **Soft delete** — deactivation sets `is_active=false`; historical analytics retained per policy
- **Expiry support** — `expiresAt` field; expired links return 410; cache TTL auto-aligns to expiry
- **Custom aliases** — vanity short codes with TOCTOU race condition patched (DataIntegrityViolationException → 409)
- **Stats endpoint** — total clicks, last-clicked timestamp, creation date aggregated from `url_clicks`
- **Built-in Web UI** — Bitly-inspired dark-navy dashboard at `/` with Short Link and QR Code tabs

---

## 🛠️ Tech Stack

### Backend
| Dependency | Version | Purpose |
|---|---|---|
| `spring-boot-starter-web` | 3.3.4 | REST API framework |
| `spring-boot-starter-data-jpa` | 3.3.4 | ORM + repository layer |
| `spring-boot-starter-data-redis` | 3.3.4 | Lettuce Redis client |
| `spring-kafka` | 3.3.4 | Kafka producer + `@KafkaListener` consumer |
| `flyway-core` + `flyway-database-postgresql` | 10.x | DB migrations |
| `postgresql` | 42.x | JDBC driver |
| `zxing:core` + `zxing:javase` | 3.5.3 | QR code PNG generation |
| `spring-boot-starter-validation` | 3.3.4 | Bean validation |

### Testing
| Dependency | Purpose |
|---|---|
| JUnit 5 + Mockito | Unit tests (81 tests total) |
| Testcontainers (Postgres + Kafka + Redis) | Integration tests |
| `spring-kafka-test` + `@EmbeddedKafka` | Kafka integration tests |
| Awaitility | Async assertion for Kafka consumer tests |

### Infrastructure
| Service | Role |
|---|---|
| PostgreSQL 16 | Primary data store (Docker / cloud) |
| Redis 7 | Read-through redirect & QR code cache |
| Apache Kafka 3.7 (KRaft) | Click-analytics event bus (no Zookeeper) |
| Docker Compose | Local full-stack orchestration |

---

## 📁 Project Structure

```
urlShortner/
├── src/
│   ├── main/
│   │   ├── java/com/urlshortener/
│   │   │   ├── cache/            UrlCacheService (Redis get/put/evict)
│   │   │   ├── config/           AppProperties, KafkaConfig, RedisConfig
│   │   │   ├── controller/       RedirectController, UiController, UrlController
│   │   │   ├── dto/              CreateUrlRequest, CreateUrlResponse,
│   │   │   │                     UrlStatsResponse, CachedUrl  (Java records)
│   │   │   ├── event/            UrlClickedEvent  (Kafka payload)
│   │   │   ├── exception/        Domain exceptions + GlobalExceptionHandler
│   │   │   ├── kafka/            UrlClickEventProducer, UrlClickEventConsumer
│   │   │   ├── model/            Url, UrlClick  (JPA entities)
│   │   │   ├── repository/       UrlRepository, UrlClickRepository
│   │   │   ├── service/          UrlService, UrlServiceImpl, QrCodeService
│   │   │   └── util/             Base62Encoder, UrlValidator
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── static/index.html           ← built-in Web UI
│   │       └── db/migration/
│   │           └── V1__initial_schema.sql  ← Flyway migration
│   └── test/java/com/urlshortener/
│       ├── cache/           CacheInvalidationTest (Testcontainers)
│       ├── concurrency/     ConcurrencyBugTest, ConcurrencyIntegrationTest
│       ├── integration/     QrCodeIntegrationTest, UrlShortenerIntegrationTest
│       ├── kafka/           UrlClickEventConsumerTest
│       ├── service/         QrCodeServiceTest, UrlServiceImplTest
│       └── util/            Base62EncoderTest, UrlValidatorTest
├── docs/
│   └── ARCHITECTURE.md     ← component diagrams + all design trade-offs
├── docker-compose.yml
├── Dockerfile
├── .env.example
└── pom.xml
```

---

## 🚀 Quick Start

### Prerequisites

- JDK 21+ (JDK 25 from Eclipse Adoptium also works)
- Maven 3.9+
- Docker Desktop

### 1. Clone the repo

```bash
git clone https://github.com/<your-username>/urlShortner
cd urlShortner
```

### 2. Configure environment

```bash
cp .env.example .env
# Edit .env if needed — defaults work for local Docker Compose
```

### 3a. Full stack via Docker Compose *(recommended)*

```bash
docker-compose up --build
# Starts Postgres 16, Redis 7, Kafka 3.7 (KRaft), and the app
# App ready at http://localhost:8080 when health check passes
```

### 3b. Infrastructure only + local app

```bash
# Terminal 1 — infra
docker-compose up postgres redis kafka

# Terminal 2 — app (set JAVA_HOME if needed)
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dnet.bytebuddy.experimental=true"
# → http://localhost:8080
```

> **JDK note:** Lombok's annotation processor is broken on JDK 23+ due to internal API changes.
> All entity/DTO code uses explicit Java instead. The `-Dnet.bytebuddy.experimental=true` flag
> enables Mockito's ByteBuddy agent on JDK 23+. On JDK 21 (the spec target) neither flag is needed.

### 4. Run tests

```bash
mvn test                     # unit tests — no Docker required (81 tests)
# Start Docker Desktop first, then re-run to also execute Testcontainers suites
```

---

## 🖥️ Web UI

A built-in dashboard is served at **http://localhost:8080**.

| Tab | What it does |
|---|---|
| 🔗 **Short Link** | Paste a URL, set optional alias & expiry, get a short link with one-click Copy + QR toggle |
| ⚜ **QR Code** | Enter any existing short code to generate a scannable QR image at your chosen size |
| 📊 **Stats & Management** | Look up click count, last-click time, creation date; deactivate any link |

---

## 📡 API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/urls` | Create short URL — body: `longUrl`, optional `customAlias`, `expiresAt`; header: optional `Idempotency-Key` |
| `GET` | `/{shortCode}` | 302 redirect → long URL · 404 not found · 410 deactivated/expired |
| `GET` | `/api/v1/urls/{shortCode}/stats` | Total clicks, last-clicked timestamp, creation date |
| `DELETE` | `/api/v1/urls/{shortCode}` | Soft-delete (sets `is_active=false`); subsequent redirects return 410 |
| `GET` | `/api/v1/urls/{shortCode}/qrcode` | PNG QR code; `?size=N` clamped to [100, 1000], default 300 |

### Create short URL — example

```bash
# Minimal
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com/very/long/path"}' | jq .

# With alias, expiry and idempotency key
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: campaign-launch-001" \
  -d '{
    "longUrl":     "https://example.com/long/path?ref=email",
    "customAlias": "launch",
    "expiresAt":   "2026-12-31T23:59:59Z"
  }' | jq .
```

**Response 201:**
```json
{
  "shortCode": "launch",
  "shortUrl":  "http://localhost:8080/launch",
  "longUrl":   "https://example.com/long/path?ref=email",
  "createdAt": "2026-08-28T10:00:00Z",
  "expiresAt": "2026-12-31T23:59:59Z"
}
```

| Status | Meaning |
|--------|---------|
| 201 | Created (or idempotency hit — returns original) |
| 400 | Malformed URL or SSRF-blocked private IP range |
| 409 | Custom alias already in use |

### QR code — example

```bash
# Save a 400×400 px PNG
curl -s "http://localhost:8080/api/v1/urls/launch/qrcode?size=400" --output qr.png
```

---

## 🗄️ Database Schema

```
urls                                     url_clicks
─────────────────────────────────        ──────────────────────────
id              bigserial   PK           id          bigserial  PK
short_code      varchar(20) UNIQUE NN    url_id      bigint     FK → urls(id)
long_url        text        NN           clicked_at  timestamptz NN
created_at      timestamptz NN           referrer    text
expires_at      timestamptz                          ↑
is_active       boolean     NN DEFAULT true    Written ONLY by Kafka consumer
idempotency_key varchar(255) UNIQUE
```

**Indexes:**
- `idx_urls_short_code` — primary redirect lookup path
- `idx_urls_idempotency_key WHERE idempotency_key IS NOT NULL` — sparse index
- `idx_url_clicks_url_id` — stats aggregation
- `idx_url_clicks_clicked_at` — time-range analytics

Schema is managed entirely by **Flyway** (`V1__initial_schema.sql`). Hibernate is set to `ddl-auto: validate` — it never touches the schema.

---

## 🚢 Deployment

### Free-tier hosting (recommended setup)

| Component | Service | Free tier |
|---|---|---|
| App | [Render.com](https://render.com) | Web service (sleeps after 15 min) |
| PostgreSQL | [Neon.tech](https://neon.tech) | Always-free, 512 MB |
| Redis | [Upstash Redis](https://upstash.com) | 10,000 req/day |
| Kafka | [Upstash Kafka](https://upstash.com/kafka) | 10,000 msg/day |

Set `DB_SSL_PARAMS=?sslmode=require` when using Neon or other cloud Postgres providers.

### Docker image

```dockerfile
# Two-stage build — Maven compile → eclipse-temurin:21-jre-alpine runtime
docker build -t url-shortener .
docker run -p 8080:8080 --env-file .env url-shortener
```

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `urlshortener` | Database name |
| `DB_USER` | `urlshortener` | Database user |
| `DB_PASSWORD` | `urlshortener` | **Change in production** |
| `DB_SSL_PARAMS` | *(empty)* | Append `?sslmode=require` for cloud DBs |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka bootstrap (9094 = Docker external listener) |
| `SERVER_PORT` | `8080` | HTTP port |
| `BASE_URL` | `http://localhost:8080` | Prefix for `shortUrl` in responses |
| `CACHE_TTL_SECONDS` | `3600` | Redis TTL for cached entries |
| `KAFKA_CLICK_EVENTS_TOPIC` | `url.click.events` | Kafka topic name |

Copy `.env.example` to `.env` — the file is git-ignored and never committed.

---

## 🤝 Contributing

Contributions are welcome! Please open an issue first to discuss what you'd like to change.

1. Fork the repository
2. Create your branch: `git checkout -b feature/amazing-feature`
3. Commit your changes: `git commit -m 'Add amazing feature'`
4. Push to the branch: `git push origin feature/amazing-feature`
5. Open a Pull Request

---

## 📄 License

Distributed under the MIT License. See [`LICENSE`](LICENSE) for more information.

---

<div align="center">

Made with ❤️ by [rishi-2399](https://github.com/rishi-2399)

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:1d4ed8,100:6366f1&height=100&section=footer" width="100%" />

</div>

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
