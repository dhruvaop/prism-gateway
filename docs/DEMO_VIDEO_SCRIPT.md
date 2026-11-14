# Prism — Explainer Video Script

**Aligned to:** Airtribe AI-first Software Engineering Program case study, *Prism: LLM Gateway and Semantic Cache*.

The case study's deliverable asks for an explainer video demonstrating:

> streaming, smart routing, budget rejection, rate limiting, semantic cache hit on a paraphrased prompt, and live provider failover.

This script covers those six required behaviors in that order, plus the objective from the brief: one OpenAI-compatible API, multiple providers, per-tenant metering, budgets, request logs, and a simple ops console.

---

## 1. Deliverable coverage

| Case-study requirement | Scene | Evidence on screen |
|---|---|---|
| Unified OpenAI-compatible API | 2, 4 | `/v1/chat/completions`, Playground, header contract |
| Streaming token by token, `data: [DONE]` | 4 | Tokens render live; terminal shows SSE termination |
| Smart routing with `model: "auto"` | 5 | Short-but-hard → smart; long-but-trivial → fast |
| Beats length-only baseline | 5 | Routing eval `20/20` vs baseline `15/20` |
| Budget rejection | 6 | Second request returns `budget_exceeded` / `rejected_budget` |
| Rate limiting without over-admission | 7 | Load test: 10 accepted, 20 × 429 |
| Semantic cache on a paraphrase | 8 | Paraphrase hit, near-miss miss, other key miss |
| Live provider failover | 9 | Alpha down → Beta serves, `x-prism-fallback: true` |
| Metering, request logs, usage | 10 | Request log columns, spend, usage API |
| Ops console | 3, 10 | Usage by key, recent requests, cache hit rate |
| Verification | 11 | Smoke test, load test, routing eval results |
| README / limitations | 12 | End-card references |

**Target runtime:** 6 min 40 s (record 7–8 minutes and trim pauses).

---

## 2. Video specifications

| Item | Recommendation |
|---|---|
| Resolution | 1920×1080, 30 fps |
| Browser zoom | 110% |
| Cursor | Visible, with click highlight if available |
| Audio | One presenter, close microphone, no music under narration |
| Pace | Calm; pause 1 second after each result appears |
| Screen | Console full screen for scenes 3–6, 8, 10; terminal full screen for 5, 7, 9; split screen only when comparing |
| Captions | Add the callout text shown in each scene |
| Do not show | `.env`, terminal history with secrets, full virtual keys, JWTs, database URLs, passwords |

---

## 3. Pre-flight checklist

### 3.1 Start the stack

Use four terminals from the repository root.

**Terminal A — Alpha mock provider**

```powershell
python scripts/mock_provider.py --port 9001 --name alpha
```

**Terminal B — Beta mock provider**

```powershell
python scripts/mock_provider.py --port 9002 --name beta
```

**Terminal C — Spring Boot gateway**

```powershell
cd backend
mvn spring-boot:run
```

**Terminal D — React console**

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173` and sign in as the workspace administrator.

### 3.2 Prepare the demo state

1. **Virtual keys** → set the `research` key routing to `mock`. This makes routing and failover deterministic without any external API key.
2. **Semantic cache** → **Purge now**. Start with an empty cache.
3. **Playground** → select `budget-demo` → click **Reset usage**. It can accept exactly one request again.
4. Confirm **Overview → Routing health** shows `alpha` and `beta` as **Healthy**.
5. Open a second browser tab at `http://localhost:5174/chat.html` only if you are recording the optional chat segment.
6. Close notifications, bookmarks, and unrelated tabs. Clear the terminal so no secrets are visible.

### 3.3 Keep these prompts on the clipboard

| Use | Prompt |
|---|---|
| Streaming | `Explain how token streaming works in one short paragraph.` |
| Short-but-hard | `Prove that the square root of 2 is irrational.` |
| Long-but-trivial | The log excerpt from `data/routing_eval.jsonl` case `route_007`, ending with `What is the timestamp of the first ERROR line?` |
| Cache A | `How do I reset my password on the dashboard?` |
| Cache B | `What are the steps to reset my dashboard password?` |
| Cache near-miss | `How do I reset my two-factor authentication on the dashboard?` |
| Budget | `Say hello in one sentence.` |
| Failover | `Say hello in one sentence.` |

