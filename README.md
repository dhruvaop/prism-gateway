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

The user chat app is available at http://localhost:5174/chat.html (`cd frontend; npm run dev:chat`). In the operations console, open **Access** to create plans, teams, and chat users. A user receives a private virtual key from the selected team's plan; the key is shown only when created. Chat users sign in at the chat app with the username or email and password set by the administrator. Their key stays on the server.

### Demo keys

| Team | Key | Models | Requests/min | Monthly budget |
|---|---|---|---:|---:|
| search | prism-sk-search-1a2b3c | fast, auto | 60 | $50 |
| research | prism-sk-research-4d5e6f | fast, smart, auto | 300 | $500 |
| free-tier | prism-sk-free-7g8h9i | fast, auto | 10 | $5 |
| budget-demo | prism-sk-budget-demo-0j1k2l | fast, auto | 60 | $0.00001 |

Keys, aliases, prices, and provider endpoints load from the supplied data JSON files. Seed values are preserved for the demo scripts.

### Password reset

Both sign-in screens (`/` for the console and `/chat.html` for chat) have a **Forgot password?** flow. Chat users need an email address, which the **Access** page collects when creating or editing a user. Reset links are random, single-use, stored as SHA-256 hashes, and expire after 30 minutes.

To send real email, set `PRISM_MAIL_HOST`, `PRISM_MAIL_PORT`, `PRISM_MAIL_USERNAME`, `PRISM_MAIL_PASSWORD`, and `PRISM_MAIL_FROM` in `.env`. Gmail requires an app password. When SMTP is not configured, the local gateway returns a development reset link instead of pretending to send mail; the enterprise profile disables that fallback with `PRISM_MAIL_EXPOSE_RESET_LINK=false`.

### Google Gemini

Gemini is supported as an OpenAI-compatible upstream. Set `GEMINI_API_KEY` (from Google AI Studio; most keys start with `AIza`, and newer keys such as `AQ.…` also work), optionally `GEMINI_MODEL` (default `gemini-3.1-flash-lite`), and `GEMINI_PREFER_AS_PRIMARY=true` to route fast/smart and task traffic to Gemini first, with OpenRouter and then the mocks as fallbacks. Leave the key empty to keep Gemini out of the routing chain. If Google returns `API_KEY_INVALID`, confirm the key belongs to the Gemini API and that `GEMINI_MODEL` is available to that key.

## Request routing and failover

fast and smart map to their configured primary and ordered fallback models. Prism attempts the primary provider up to three times for retryable errors (including timeouts and 429/5xx responses), using exponential backoff from gateway_config.sample.json; then it advances to the next provider. Responses identify the serving provider and set x-prism-fallback when a fallback served the request. Provider credentials are held server-side.

For model: "auto", a deterministic task router runs before model resolution. It classifies the prompt into **web search**, **research**, **reasoning / chain of thought**, **code**, or **simple lookup** using weighted lexical signals (proofs, estimation, distributed-systems, citations, algorithms, freshness, extraction, lookups, and rewrites) instead of prompt length alone. The task keeps the existing fast/smart contract for allowlists and the 20-case eval: research, reasoning, and code use the smart tier; web search and simple lookups use the fast tier. The task, tier, confidence, and matched reason are recorded in the request log, returned in the `x-prism-task` response header, and shown in the Playground. The router reads the prompt, never the evaluation labels. On the 20-case eval it scores 20/20, while the best length-only baseline scores 15/20 (75%) at a 42-character threshold.

When OpenRouter is the primary provider, `auto` also picks a task-specific upstream model: web search/simple use `OPENROUTER_MODEL` (optionally `:online`), research uses `OPENROUTER_MODEL_RESEARCH`, reasoning uses `OPENROUTER_MODEL_REASONING`, and code uses `OPENROUTER_MODEL_CODE`. The task route is followed by the generic OpenRouter tier and then the Alpha/Beta mocks, so provider failover still applies. With the mocks as primary (the default) the alias order is unchanged.

### Per-key routing middleware

Every virtual key stores its own `routing_profile`: `auto` (gateway global preference), `openrouter` (Nemotron/task routes), `gemini`, `openai` (when `OPENAI_API_KEY` is configured), or `mock`. The key's profile is applied to both `auto` classification and explicit `fast`/`smart` aliases, so two keys on the same gateway can resolve to different providers at the same time. Fallbacks still run in this order: key profile provider → other configured live providers → local mocks. Admins can change a key's profile in **Virtual keys** or **Access**, or with `PATCH /admin/managed-keys/ref/{key_ref}` and `{"routing_profile":"gemini"}`. `GET /admin/routing/profiles` lists the profiles currently available. The seeded demo keys use OpenRouter, Gemini, and mock profiles to demonstrate the split.

Run the 20-case routing report while the gateway is running:

~~~powershell
python scripts/eval_spring_router.py --url http://localhost:8080
~~~

