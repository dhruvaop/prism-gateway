# Prism API Contract

This document describes the expected product and API behavior without requiring any specific programming language or framework.

You may change admin route names, request shapes, or response shapes if you document the changes clearly. The data plane is the exception: `POST /v1/chat/completions` must stay OpenAI-compatible and must return the `x-prism-*` headers, because the provided smoke test and load test depend on them.

## Personas

- `application caller`: A team's service calling the gateway with a virtual key. Never sees provider credentials.
- `platform admin`: Operates the gateway — tenants, usage, logs, provider health.

For Must Have, admin APIs can be protected by a simple demo token. Key-management APIs are Good To Have.

## Core Resources

- Virtual key (tenant)
- Provider
- Model alias
- Request log entry
- Usage record
- Cache entry

Good To Have resources:

- Provider health snapshot
- Guardrail configuration

## Authentication

- Data plane: `Authorization: Bearer <virtual-key>` using keys from `data/seed_keys.json`.
- Admin plane: `POST /auth/signup` and `POST /auth/signin` accept JSON account credentials and return `{ "token": "<JWT>", "user": { ... } }`. Send the token as `Authorization: Bearer <JWT>` to `/admin/**`; tokens are signed with `JWT_SECRET` and expire after 24 hours by default. Passwords are BCrypt hashes in the `auth_users` database table. Public admin signup is bootstrap-only: it is allowed only while no administrator exists. After that, new administrators require an existing ADMIN token. Duplicate email returns 409. Admin routes without a valid token return 401. Chat users receive a separate `CHAT_USER` token from `/app/login`; that token cannot access `/admin/**`.

## Core API Flows

### 1. Chat Completion (Non-Streaming)

`POST /v1/chat/completions`

Expected behavior:

- Authenticate the virtual key.
- Enforce model allowlist, rate limit, and budget — in a documented order.
- Resolve the alias (`fast`, `smart`) or model name to a provider model. For `auto`, classify the prompt's difficulty first, pick a tier, and record the decision and its reason in the request log.
- Check the semantic cache; on miss, forward upstream with a timeout and retries.
- Compute cost from provider-reported usage and the price table.
- Log the request and return an OpenAI-shaped response.

Example request:

```json
{
  "model": "fast",
  "messages": [{"role": "user", "content": "What is a message queue?"}]
}
```