---

## 4. One-page shot list

| Time | Scene | Screen |
|---|---|---|
| 0:00–0:20 | Cold open | Title card over the console |
| 0:20–0:45 | Sign in | Console login |
| 0:45–1:20 | Control plane | Overview |
| 1:20–2:10 | Streaming | Playground + terminal SSE |
| 2:10–3:05 | Smart routing | Playground + routing eval |
| 3:05–3:40 | Budget rejection | Playground + Requests |
| 3:40–4:15 | Rate limiting | Terminal load test |
| 4:15–5:00 | Semantic cache | Semantic cache + Playground |
| 5:00–5:35 | Provider failover | Playground + terminal injection |
| 5:35–6:05 | Metering and logs | Requests + Overview |
| 6:05–6:25 | Verification recap | Verification report / terminal |
| 6:25–6:40 | Close | End card |

---

## 5. Full script

### Scene 1 — Cold open (0:00–0:20)

**On screen:** Console Overview, blurred slightly, with the title lower-third.

**Callout:** `Prism — LLM Gateway & Semantic Cache`

**Say:**

> Every AI product runs on an invisible layer between the application and the model providers. It handles keys, budgets, failover, caching, and cost accounting. Prism is that layer. It is an OpenAI-compatible LLM gateway with machine judgment in the request path: it judges how hard each prompt is, routes it to the right model tier, and matches meaning — not just strings — to serve repeated questions from cache. In the next seven minutes, I'll demonstrate the full workflow from the case study.

---

### Scene 2 — Sign in and set the frame (0:20–0:45)

**Do:**

1. Show the Prism sign-in screen.
2. Sign in with the workspace admin account.
3. Land on **Overview**.

**Callout:** `Admin plane · JWT session · provider keys stay server-side`

**Say:**

> This is the Prism operations console. I'll sign in as the workspace administrator. The browser holds a short-lived admin JWT. Provider credentials never leave the gateway, and the console never receives them.

---

### Scene 3 — Overview: the control plane (0:45–1:20)

**Do:**

1. Point to **Total requests**, **Total spend**, **Cache hit rate**, **Active keys**.
2. Point to **Routing health** and the provider rows.
3. Point to **Usage by virtual key**.
4. Point to **Recent requests**.

**Callout:** `One screen: traffic · spend · cache · provider health`

**Say:**

> The Overview is the control plane. Total requests, total spend, cache hit rate, and active keys come from the same database that meters every request. Routing health shows each provider. Usage by key shows monthly spend against each team's budget. Recent requests gives me the latest gateway activity. The data plane does the work; this console is how an operator sees it.

---

### Scene 4 — Streaming pass-through (1:20–2:10)

**Do:**

1. Open **Playground**.
2. Select the `research` key. Its cache is disabled, so this scene shows the provider path.
3. Select model `fast`.
4. Paste the streaming prompt and click **Run request**.
5. Let the answer stream in visibly. Do not cut away until it finishes.
6. Point to the response metadata and the completed cost line.

**Optional terminal cutaway:**

```powershell
$body = '{"model":"fast","stream":true,"messages":[{"role":"user","content":"Explain how token streaming works in one short paragraph."}]}'
curl.exe -N http://localhost:8080/v1/chat/completions `
  -H "Authorization: Bearer prism-sk-research-4d5e6f" `
  -H "Content-Type: application/json" `
  -d $body
