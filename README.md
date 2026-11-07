# Prism — LLM Gateway & Control Plane

Prism is an OpenAI-compatible LLM gateway with prompt classification, provider failover, token streaming, per-key usage and budgets, a tenant-scoped semantic response cache, and a React operations console.

## Stack

- **Backend:** Java 17, Spring Boot 3.4, Spring MVC, PostgreSQL for shared deployments, SQLite for quick local development, SLF4J.
- **Frontend:** React 19, Vite, Tailwind CSS, Recharts, Lucide.
- **Local providers:** the supplied mock Alpha and Beta providers. They are free, offline, and support live failure injection.
- **Persistence:** Flyway-managed PostgreSQL schema for deployment; file-based SQLite for the no-setup local run. Seeded virtual keys are stored in the database and loaded from the supplied seed file.
- **Deployment database:** PostgreSQL 17. Compose keeps PostgreSQL private on its internal network; Flyway creates and updates the schema at gateway startup.

The implementation uses package com.dhruva.airtribe.prism. The original Python files and the provided Python verification utilities remain in the repository, but the Prism application runs on the Spring Boot backend.

For step-by-step deployment instructions in plain language, see docs/DEPLOYMENT_GUIDE.md. The Docker Compose deployment uses PostgreSQL; the quick local Maven run continues to use SQLite.

See docs/ARCHITECTURE_DECISIONS.md for the first-principles database and provider-independence decisions.

## Run locally

Requirements: Java 17+, Maven 3.9+, Node.js 20.19+ (or 22.12+), and Python 3.9+ for the supplied mock servers and verification scripts.

Start Alpha and Beta in separate PowerShell terminals from the project root:

~~~powershell
python scripts/mock_provider.py --port 9001 --name alpha
python scripts/mock_provider.py --port 9002 --name beta
~~~

Start Spring Boot in another terminal:

~~~powershell
cd backend
mvn spring-boot:run
~~~

Start the React console in another terminal:

~~~powershell
cd frontend
npm install
npm run dev
~~~

Open http://localhost:5173. The gateway listens on http://localhost:8080. VITE_API_URL can point the console at a different gateway URL. PRISM_DATA_DIR, PRISM_DB, and PORT configure the seed directory, local SQLite file, and backend port.

### Demo keys

| Team | Key | Models | Requests/min | Monthly budget |
|---|---|---|---:|---:|
| search | prism-sk-search-1a2b3c | fast | 60 | $50 |
| research | prism-sk-research-4d5e6f | fast, smart, auto | 300 | $500 |
| free-tier | prism-sk-free-7g8h9i | fast | 10 | $5 |
| budget-demo | prism-sk-budget-demo-0j1k2l | fast | 60 | $0.00001 |

Keys, aliases, prices, and provider endpoints load from the supplied data JSON files. Seed values are preserved for the demo scripts.

## Request routing and failover

fast and smart map to their configured primary and ordered fallback models. Prism attempts the primary provider up to three times for retryable errors (including timeouts and 429/5xx responses), using exponential backoff from gateway_config.sample.json; then it advances to the next provider. Responses identify the serving provider and set x-prism-fallback when a fallback served the request. Provider credentials are held server-side.

For model: "auto", a deterministic classifier runs before model resolution. It uses reasoning, proof, estimation, distributed-systems, extraction, lookup, and rewrite signals instead of prompt length alone. It records the tier and the matched reason in request logs. The classifier reads the prompt, never the evaluation labels.

Run the 20-case routing report while the gateway is running:

~~~powershell
python scripts/eval_spring_router.py --url http://localhost:8080
~~~

## Cache, metering, and logs

- Cache lookups are isolated by virtual key and requested alias, use normalized word overlap plus a small synonym map, and inspect the newest 500 matching entries. The supplied paraphrase pairs pass the seeded thresholds; the password vs. two-factor near miss does not.
- Successful upstream responses are cached. Error responses are not. Cache hits have zero provider cost. The cache is stored in the configured database and currently has no TTL or physical eviction policy.
- Provider-reported token usage drives the cost calculation against model_pricing.json. Client-declared usage is ignored.
- Monthly spend is updated atomically with a database upsert. Per-key admission is serialized through completion and stream usage accounting, so one gateway instance records each request's final cost before admitting the next request for that key.
- RPM admission uses a synchronized in-process sliding window. It is race-safe for one gateway instance; it is not distributed across instances.
- SLF4J logs request ID, team, model, status, provider, cache/fallback outcome, retries, token counts, cost, and latency. Raw prompts, virtual keys, and provider credentials are not included in application logs.

