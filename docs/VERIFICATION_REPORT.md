# Prism verification report

Run date: 2026-11-07  
Runtime: Java 17, Spring Boot 3.4.5, React/Vite production build, local Alpha/Beta mock providers.

## Results

| Check | Result |
|---|---|
| Spring Boot TDD suite | 18 tests, 0 failures, 0 errors |
| Frontend production build | Passed |
| Seed data validation | Passed: 4 prices, 2 providers, 3 aliases, 4 tenants, 14 sample requests, 20 routing cases |
| Provided smoke test | 18 passed, 0 warnings, 0 failed |
| Concurrent load test | 10 accepted, 20 rate-limited; no over-admission |
| Routing evaluation | 20/20 correct (100%) |
| Live Alpha outage drill | HTTP 200 from beta/beta-small; x-prism-fallback=true; Alpha restored afterwards |
| OpenRouter new-key check | Models endpoint HTTP 200; free-model completion returned "4" with finish_reason=stop |

## Load test accounting

The supplied burst used the free-tier key at 10 requests per minute. The first run reported:

- Client accepted requests: 10
- Rate limit rejections: 20
- Client prompt tokens: 120
- Client completion tokens: 278
- Sum of rounded cost headers: $0.000185
- Usage API cost: $0.0001848
- Usage API tokens: 120 prompt and 278 completion
- Client-observed latency: 140 ms average, 223 ms p95

After rebuilding the PostgreSQL-capable backend, a second burst again admitted 10 and rejected 20. It measured 120 prompt tokens, 270 completion tokens, $0.000180 in summed cost headers, and 122 ms average / 171 ms p95. The usage API then showed the two runs together: 20 requests, 240 prompt tokens, 548 completion tokens, and $0.0003648. The cost-header sums are rounded by the supplied script to six decimal places.

## Smoke and routing details

The smoke test verified JSON completion and response headers, streaming SSE and [DONE], invalid key rejection, unknown model rejection, exact and paraphrased semantic cache hits, and an unrelated cache miss. The 20-case routing eval includes short-but-hard and long-but-trivial prompts; all 20 matched their labels.

The Spring tests include concurrent rate admission, budget rejection, cache paraphrase thresholds and the two-factor near miss, provider-reported token pricing, retry/failover, streaming token forwarding, stream usage accounting, API error/header contracts, and database dialect/migration configuration.

## Limits

These results use one local gateway instance and the supplied mock providers. The gateway's rate limit is single-instance, the operations API has no admin authentication, and the local semantic matcher is normalized token overlap rather than a neural embedding model. The Docker deployment guide documents the demo deployment boundaries.

The OpenRouter completion check used a catalog-verified zero-price model and was a direct API check; it did not route through Prism. PostgreSQL schema/profile configuration is covered by database-dialect and migration-resource tests. A live PostgreSQL container check could not run because the local Docker daemon is unavailable.
