# Prism architecture choices from first principles

## Start from the product's invariants

1. A team's usage and spend must not disappear when a gateway process restarts.
2. Two requests arriving together must not silently overwrite each other's accounting.
3. A provider outage must not make the console, authentication, logs, or usage APIs unavailable.
4. A provider credential must never be sent to the browser.
5. The simplest deployment that meets those requirements is preferred over adding services without a measured need.

## Database choice

**Use PostgreSQL for shared or public deployments.** Request logs, monthly usage, and cache entries are shared state written by many concurrent requests. PostgreSQL provides a central client/server database with transaction and concurrency control. SQLite remains the no-setup option for local development and single-instance demos; it only allows one writer at a time, so it is not the database to share between multiple gateway copies. See the [PostgreSQL concurrency documentation](https://www.postgresql.org/docs/current/mvcc.html) and [SQLite's guidance on when to use it](https://www.sqlite.org/whentouse.html).

The Docker Compose environment runs PostgreSQL and applies versioned Flyway migrations at startup. A plain local `mvn spring-boot:run` continues to use SQLite, so developers do not need Docker just to work on the UI or gateway. The two supported JDBC URLs are selected explicitly; unsupported database URLs fail startup instead of silently creating a different schema.

PostgreSQL is free and open source to run yourself. A managed PostgreSQL service can reduce backup and patch work, but the provider and price are deployment choices; the application uses a standard JDBC URL and credentials.

## Provider independence

The supplied Alpha/Beta mocks and alias configuration are the default path and are sufficient for the required demo. OpenRouter is optional: when `OPENROUTER_API_KEY` is set, it is appended after the configured mock providers. `OPENROUTER_PREFER_AS_PRIMARY=true` explicitly opts into changing the primary route. If every configured provider is unavailable, the completion request returns an upstream error while health, usage, logs, and the console remain available.

Prism uses the same OpenAI-compatible provider adapter for OpenRouter when enabled. The integration test covers prompt forwarding, provider selection, response parsing, price-table accounting, and streaming. A local key is optional; Alpha/Beta remain the configured demo providers.

## Request concurrency and scaling limits

The RPM limiter uses a synchronized per-key sliding window and is race-safe in one gateway instance. Budget checks and completion accounting are serialized per key through the full response, including streaming, so a second request cannot pass the budget check before the first request's actual provider usage is recorded. This is intentionally single-instance coordination. Before running multiple gateway replicas, move both admission controls to shared atomic storage such as PostgreSQL or Redis.

The local cache uses normalized token overlap, not embedding vectors. Admin APIs are open for the capstone demo. These are deliberate boundaries for the current implementation and must be addressed before treating it as a public, multi-tenant production service.
