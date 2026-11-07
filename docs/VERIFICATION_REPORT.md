# Prism verification report

Run date: 2026-11-08

Runtime: Java 17, Spring Boot 3.4.5, React/Vite, Supabase PostgreSQL, local Alpha/Beta mock providers.

## Problem statement checks

| Check | Result |
|---|---|
| Backend automated suite | 49 tests, 0 failures, 0 errors |
| Frontend production build | Passed |
| Seed data | 4 tenant keys, 2 mock providers, 3 aliases, 20 routing cases |
| PostgreSQL schema | Flyway V1–V3 applied to Supabase; V3 creates persisted dashboard accounts |
| Local data migration | 4 tenant keys, 197 request logs, 4 monthly usage rows, 55 cache entries copied before live checks |
| Provided gateway smoke test | 18 passed, 0 warnings, 0 failed (cache-enabled search key) |
| Load test | 10 accepted, 20 rate-limited; no over-admission |
| Routing evaluation | 20/20 correct (100%) |
| Alpha outage drill | HTTP 200 from `beta/beta-small`, `x-prism-fallback: true`; Alpha recovered and served again |
| JWT auth against Supabase | Sign-up, BCrypt-backed sign-in, and protected admin API passed; temporary test account removed |
| Unauthenticated admin API | HTTP 401 |
| Browser admin CORS preflight | HTTP 200 without a JWT; configured origin is allowed |
| Provider degradation | Rolling error-rate and p95 latency thresholds reorder degraded providers behind healthy fallbacks; reported to the ops dashboard |
| Budget enforcement | Backend tests pass for concurrent admission and budget rejection |

The live PostgreSQL smoke test found an ambiguous column in the monthly usage upsert that SQLite tests could not detect. The update now qualifies the target table columns and passed the PostgreSQL smoke, load, and failover checks.

## Load test accounting

The supplied burst used the free-tier key at 10 requests per minute:

- Accepted requests: 10; rate-limit rejections: 20.
- Prompt tokens: 120; completion tokens: 262.
- Sum of cost headers: $0.000175.
- Client-observed latency: 1,670 ms average; 2,641 ms p95.

## Limits

The verification used one gateway instance and the supplied mock providers. RPM state and rolling provider health samples are in memory and reset on restart; use a shared store and external health probes for multi-instance production deployments. The semantic cache uses normalized token overlap rather than embeddings. JWT signing requires a private `JWT_SECRET` in enterprise deployments; dashboard tokens expire after 24 hours by default. Public sign-up currently grants workspace administrator access, so keep the registration URL private to trusted users.

The default demo follows the supplied Alpha/Beta configuration. OpenRouter remains optional and is appended after those providers when configured; `OPENROUTER_PREFER_AS_PRIMARY=true` deliberately changes that order.