```

Show the chunks arriving and the final `data: [DONE]`.

**Callout:** `stream: true → chunked SSE → data: [DONE] → usage still recorded`

**Say:**

> Streaming is the default path for chat. With stream set to true, tokens flow from the provider through the gateway to the browser as they arrive. There is no buffering of the full answer. The stream terminates with data: [DONE]. Headers are sent before the answer finishes, so the cost header is zero during the stream — but the gateway still records provider-reported usage and the final cost when the last chunk arrives. That is the zero-buffer pass-through and trailing reconciliation required by the case study.

---

### Scene 5 — Smart routing with `auto` (2:10–3:05)

**Do:**

1. Stay in **Playground**. Keep the `research` key.
2. Select model `auto`.
3. Paste the short-but-hard prompt: `Prove that the square root of 2 is irrational.`
4. Run it. Point to `model:` in the response metadata — it should resolve to the `smart` model (`alpha-large`).
5. Replace the prompt with the long-but-trivial log excerpt from `route_007`.
6. Run it. Point to `model:` — it should resolve to the `fast` model (`alpha-small`).
7. Open **Requests** and point to the two rows: requested model `auto`, resolved provider/model, and the route reason.

**Then switch to Terminal C and run:**

```powershell
python scripts/eval_spring_router.py --url http://localhost:8080
```

Show the first three lines and the summary.

**Callout:** `Short but hard → smart · long but trivial → fast · 20/20 vs 15/20 baseline`

**Say:**

> Now smart routing. I'll choose the auto alias. This prompt is only nine words, but it asks for a formal proof. Prism classifies it as reasoning and routes it to the smart tier. The response metadata shows the resolved model.

> Now the opposite trap. This prompt is long, but the task is a single timestamp lookup in a log. Prism classifies it as a simple extraction and routes it to the fast tier. A length-only router would get this backwards.

> The router is measured against the labeled evaluation set. It scores twenty out of twenty. The length-only baseline scores fifteen out of twenty. That is the required proof that it beats the baseline on short-but-hard and long-but-trivial traps.

---

### Scene 6 — Budget rejection (3:05–3:40)

**Do:**

1. Open **Playground**.
2. Select the `budget-demo` key.
3. Click **Reset usage** in the note under the key selector.
4. Select model `fast`.
5. Send `Say hello in one sentence.` The first request should be admitted.
6. Send the same prompt again. The second request should be rejected.
7. Open **Requests** and point to the rejected row showing `rejected_budget`.

**Callout:** `First request admitted · second request 429 budget_exceeded · rejected_budget`

**Say:**

> Every key enforces its own monthly budget. The budget-demo key has a deliberately tiny budget, smaller than one request. I've reset its usage. The first request is admitted. The second is rejected before it reaches a provider, with a distinct budget-exceeded error. On the Requests page it is recorded as rejected_budget. That is pre-flight financial quota enforcement, not an after-the-fact alert.

---

### Scene 7 — Rate limiting and no over-admission (3:40–4:15)

**Do:**

1. Switch to Terminal C.
2. Run the load test against the `free-tier` key:

```powershell
python scripts/load_test.py --url http://localhost:8080 `
  --key prism-sk-free-7g8h9i --model fast `
  --requests 30 --concurrency 10 --rpm-limit 10
```

3. Wait for the summary. Point to `accepted (200): 10` and `rate limited (429): 20`.
4. Point to the accounting totals printed underneath.

**Callout:** `30 requests · concurrency 10 · 10 accepted · 20 × 429 · zero over-admission`

**Say:**

> Rate limits are enforced with a sliding sixty-second window. This is the free-tier key, configured for ten requests per minute. The load test bursts thirty requests at concurrency ten. Exactly ten are accepted. Exactly twenty are rejected with 429. Zero over-admission under concurrency. The same run reconciles client-side token and cost totals with the server's usage record.

**Transition:**

> Cut here. Wait sixty seconds for the free-tier window to reset before recording the cache scene. Edit the pause out.

---

### Scene 8 — Semantic cache and tenant isolation (4:15–5:00)

**Do:**

1. Open **Semantic cache**. Click **Purge now**. Let the toast confirm the cache is empty.
2. Open **Playground**. Select the `search` key (cache is enabled for this key).
3. Send Cache A: `How do I reset my password on the dashboard?`
   - Response metadata should show `cache: miss`.
4. Send Cache B: `What are the steps to reset my dashboard password?`
   - Response metadata should show `cache: hit`.
5. Send the near-miss: `How do I reset my two-factor authentication on the dashboard?`
   - It should show `cache: miss`.
6. Switch to the `free-tier` key and send Cache B again.
   - It should show `cache: miss`, proving per-key isolation.