## API

POST /v1/chat/completions accepts OpenAI-shaped messages and fast, smart, auto, or a configured provider model. It supports streaming with stream: true; SSE chunks pass through as they arrive and terminate with data: [DONE].

Every completion returns x-prism-provider, x-prism-cache, and x-prism-fallback. Non-streaming completions also return x-prism-cost-usd; streaming usage and cost are recorded after the stream completes.

Admin endpoints:

- GET /health
- GET /admin/overview
- GET /admin/usage?key=<virtual-key>
- GET /admin/logs?key=<virtual-key>&limit=100
- GET /admin/cache/stats
- GET /admin/keys
- GET /admin/providers/health
- GET /admin/router/eval

Admin endpoints are open for local demo use and should be protected before deployment.

## Build and deploy

Build the Java gateway and React console:

~~~powershell
cd backend
mvn clean package
cd ..\frontend
npm ci
npm run build
~~~

For the provided single-server deployment, install Docker and Docker Compose, set `DATABASE_PASSWORD` in the environment (or copy `.env.example` to `.env` and change it), then run `docker compose up --build -d` from the repository root. The gateway automatically applies PostgreSQL migrations. The console and API are served through `http://<host>:8081`; for example, `GET /health` and `POST /v1/chat/completions`. The PostgreSQL JDBC URL inside Compose is `jdbc:postgresql://db:5432/prism`; keep that database endpoint private. For managed PostgreSQL such as Supabase, use `SPRING_PROFILES_ACTIVE=enterprise`, set `DATABASE_URL=jdbc:postgresql://<host>:5432/<database>?sslmode=require`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. Supabase project URLs and API keys are not database login credentials; copy the connection details from the Supabase Dashboard's Connect dialog.

The core demo uses the provided Alpha/Beta mock providers and does not need an external AI key. If `OPENROUTER_API_KEY` is configured, OpenRouter is added as the last fallback while the provided alias order remains intact. Set `OPENROUTER_PREFER_AS_PRIMARY=true` only when intentionally choosing OpenRouter as the primary provider.

To copy an existing local SQLite database into a fresh PostgreSQL/Supabase database, configure the root `.env` with the destination `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`, start the gateway once so Flyway creates the schema, install `requirements-migrate.txt`, then run `python scripts/migrate_sqlite_to_postgres.py`. The importer copies tenant keys, request logs, monthly usage, and cache entries; it refuses to merge into a destination that already has runtime history. Provider aliases, pricing, and routing evaluation remain the supplied files under `data/`, as in the capstone design.

## TDD and verification

See docs/VERIFICATION_REPORT.md for the latest local smoke, load, routing, and failover results.

Run the Spring Boot tests:

~~~powershell
cd backend
mvn test
~~~

The tests cover authentication, allowlists, unknown and malformed requests, persistent seeded keys, concurrent budget admission, budget rejection, routing evaluation, the supplied semantic paraphrases and near miss, concurrent RPM admission, retry/failover, provider-based cost calculation, streamed token forwarding, SSE termination, and usage recording.

With both mock providers and the gateway running, run:

~~~powershell
python scripts/smoke_test.py --url http://localhost:8080 --key prism-sk-search-1a2b3c --model fast
python scripts/load_test.py --url http://localhost:8080 --key prism-sk-free-7g8h9i --model fast --requests 30 --concurrency 10 --rpm-limit 10
python scripts/eval_spring_router.py --url http://localhost:8080
~~~

Failure injection is provided by the mock servers. For example, take Alpha down and send a completion to observe Beta serving it:

~~~powershell
Invoke-RestMethod -Method Post -Uri http://localhost:9001/admin/config -ContentType 'application/json' -Body '{"mode":"down"}'
~~~

Restore Alpha with {"mode":"ok","fail_rate":0}.