## Cache, metering, and logs

- Cache lookups are two-layer and isolated by virtual key and requested alias. L1 is a bounded in-process burst cache (500 entries, 15-minute TTL by default) that answers identical or close paraphrases without a database read. L2 is the durable Postgres semantic cache, using normalized word overlap plus a small synonym map and inspecting the newest 500 matching entries. The supplied paraphrase pairs pass the seeded thresholds; the password vs. two-factor near miss does not.
- Successful upstream responses are cached. Error responses are not. Cache hits have zero provider cost. A Postgres hit is promoted into L1, so the next repeat is served from memory.
- L2 is capped on every write: 1,000 global rows, 200 rows per key, 5,000 hit-history rows, 32 KB per response, and a 24-hour retention ceiling by default. Rows older than the effective per-key TTL are deleted when that key writes again.
- A scheduled purge runs every day at 17:00 Asia/Kolkata (`prism.cache.purge-cron`, `prism.cache.purge-zone`). It clears L1 and deletes all `cache_entries` and `cache_hits` rows. Administrators can run the same purge on demand with `POST /admin/cache/purge`.
- Provider-reported token usage drives the cost calculation against model_pricing.json. Client-declared usage is ignored.
- Monthly spend is updated atomically with a database upsert. Per-key admission is serialized through completion and stream usage accounting, so one gateway instance records each request's final cost before admitting the next request for that key.
- RPM admission uses a synchronized in-process sliding window. It is race-safe for one gateway instance; it is not distributed across instances.
- SLF4J logs request ID, team, model, status, provider, cache/fallback outcome, retries, token counts, cost, and latency. Raw prompts, virtual keys, and provider credentials are not included in application logs.

## API

POST /v1/chat/completions accepts OpenAI-shaped messages and fast, smart, auto, or a configured provider model. It supports streaming with stream: true; SSE chunks pass through as they arrive and terminate with data: [DONE]. Reasoning models are asked to exclude hidden reasoning from the response. If an upstream returns only reasoning deltas, or an empty non-streaming answer, the gateway treats it as a failed attempt and advances to the next provider instead of returning a blank answer.

Every completion returns the full header contract: x-prism-provider, x-prism-cache, x-prism-fallback, and x-prism-cost-usd, plus x-prism-task for auto requests. Streaming responses carry x-prism-cost-usd as 0.000000000 because headers are sent before the stream completes; the final token cost is recorded in usage and the request log.

Admin endpoints:

- GET /health
- GET /admin/overview
- GET /admin/usage (optional `?key=` server-side filter; responses return `key_ref` and `key_hint`, never full keys)
- GET /admin/logs?limit=100 (optional `?key=` server-side filter; rows return `key_ref` and `key_hint`, not `virtual_key`)
- GET /admin/cache/stats
- GET /admin/cache/entries?limit=200 and /admin/cache/hits?limit=200; DELETE /admin/cache/entries/{id}
- POST /admin/cache/purge (clear burst memory and empty both Postgres cache tables; runs daily at 17:00 Asia/Kolkata)
- GET /admin/keys (returns `key_ref` and `key_hint`, never full keys)
- GET /admin/providers/health
- GET /admin/router/eval
- POST /admin/playground/chat (admin JWT + `key_ref`; returns a privacy-scoped stream with `x-prism-model-hidden: true` and no provider/model headers)

Managed access endpoints (JWT admin session required):

- GET/POST/PATCH/DELETE /admin/plans
- GET/POST/PATCH/DELETE /admin/teams
- GET/POST/PATCH/DELETE /admin/users (DELETE disables the account and key)
- GET /admin/keys; POST /admin/managed-keys/ref/{key_ref}/enable, /disable, or /rotate

Creating a user mints a new `prism-sk-…` virtual key with `SecureRandom` and returns it once so the administrator can copy it. After that, admin reads return only `key_ref` and `key_hint`; the Playground and key-management actions use the opaque ref. Rotate returns the replacement key once, and disable blocks it immediately. Seeded demo keys are never regenerated, so the supplied demo scripts keep working.

Chat endpoints use a chat-session JWT from POST /app/login: GET /app/me, GET/POST /app/conversations, GET/PATCH/DELETE /app/conversations/{id}, POST /app/chat, and GET /app/usage. Public chat signup is POST /app/signup: it creates a self-serve account with a 5-request demo, then requires Pro (₹499/month) or Enterprise (₹1,999/month) through Razorpay. Conversation records are scoped to the signed-in user. API-only users cannot use the chat endpoints. Admin-provisioned and existing accounts are unlimited; only self-signups are metered. Billing endpoints are GET /app/billing/status, POST /app/billing/order, POST /app/billing/verify, and the signed Razorpay webhook POST /webhooks/razorpay. The Razorpay key secret stays server-side; only the public key id is returned to Checkout. Set `RAZORPAY_PAY_KEY_ID`, `RAZORPAY_PAY_KEY_SECRET`, `RAZORPAY_MERCHANT_ID`, and `RAZORPAY_WEBHOOK_SECRET` in the root `.env`, and point the Razorpay Dashboard webhook at `/webhooks/razorpay` with the same secret.

