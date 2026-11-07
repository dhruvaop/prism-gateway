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

OpenRouter is not a required runtime service. The gateway receives requests first, classifies `auto` prompts locally, then calls an OpenAI-compatible provider selected by its configured alias chain. Alpha/Beta mocks are free fallback providers for local verification. If a remote provider is unavailable, the gateway retries and moves through its configured fallback chain. If every configured provider is unavailable, the completion request returns a clear upstream error while health, usage, logs, and the console remain available.

The models-list endpoint check only verified OpenRouter connectivity and authorization; Prism does not currently include OpenRouter in its configured chain. To add it, implement/configure an adapter and a fallback chain, then test an actual completion and outage behavior before relying on it.

## Scaling limits to resolve before multiple replicas

PostgreSQL lets multiple gateway instances share durable records, but the current RPM limiter is in memory. Two replicas would each admit requests independently. Before enabling multiple gateway replicas, move request limits to Redis or a PostgreSQL-backed atomic limiter. Monthly totals use database upserts; final provider costs are unknown at admission time, so concurrent in-flight requests can exceed a remaining budget by their eventual cost. Strict financial caps require reserving a conservative amount before calling the provider and reconciling that reservation afterward.

The local cache uses normalized token overlap, not embedding vectors. Admin APIs are open for the capstone demo. These are deliberate boundaries for the current implementation and must be addressed before treating it as a public, multi-tenant production service.
