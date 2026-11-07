# Prism verification report

Run date: 2026-11-08
Runtime: Java 17, Spring Boot 3.4.5, React/Vite, local Alpha/Beta mock providers.

## Problem statement checks

| Check | Result |
|---|---|
| Backend automated suite | 35 tests, 0 failures, 0 errors |
| Frontend production build | Passed |
| Seed data | 4 tenant keys, 2 mock providers, 3 aliases, 20 routing cases |
| Persistent virtual keys | Seeded into SQLite at startup; PostgreSQL migration V2 adds the same tenant table |
| Provided smoke test | 18 passed, 0 warnings, 0 failed |
| Load test | 10 accepted, 20 rate-limited; no over-admission |
| Routing evaluation | 20/20 correct (100%) |
| Alpha outage drill | HTTP 200 from `beta/beta-small`; `x-prism-fallback: true`; Alpha restored |
| Budget concurrency | Test confirms the next same-key request waits for final usage accounting before budget admission |
| Fresh budget demo | First request returned 200; second returned 429 `budget_exceeded` |
| OpenRouter | Optional adapter test passed with an in-process fake; provided Alpha/Beta alias order remains the default |

## Load test accounting

The supplied burst used the free-tier key at 10 requests per minute:

- Accepted requests: 10; rate-limit rejections: 20.
- Prompt tokens: 120; completion tokens: 259.
- Sum of cost headers: $0.000173.
- Client-observed latency: 146 ms average; 226 ms p95.
- The usage API showed 30 requests after the run because the persistent local database already contained 20 earlier requests for that key. Its total was 360 prompt tokens, 807 completion tokens, and $0.0005382; the increment for this run reconciles with the load-test output.

## Smoke and routing details

The provided smoke test verified non-streaming response headers and usage, token-by-token SSE termination with `[DONE]`, invalid-key rejection, unknown-model rejection, exact and paraphrased semantic cache hits, and an unrelated cache miss. The routing eval includes short-but-hard and long-but-trivial cases; all 20 matched their labels.

The Spring tests cover authentication, allowlists, cost calculations from provider usage, seeded-key persistence, concurrent RPM and budget admission, retries and failover, semantic cache thresholds and isolation, OpenRouter request/stream handling, and PostgreSQL migration resources.

## Limits

The verification used a single gateway instance and the supplied mock providers. RPM and budget admission coordination are process-local; multiple replicas need shared atomic admission control. Admin endpoints are open for the capstone demo. The semantic cache uses documented normalized token overlap rather than embeddings. A live PostgreSQL container check was unavailable because the local Docker daemon was not running; PostgreSQL migrations are covered by resource tests.

The default demo follows the supplied Alpha/Beta configuration. OpenRouter is optional and is appended after those providers when configured; `OPENROUTER_PREFER_AS_PRIMARY=true` deliberately changes that default.