Example response body (OpenAI-compatible):

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "model": "alpha-small",
  "choices": [{"index": 0, "message": {"role": "assistant", "content": "..."}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 5, "completion_tokens": 17, "total_tokens": 22}
}
```

Required response headers:

| Header | Value |
|---|---|
| `x-prism-provider` | upstream provider/model that served it, e.g. `alpha/alpha-small` |
| `x-prism-model` | resolved upstream model id, e.g. `gemini-3.1-flash-lite` |
| `x-prism-model-creator` | model creator, e.g. `Google` |
| `x-prism-model-family` | model family, e.g. `Gemini` |
| `x-prism-tier` | resolved routing tier, `fast` or `smart` |
| `x-prism-cache` | `hit` or `miss` |
| `x-prism-fallback` | `true` only when a fallback provider served it |
| `x-prism-cost-usd` | computed cost, e.g. `0.000014` (use `0` for cache hits) |

### 2. Chat Completion (Streaming)

Same endpoint with `"stream": true`.

Expected behavior:

- Respond with `Content-Type: text/event-stream`.
- Forward upstream chunks as they arrive (OpenAI chunk objects in `data:` lines) — no buffering the full completion.
- Terminate with `data: [DONE]`.
- Record usage and cost for streamed requests too (the mock providers include `usage` in the final chunk).
- Headers are sent before the body, so the cost is not known yet: `x-prism-cost-usd` is not required on streaming responses. `x-prism-provider`, `x-prism-cache`, and `x-prism-fallback` are still required up front, and the stream's final cost must be recorded in the request log and usage API.

### 3. Error Responses

Every rejection returns an OpenAI-style error body with a distinct, documented reason:

```json
{
  "error": {
    "message": "Monthly budget of $5.00 exhausted for this key",
    "type": "budget_exceeded",
    "code": "budget_exceeded"
  }
}
```

Suggested mapping:

| Case | Status | `type` |
|---|---|---|
| Missing/unknown virtual key | 401 | `authentication_error` |
| Model not on the key's allowlist | 403 | `model_not_allowed` |
| Rate limit exceeded | 429 | `rate_limit_exceeded` |
| Monthly budget exhausted | 429 or 402 | `budget_exceeded` |
| Unknown model or alias | 404 | `not_found_error` |
| All providers failed | 502 | `upstream_error` |

You may choose different status codes if documented, but each case must be distinguishable from the body.

### 4. Usage Summary

`GET /admin/usage?key=...&from=...&to=...`

Expected behavior:

- Return spend and token totals for a key over a window.

Example response:

```json
{
  "key": "prism-sk-search-1a2b3c",
  "from": "2026-07-01",
  "to": "2026-07-31",
  "requests": 412,
  "prompt_tokens": 18734,
  "completion_tokens": 9211,
  "cost_usd": 0.0141,
  "cache_hits": 96
}
```

The totals here must reconcile with the sum of `x-prism-cost-usd` headers — the load test prints client-side totals to compare against this API.

### 5. Request Logs

`GET /admin/logs?key=...&limit=...`

Expected behavior:

- Return recent request log entries, newest first, filterable by key.
- Each entry carries the fields listed in `docs/DATA_MODEL.md` (Request Log Entry).

### 6. Cache Stats

`GET /admin/cache/stats`

Expected behavior:

- Report hits, misses, and hit rate — overall or per key.
- Report both layers: `memory` (entries, max entries, TTL, hits, misses, evictions, expirations, last purge) and `limits` (Postgres row/byte caps and retention).
- Report the scheduled purge in `schedule` (`cron`, `zone`, and a human-readable label). The default is daily at 17:00 Asia/Kolkata.

`POST /admin/cache/purge`

Expected behavior:

- Clear the burst memory layer and delete every row from `cache_entries` and `cache_hits`.
- Return `deleted_entries`, `deleted_hits`, `memory.removed`, and `purged_at`. The same purge runs automatically on the configured cron.

### 7. Provider Health (Good To Have)

`GET /admin/providers/health`

Expected behavior:

- Report per-provider status derived from recent traffic: error rate, latency, and whether the gateway currently considers it healthy.

## Managed users and chat

This implementation also supports per-user virtual keys, team plans, a separate chat app, and user-scoped conversation history. Admin operations require an `ADMIN` JWT.

- `GET/POST/PATCH/DELETE /admin/plans`: manage model allowlists, budgets, RPM/TPM, monthly token caps, cache thresholds and TTL, guardrails, and checker models. Plan changes update member keys.
- `GET/POST/PATCH/DELETE /admin/teams`: assign teams to plans; changing a team's plan refreshes member key limits.
- `GET/POST/PATCH/DELETE /admin/users`: create user credentials and individual keys, update access, or disable accounts.
- `GET /admin/keys`; `POST /admin/managed-keys/ref/{key_ref}/enable`, `/disable`, and `/rotate`; `PATCH /admin/managed-keys/ref/{key_ref}` with `{"routing_profile":"openrouter"}` to change a key's provider middleware. `GET /admin/routing/profiles` lists the profiles available with the current provider credentials (`auto`, `openrouter`, `gemini`, `openai`, `mock`). Seeded demo keys cannot be rotated or disabled, but their routing profile can be reassigned. Admin reads return `key_ref` and `key_hint` instead of the virtual key: `/admin/overview`, `/admin/usage`, `/admin/logs`, `/admin/cache/entries`, `/admin/cache/hits`, `/admin/keys`, and `/admin/users`. Creating a user or rotating a key returns the new key once so the administrator can copy it.
- `POST /admin/playground/chat` is the admin Playground transport. It requires an ADMIN JWT and a `key_ref`, resolves the virtual key server-side, and streams with `x-prism-model-hidden: true`. It returns only `x-prism-cache`, `x-prism-fallback`, and `x-prism-request-id` routing headers; SSE chunks carry `"model": "prism"` and no provider, model, task, or cost details. The public `/v1/chat/completions` surface remains transparent for API and audit consumers.
- `POST /app/signup` creates a self-serve chat account with a 5-request demo. `GET /app/billing/status`, `POST /app/billing/order`, and `POST /app/billing/verify` drive Razorpay Checkout; `POST /webhooks/razorpay` is the signed server-to-server webhook. The Razorpay key secret is never returned to the browser or written to logs; only the public key id is exposed.
- `POST /app/login` accepts `username` as either the account username or email address (case-insensitive) and returns a chat JWT. Use it with `/app/me`, `/app/usage`, `/app/conversations`, and `/app/chat`. Chat users' virtual keys stay on the server.
- `POST /app/chat` streams a completion and stores the conversation. Set `verify: true` to request review by a checker model on the user's plan. This surface is privacy-scoped: it returns only `x-prism-conversation-id` and `x-prism-model-hidden: true` as routing headers, every SSE chunk carries `"model": "prism"`, and assistant text is redacted server-side before streaming and storage. The public `/v1/chat/completions` surface remains transparent and still returns the full `x-prism-model*` headers and fact sheet.
- `/app` access modes are `chat`, `api`, and `both`. Chat-only keys are rejected by the public `/v1` API.
- `GET /admin/cache/entries`, `GET /admin/cache/hits`, and `DELETE /admin/cache/entries/{id}` inspect and manage cache records.