7. Return to **Semantic cache** and point to the hit rate and stored entries.

**Callout:** `Paraphrase → hit · near-miss → miss · different key → miss`

**Say:**

> This is the semantic cache. I'll purge it first so the demo starts clean. I'll send a question with the search key. That's a cache miss and a paid provider call.

> Now I'll ask the same question a different way. Prism matches the meaning using normalized word overlap and synonym normalization, clears the similarity threshold, and serves the stored response. The header contract reports a cache hit, and the cost is zero.

> A near miss — two-factor authentication instead of password — does not hit. And when I send the exact same paraphrase with a different virtual key, it misses again. Cache entries are scoped per key, so one team can never receive another team's response.

---

### Scene 9 — Live provider failover (5:00–5:35)

**Do:**

1. Open **Playground**. Select the `research` key (routing set to `mock` in pre-flight).
2. Select model `fast`. Send `Say hello in one sentence.` Point to `model: alpha-small` and `fallback: false`.
3. Switch to Terminal A and take Alpha down:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:9001/admin/config `
  -ContentType 'application/json' -Body '{"mode":"down"}'
```

4. Return to Playground and send the same request again. It should resolve to `beta-small` with `fallback: true`. Allow a few seconds for retries.
5. Open **Requests** and point to the fallback row.
6. Restore Alpha in Terminal A:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:9001/admin/config `
  -ContentType 'application/json' -Body '{"mode":"ok","fail_rate":0}'
```

**Callout:** `Alpha down → retries → Beta serves · x-prism-fallback: true`

**Say:**

> Now live provider failover. With both mock providers healthy, a fast request is served by Alpha.

> I'll take Alpha down with the mock provider's failure-injection endpoint. I'll send the same request again. The gateway retries transient failures with exponential backoff, then fails over to Beta. The response reports the new provider and sets x-prism-fallback to true. The Requests page records the fallback.

> I'll restore Alpha. No client code changed, and the caller still received a valid OpenAI-shaped completion.

---

### Scene 10 — Metering, request logs, and usage (5:35–6:05)

**Do:**

1. Open **Requests**. Point across the columns: request, team, model, provider, status, cost, latency, time.
2. Search or filter for one key used in the demo.
3. Open **Overview** and point to **Total spend** and **Usage by virtual key**.

**Callout:** `Key · model · provider · status · tokens · cost · cache · fallback · latency`

**Say:**

> Every request is metered from provider-reported token usage and the price table. The request log records the key, team, requested model, resolved provider, status, cache result, fallback flag, token counts, cost, latency, and time. That is enough to debug any AI traffic issue: which key, which route, what it cost, and why it failed. The same records drive the usage API and the monthly spend on the overview.

---

### Scene 11 — Verification recap (6:05–6:25)

**On screen:** `docs/VERIFICATION_REPORT.md`, or a clean slide with the three results.

**Callout:** `Smoke ✓ · Load ✓ · Routing 20/20 ✓`

**Say:**

> The repository ships the verification harness from the case study. The smoke test validates the OpenAI schema and the header contract. The load test proves zero over-admission and reconciles accounting. The routing eval reports twenty out of twenty against a fifteen out of twenty baseline. The full output is captured in the verification report.

---

### Scene 12 — Close (6:25–6:40)

**On screen:** End card with the repository URL and the demo-key table.

**Callout:** `github.com/dhruvaop/prism-gateway`

**Say:**

> Prism is one OpenAI-compatible API in front of multiple providers, with smart routing, streaming, per-key budgets and rate limits, semantic caching, metering, failover, and complete request logs. The README documents setup, the API, architecture, cache scoping, routing and failover design, and known limitations — including that rate-limit admission is per gateway instance and the cache uses a local normalized-overlap matcher rather than a hosted vector database. The seeded demo keys and mock providers reproduce everything you just saw. Thanks for watching.

---

## 6. Optional segment — user-facing chat (not required by the case study)

Record only if you also want to show the separate end-user surface. Keep it under 45 seconds.

**Do:**

1. Open `http://localhost:5174/chat.html`.
2. Sign in as a chat user created in **Access**.
3. Send one prompt and let it stream.
4. Point to `Served by · model details hidden` and the **5 free requests left** counter.
5. Click **Choose a plan** and show Pro ₹499/month and Enterprise ₹1,999/month.

