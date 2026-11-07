# Prism verification report

Run date: 2026-11-07  
Runtime: Java 17, Spring Boot 3.4.5, React/Vite production build, local Alpha/Beta mock providers.

## Results

| Check | Result |
|---|---|
| Spring Boot TDD suite | 14 tests, 0 failures, 0 errors |
| Frontend production build | Passed |
| Seed data validation | Passed: 4 prices, 2 providers, 3 aliases, 4 tenants, 14 sample requests, 20 routing cases |
| Provided smoke test | 18 passed, 0 warnings, 0 failed |
| Concurrent load test | 10 accepted, 20 rate-limited; no over-admission |
| Routing evaluation | 20/20 correct (100%) |
| Live Alpha outage drill | HTTP 200 from beta/beta-small; x-prism-fallback=true; Alpha restored afterwards |

## Load test accounting

The supplied burst used the free-tier key at 10 requests per minute:

- Client accepted requests: 10
- Rate limit rejections: 20
- Client prompt tokens: 120
- Client completion tokens: 278
- Sum of rounded cost headers: $0.000185
- Usage API cost: $0.0001848
- Usage API tokens: 120 prompt and 278 completion
- Client-observed latency: 140 ms average, 223 ms p95

The usage API totals reconcile with the load test. The cost-header sum is rounded by the supplied script to six decimal places.

## Smoke and routing details

The smoke test verified JSON completion and response headers, streaming SSE and [DONE], invalid key rejection, unknown model rejection, exact and paraphrased semantic cache hits, and an unrelated cache miss. The 20-case routing eval includes short-but-hard and long-but-trivial prompts; all 20 matched their labels.

The Spring tests include concurrent rate admission, budget rejection, cache paraphrase thresholds and the two-factor near miss, provider-reported token pricing, retry/failover, streaming token forwarding, stream usage accounting, and API error/header contracts.

## Limits

These results use one local gateway instance and the supplied mock providers. The gateway's rate limit is single-instance, the operations API has no admin authentication, and the local semantic matcher is normalized token overlap rather than a neural embedding model. The Docker deployment guide documents the demo deployment boundaries.