Plans configure monthly USD budget, RPM and TPM limits, monthly token cap, model allowlist, cache similarity/TTL, prompt injection and PII guardrails, and optional verification models. Chat supports a second-model answer check when the plan includes a checker. Cache expiry is enforced per key; similarity remains the documented local token-overlap matcher.

Input guardrails run before budget admission and before any upstream call. Each key inherits `guardrail_injection` and `guardrail_pii` from its plan; both support `off`, `flag` (log and continue), `block` (HTTP 400 `guardrail_blocked`), and `redact` (mask the matched text and continue). Every user turn in the replayed conversation is inspected, so an injection hidden in earlier history is caught too. Injection covers phrases such as "ignore all previous instructions", "reveal your system prompt", and "override the system prompt"; PII covers email, SSN, phone, payment-card numbers, and simple street addresses. When `flag` is set, the gateway writes a `guardrail_flag` warning with the key ref, team, and rule, but never logs the prompt text or the virtual key. Guardrail rejections are recorded as `rejected_guardrail` in the request log.

Model-identity questions such as "which model do you use", "who created you", or "tell me all model-related details" are answered from Prism's routing metadata, not from the model's trained self-description. On the public API, the gateway injects an authoritative fact sheet naming the exact selected model, its creator, family, variant/version, gateway, provider route, requested alias, tier, auto-router task, full fallback chain, whether a fallback was used, and pricing; the model is told to return all of those facts. Identity turns bypass the semantic cache, and the same metadata is returned as `x-prism-model`, `x-prism-model-creator`, and `x-prism-model-family` headers.

The chat surface (`/app/chat`) is privacy-scoped instead. It injects a confidentiality instruction rather than the fact sheet, bypasses the semantic cache, rebuilds streamed chunks with `model: "prism"`, redacts assistant text server-side before it is written or stored, and sets `x-prism-model-hidden: true` with no provider/model headers. Stored assistant turns are redacted again on read, so older conversations cannot leak metadata. The chat renderer also masks any remaining identifiers and always shows `Served by · model details hidden`. This keeps `/v1/chat/completions` transparent for API and audit consumers while chat users — including anyone inspecting DevTools Network — see no upstream model, creator, tier, or fallback details.

Set `PRISM_ROUTING_STRATEGY=failover` (default), `least_latency`, or `weighted` to choose the ordering among healthy providers. The rolling health monitor still moves degraded providers behind healthy ones.

Admin endpoints require a valid `ADMIN` JWT. Public admin signup is bootstrap-only: it is open only while the `auth_users` table has no administrator, then closes permanently. The admin UI hides the signup tab when `GET /auth/bootstrap` reports `signup_open: false`. Chat signups use the separate self-serve `/app/signup` path and never create an administrator.

## Build and deploy

For a free hosted deployment (Render + Cloudflare Pages + Supabase, or an
always-on Oracle Cloud Always Free VM), see `docs/FREE_DEPLOYMENT.md`. The root
`Dockerfile` is the portable gateway image for hosts such as Render and Google
Cloud Run; it includes the `data/` files the gateway reads at startup.

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

To use a real model locally, put `OPENROUTER_API_KEY=...` and `OPENROUTER_PREFER_AS_PRIMARY=true` in the repo-root `.env` and start the gateway with `powershell -ExecutionPolicy Bypass -File scripts\run-backend.ps1` (that script exports every `.env` value into the process). `OPENROUTER_MODEL` (default `nvidia/nemotron-3.5-lightning:free`) selects the upstream model for the fast tier and web-search/simple tasks; `OPENROUTER_MODEL_RESEARCH` (`nvidia/nemotron-3-ultra-550b-a55b:free`), `OPENROUTER_MODEL_REASONING` (`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`), and `OPENROUTER_MODEL_CODE` (`cohere/north-mini-code:free`) select the task-specific models used by `auto`. `OPENROUTER_SEARCH_ONLINE=true` appends `:online` to the search/simple model. `OPENROUTER_MAX_TOKENS` (default `4096`) caps completion tokens - reasoning models spend part of that budget on hidden reasoning, so a small cap can return an empty answer. Prism requests `reasoning.exclude` and fails over when an upstream produces no visible text, but keep the cap high enough for the selected model. When OpenRouter is unreachable or its free quota is exhausted, the gateway falls back to the mocks and the console tags the reply as `mock reply`. For Gemini-first routing, set `GEMINI_API_KEY=...`, `GEMINI_MODEL=gemini-3.1-flash-lite`, and `GEMINI_PREFER_AS_PRIMARY=true` instead; Gemini then goes first, with OpenRouter and the mocks behind it.

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