**Say:**

> The same gateway also powers a private end-user chat. Chat users never see the upstream provider or model: the response is rebuilt with model details hidden. Self-serve accounts receive five free requests, then the Razorpay paywall offers Pro at ₹499 per month or Enterprise at ₹1,999 per month. Admin-provisioned accounts are unlimited.

---

## 7. Terminal appendix

### 7.1 Health and header contract

```powershell
Invoke-RestMethod http://localhost:8080/health

$r = Invoke-WebRequest -Method Post -Uri http://localhost:8080/v1/chat/completions `
  -Headers @{ Authorization = "Bearer prism-sk-research-4d5e6f" } `
  -ContentType "application/json" `
  -Body '{"model":"fast","messages":[{"role":"user","content":"Say hello"}]}'
$r.Headers["x-prism-provider"]
$r.Headers["x-prism-cache"]
$r.Headers["x-prism-fallback"]
$r.Headers["x-prism-cost-usd"]
```

### 7.2 Streaming

```powershell
$body = '{"model":"fast","stream":true,"messages":[{"role":"user","content":"Explain token streaming in one paragraph."}]}'
curl.exe -N http://localhost:8080/v1/chat/completions `
  -H "Authorization: Bearer prism-sk-research-4d5e6f" `
  -H "Content-Type: application/json" `
  -d $body
```

### 7.3 Routing evaluation

```powershell
python scripts/eval_spring_router.py --url http://localhost:8080
```

Expected summary:

```text
Routing evaluation: 20/20 correct (100.0%)
Length-only baseline: 15/20 (75.0%) at 42 chars
Task router beats length-only baseline: YES
```

### 7.4 Load test

```powershell
python scripts/load_test.py --url http://localhost:8080 `
  --key prism-sk-free-7g8h9i --model fast `
  --requests 30 --concurrency 10 --rpm-limit 10
```

Expected summary:

```text
accepted (200):      10
rate limited (429):  20
```

### 7.5 Failure injection

```powershell
# Take Alpha down
Invoke-RestMethod -Method Post -Uri http://localhost:9001/admin/config `
  -ContentType 'application/json' -Body '{"mode":"down"}'

# Restore Alpha
Invoke-RestMethod -Method Post -Uri http://localhost:9001/admin/config `
  -ContentType 'application/json' -Body '{"mode":"ok","fail_rate":0}'

# Check the current injection state
Invoke-RestMethod http://localhost:9001/admin/config
```

### 7.6 Smoke test

```powershell
python scripts/smoke_test.py --url http://localhost:8080 `
  --key prism-sk-search-1a2b3c --model fast
```

---

## 8. Troubleshooting during recording

| Symptom | Fix |
|---|---|
| Cache does not hit on the paraphrase | Purge, then send A first and B second with the same key. Confirm the key's cache is enabled and the similarity threshold is at or below the plan value. |
| Budget key rejects the first request | Click **Reset usage** again; confirm you selected `budget-demo`, not `free-tier`. |
| Load test accepts fewer than 10 | Another request used the key in the last minute. Wait 60 seconds and rerun. |
| Failover still shows Alpha | Confirm the injection response reports `mode: down`; wait for retries; refresh **Routing health**. |
| Streaming appears all at once | Record the terminal `curl -N` cutaway; avoid a buffering proxy. |
| Smart routing picks the wrong tier | Use the exact `route_011` and `route_007` prompts. Purge cache and use the `mock` routing profile. |
| Chat paywall does not appear | The chat account must be a self-serve signup, not an admin-provisioned user. |

---

## 9. Editing notes

- Add a 2-second zoom on `accepted (200): 10`, `rate limited (429): 20`, `20/20`, and `x-prism-fallback: true`.
- Blur the bottom of the terminal if shell history is visible.
- Keep the six required demos in the order above; they match the case-study wording.
- Use the callout text as on-screen captions, not as spoken words.
- Do not add background music while narration is playing.
- End card should show the repository URL, the four seeded demo keys, and the mock-provider startup command.
