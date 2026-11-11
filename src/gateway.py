"""Prism LLM Gateway - Production-Grade FastAPI Application.
Exposes OpenAI-compatible POST /v1/chat/completions, enforces header contract,
per-tenant rate limits & budgets, semantic caching, smart auto-routing, and ops console.
"""

from __future__ import annotations

import json
import time
import uuid
from typing import Any, Dict, List, Optional
from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse

from src.budget import check_budget, record_spend
from src.classifier import CLASSIFIER, evaluate_routing_set
from src.config import (
    DATA_DIR,
    MODEL_ALIASES,
    MODEL_PRICING,
    calculate_cost,
    load_json,
)
from src.db import (
    get_all_tenants,
    get_recent_logs,
    get_tenant,
    get_usage_summary,
    init_db,
    record_request_log,
)
from src.models import (
    ChatCompletionRequest,
    ChatCompletionResponse,
    ErrorDetail,
    ErrorResponse,
)
from src.providers import (
    UpstreamError,
    execute_non_streaming,
    execute_streaming,
)
from src.rate_limiter import RATE_LIMITER
from src.semantic_cache import CACHE, extract_user_prompt

app = FastAPI(
    title="Prism LLM Gateway",
    description="Production-Grade AI Gateway with Smart Routing & Semantic Cache",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def on_startup():
    init_db()


def openai_error(status_code: int, message: str, err_type: str, code: str) -> JSONResponse:
    """Helper to return an exact OpenAI error response body."""
    return JSONResponse(
        status_code=status_code,
        content={"error": {"message": message, "type": err_type, "code": code}},
    )


@app.get("/health")
def health():
    return {"status": "ok", "service": "prism-gateway", "version": "1.0.0"}


@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    start_time = time.monotonic()
    req_id = f"chatcmpl-{uuid.uuid4().hex[:12]}"

    # 1. Authenticate Virtual Key
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        return openai_error(401, "Missing or invalid Authorization header", "authentication_error", "invalid_api_key")

    virtual_key = auth_header[7:].strip()
    tenant = get_tenant(virtual_key)
    if not tenant or tenant.get("status") == "disabled":
        return openai_error(401, "Invalid or unauthorized virtual API key", "authentication_error", "invalid_api_key")

    # 2. Parse & Validate Request Body
    try:
        body = await request.json()
    except Exception:
        return openai_error(400, "Malformed JSON request body", "invalid_request_error", "invalid_json")

    requested_model = body.get("model", "")
    messages = body.get("messages", [])
    stream = bool(body.get("stream", False))

    if not requested_model:
        return openai_error(400, "Missing required field: model", "invalid_request_error", "model_missing")

    # Validate model exists in aliases or pricing table
    known_models = set(MODEL_ALIASES.keys()) | set(MODEL_PRICING.keys())
    if requested_model not in known_models and not requested_model.startswith("openrouter/"):
        return openai_error(404, f"The model '{requested_model}' does not exist or is not recognized", "not_found_error", "model_not_found")

    # Check model allowlist for this tenant
    allowlist = tenant.get("model_allowlist", [])
    if requested_model not in allowlist and requested_model != "auto":
        record_request_log({
            "request_id": req_id, "virtual_key": virtual_key, "requested_model": requested_model,
            "status": "rejected_allowlist", "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        })
        return openai_error(403, f"Model '{requested_model}' is not on key's allowlist", "model_not_allowed", "model_not_allowed")

    # 3. Rate Limit Enforcement (Concurrency-safe sliding window)
    rpm_limit = tenant.get("rpm_limit", 60)
    tpm_limit = tenant.get("tpm_limit", 1000000)
    prompt_text = extract_user_prompt(messages)
    est_tokens = max(1, len(prompt_text.split()))

    allowed, limit_reason = RATE_LIMITER.acquire(virtual_key, rpm_limit, est_tokens, tpm_limit)
    if not allowed:
        record_request_log({
            "request_id": req_id, "virtual_key": virtual_key, "requested_model": requested_model,
            "status": "rejected_rate_limit", "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        })
        return openai_error(429, limit_reason, "rate_limit_exceeded", "rate_limit_exceeded")

    # 4. Pre-Flight Monthly Budget Enforcement
    budget_ok, budget_reason = check_budget(virtual_key, tenant)
    if not budget_ok:
        record_request_log({
            "request_id": req_id, "virtual_key": virtual_key, "requested_model": requested_model,
            "status": "rejected_budget", "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        })
        return openai_error(429, budget_reason, "budget_exceeded", "budget_exceeded")

    # 5. Semantic Cache Lookup (Scoped strictly per tenant key)
    if tenant.get("cache_enabled", False):
        threshold = tenant.get("similarity_threshold", 0.90) or 0.90
        cached_payload, similarity, match_type = CACHE.lookup(virtual_key, requested_model, messages, threshold)

        if cached_payload is not None:
            latency_ms = round((time.monotonic() - start_time) * 1000)
            record_request_log({
                "request_id": req_id, "virtual_key": virtual_key, "requested_model": requested_model,
                "resolved_provider": "cached", "resolved_model": cached_payload.get("model", requested_model),
                "status": "cache_hit", "prompt_tokens": cached_payload.get("usage", {}).get("prompt_tokens", 0),
                "completion_tokens": cached_payload.get("usage", {}).get("completion_tokens", 0),
                "cost_usd": 0.0, "cache": "hit", "fallback": False, "latency_ms": latency_ms,
                "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            })

            headers = {
                "x-prism-provider": "cached",
                "x-prism-cache": "hit",
                "x-prism-fallback": "false",
                "x-prism-cost-usd": "0.000000"
            }

            if not stream:
                return JSONResponse(status_code=200, content=cached_payload, headers=headers)
            else:
                # Replay cached completion as SSE stream
                async def replay_cached_stream():
                    content = cached_payload.get("choices", [{}])[0].get("message", {}).get("content", "")
                    chunk_id = f"chatcmpl-{uuid.uuid4().hex[:12]}"
                    for word in content.split():
                        chunk_obj = {
                            "id": chunk_id, "object": "chat.completion.chunk",
                            "created": int(time.time()), "model": cached_payload.get("model", requested_model),
                            "choices": [{"index": 0, "delta": {"content": word + " "}, "finish_reason": None}]
                        }
                        yield f"data: {json.dumps(chunk_obj)}\n\n"
                    done_chunk = {
                        "id": chunk_id, "object": "chat.completion.chunk",
                        "created": int(time.time()), "model": cached_payload.get("model", requested_model),
                        "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                        "usage": cached_payload.get("usage", {})
                    }
                    yield f"data: {json.dumps(done_chunk)}\n\n"
                    yield "data: [DONE]\n\n"

                stream_headers = dict(headers)
                stream_headers.pop("x-prism-cost-usd", None)
                return StreamingResponse(replay_cached_stream(), media_type="text/event-stream", headers=stream_headers)

    # 6. Upstream Provider Execution (Non-Streaming or Streaming)
    if not stream:
        try:
            result = await execute_non_streaming(requested_model, body, prompt_text)
        except UpstreamError as exc:
            record_request_log({
                "request_id": req_id, "virtual_key": virtual_key, "requested_model": requested_model,
                "status": "upstream_error", "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            })
            return openai_error(exc.status_code, str(exc), "upstream_error", "all_providers_failed")

        latency_ms = round((time.monotonic() - start_time) * 1000)
        cost_usd = result["cost_usd"]

        # Record spend atomically
        record_spend(virtual_key, cost_usd)
        RATE_LIMITER.record_actual_tokens(virtual_key, result["prompt_tokens"] + result["completion_tokens"])

        # Record immutable request log
        record_request_log({
            "request_id": req_id, "virtual_key": virtual_key, "requested_model": requested_model,
            "resolved_provider": result["resolved_provider"], "resolved_model": result["resolved_model"],
            "status": "ok", "prompt_tokens": result["prompt_tokens"],
            "completion_tokens": result["completion_tokens"], "cost_usd": cost_usd,
            "cache": "miss", "fallback": result["is_fallback"],
            "route_reason": result.get("route_reason"), "retries": result.get("retries", 0),
            "latency_ms": latency_ms, "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        })

        # Store in semantic cache if enabled
        if tenant.get("cache_enabled", False):
            CACHE.store(virtual_key, requested_model, messages, result["payload"])

        headers = {
            "x-prism-provider": result["resolved_provider"],
            "x-prism-cache": "miss",
            "x-prism-cost-usd": f"{cost_usd:.6f}",
        }
        if result["is_fallback"]:
            headers["x-prism-fallback"] = "true"

        return JSONResponse(status_code=200, content=result["payload"], headers=headers)

    else:
        # Pass-Through Streaming
        try:
            stream_gen, meta = await execute_streaming(requested_model, body, prompt_text)
        except UpstreamError as exc:
            record_request_log({
                "request_id": req_id, "virtual_key": virtual_key, "requested_model": requested_model,
                "status": "upstream_error", "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            })
            return openai_error(exc.status_code, str(exc), "upstream_error", "all_providers_failed")

        async def stream_wrapper():
            async for chunk in stream_gen:
                yield chunk

            # Once stream terminates cleanly: record spend and persist request log
            latency_ms = round((time.monotonic() - start_time) * 1000)
            cost_usd = meta.get("cost_usd", 0.0)
            record_spend(virtual_key, cost_usd)

            record_request_log({
                "request_id": req_id, "virtual_key": virtual_key, "requested_model": requested_model,
                "resolved_provider": meta["resolved_provider"], "resolved_model": meta["resolved_model"],
                "status": "ok", "prompt_tokens": meta.get("prompt_tokens", 0),
                "completion_tokens": meta.get("completion_tokens", 0), "cost_usd": cost_usd,
                "cache": "miss", "fallback": meta["is_fallback"],
                "route_reason": meta.get("route_reason"), "retries": meta.get("retries", 0),
                "latency_ms": latency_ms, "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            })

        response_headers = {
            "x-prism-provider": meta["resolved_provider"],
            "x-prism-cache": "miss",
        }
        if meta["is_fallback"]:
            response_headers["x-prism-fallback"] = "true"

        return StreamingResponse(stream_wrapper(), media_type="text/event-stream", headers=response_headers)


# -------------------------------------------------------------
# Admin Plane APIs
# -------------------------------------------------------------

@app.get("/admin/usage")
def admin_usage(key: str, from_date: Optional[str] = None, to_date: Optional[str] = None):
    """Usage summary by key and date window."""
    summary = get_usage_summary(key, from_date, to_date)
    return summary


@app.get("/admin/logs")
def admin_logs(key: Optional[str] = None, limit: int = 50):
    """Recent request logs."""
    return get_recent_logs(key, limit)


@app.get("/admin/cache/stats")
def admin_cache_stats():
    """Semantic cache telemetry."""
    return CACHE.get_stats()


@app.get("/admin/tenants")
def admin_tenants():
    """List all registered tenants and current spend."""
    return get_all_tenants()


@app.get("/admin/eval/routing")
def admin_eval_routing():
    """Run one-command evaluation of prompt complexity classifier over data/routing_eval.jsonl."""
    eval_file = DATA_DIR / "routing_eval.jsonl"
    cases = []
    with eval_file.open("r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                cases.append(json.loads(line))
    return evaluate_routing_set(cases)


@app.get("/", response_class=HTMLResponse)
@app.get("/console", response_class=HTMLResponse)
def ops_console():
    """Simple, elegant Ops Console web UI."""
    return """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <title>Prism LLM Gateway - Ops Console</title>
        <script src="https://cdn.tailwindcss.com"></script>
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
    </head>
    <body class="bg-slate-950 text-slate-100 min-h-screen">
        <div class="max-w-7xl mx-auto p-6 space-y-6">
            <header class="flex justify-between items-center border-b border-slate-800 pb-4">
                <div class="flex items-center space-x-3">
                    <div class="w-10 h-10 rounded-lg bg-indigo-600 flex items-center justify-center font-bold text-xl shadow-lg shadow-indigo-500/20">P</div>
                    <div>
                        <h1 class="text-2xl font-bold tracking-tight">Prism LLM Gateway</h1>
                        <p class="text-xs text-slate-400">AI-First Middleware, Smart Routing & Semantic Cache</p>
                    </div>
                </div>
                <div class="flex items-center space-x-2">
                    <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-950 text-emerald-400 border border-emerald-800">
                        <span class="w-1.5 h-1.5 mr-1.5 bg-emerald-400 rounded-full animate-pulse"></span> Data Plane Active (Port 8000)
                    </span>
                    <button onclick="refreshAll()" class="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-xs rounded-md transition"><i class="fa-solid fa-arrows-rotate mr-1"></i> Refresh</button>
                </div>
            </header>

            <div class="grid grid-cols-1 md:grid-cols-4 gap-4">
                <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                    <p class="text-xs font-medium text-slate-400">Total Spend (Active Month)</p>
                    <p id="kpi-spend" class="text-2xl font-bold text-emerald-400 mt-1">$0.000000</p>
                </div>
                <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                    <p class="text-xs font-medium text-slate-400">Processed Requests</p>
                    <p id="kpi-reqs" class="text-2xl font-bold text-indigo-400 mt-1">0</p>
                </div>
                <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                    <p class="text-xs font-medium text-slate-400">Cache Hit Rate</p>
                    <p id="kpi-cache" class="text-2xl font-bold text-sky-400 mt-1">0.0%</p>
                </div>
                <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                    <p class="text-xs font-medium text-slate-400">Classifier Accuracy</p>
                    <p id="kpi-router" class="text-2xl font-bold text-purple-400 mt-1">100.0%</p>
                </div>
            </div>

            <!-- Tenants & Quotas -->
            <div class="bg-slate-900 border border-slate-800 rounded-xl p-5">
                <h2 class="text-sm font-semibold text-slate-300 uppercase tracking-wider mb-4"><i class="fa-solid fa-key text-indigo-400 mr-2"></i>Tenants & Virtual Key Quotas</h2>
                <div class="overflow-x-auto">
                    <table class="w-full text-left text-xs text-slate-300">
                        <thead class="bg-slate-950 text-slate-400 uppercase border-b border-slate-800">
                            <tr>
                                <th class="p-3">Team</th>
                                <th class="p-3">Virtual Key</th>
                                <th class="p-3">Current Spend / Budget</th>
                                <th class="p-3">RPM Limit</th>
                                <th class="p-3">Allowlist</th>
                                <th class="p-3">Semantic Cache</th>
                            </tr>
                        </thead>
                        <tbody id="tenants-table" class="divide-y divide-slate-800/50"></tbody>
                    </table>
                </div>
            </div>

            <!-- Recent Requests Log -->
            <div class="bg-slate-900 border border-slate-800 rounded-xl p-5">
                <div class="flex justify-between items-center mb-4">
                    <h2 class="text-sm font-semibold text-slate-300 uppercase tracking-wider"><i class="fa-solid fa-list-check text-sky-400 mr-2"></i>Recent Data Plane Logs</h2>
                    <span class="text-xs text-slate-500">Auto-refreshing</span>
                </div>
                <div class="overflow-x-auto">
                    <table class="w-full text-left text-xs text-slate-300">
                        <thead class="bg-slate-950 text-slate-400 uppercase border-b border-slate-800">
                            <tr>
                                <th class="p-3">Timestamp</th>
                                <th class="p-3">Key / Team</th>
                                <th class="p-3">Model</th>
                                <th class="p-3">Provider Served</th>
                                <th class="p-3">Status</th>
                                <th class="p-3">Cache</th>
                                <th class="p-3">Tokens</th>
                                <th class="p-3">Cost (USD)</th>
                                <th class="p-3">Latency</th>
                            </tr>
                        </thead>
                        <tbody id="logs-table" class="divide-y divide-slate-800/50"></tbody>
                    </table>
                </div>
            </div>
        </div>

        <script>
            async function refreshAll() {
                try {
                    const [tenantsRes, logsRes, cacheRes, evalRes] = await Promise.all([
                        fetch('/admin/tenants').then(r => r.json()),
                        fetch('/admin/logs?limit=15').then(r => r.json()),
                        fetch('/admin/cache/stats').then(r => r.json()),
                        fetch('/admin/eval/routing').then(r => r.json())
                    ]);

                    // Update Tenants
                    const tbody = document.getElementById('tenants-table');
                    tbody.innerHTML = '';
                    let totalSpend = 0;
                    tenantsRes.forEach(t => {
                        totalSpend += (t.current_spend_usd || 0);
                        const tr = document.createElement('tr');
                        tr.innerHTML = `
                            <td class="p-3 font-semibold text-slate-200">${t.team}</td>
                            <td class="p-3 font-mono text-slate-400">${t.virtual_key}</td>
                            <td class="p-3"><span class="text-emerald-400 font-medium">$${(t.current_spend_usd||0).toFixed(6)}</span> / $${t.monthly_budget_usd.toFixed(2)}</td>
                            <td class="p-3">${t.rpm_limit} RPM</td>
                            <td class="p-3 font-mono text-slate-400">${t.model_allowlist.join(', ')}</td>
                            <td class="p-3">${t.cache_enabled ? '<span class="text-sky-400">Enabled (' + t.similarity_threshold + ')</span>' : '<span class="text-slate-500">Disabled</span>'}</td>
                        `;
                        tbody.appendChild(tr);
                    });
                    document.getElementById('kpi-spend').innerText = '$' + totalSpend.toFixed(6);

                    // Update Logs
                    const logBody = document.getElementById('logs-table');
                    logBody.innerHTML = '';
                    document.getElementById('kpi-reqs').innerText = logsRes.length;
                    logsRes.forEach(l => {
                        const tr = document.createElement('tr');
                        const isOk = l.status === 'ok' || l.status === 'cache_hit';
                        const statusBadge = isOk 
                            ? '<span class="px-2 py-0.5 rounded text-[10px] bg-emerald-950 text-emerald-400 border border-emerald-800">200 OK</span>'
                            : '<span class="px-2 py-0.5 rounded text-[10px] bg-rose-950 text-rose-400 border border-rose-800">' + l.status + '</span>';
                        const cacheBadge = l.cache === 'hit'
                            ? '<span class="text-sky-400 font-semibold">HIT</span>'
                            : '<span class="text-slate-500">MISS</span>';
                        tr.innerHTML = `
                            <td class="p-3 text-slate-400">${l.created_at.split('T')[1]?.split('.')[0] || l.created_at}</td>
                            <td class="p-3 font-mono">${l.virtual_key.split('-')[2] || l.virtual_key}</td>
                            <td class="p-3 font-semibold">${l.requested_model}</td>
                            <td class="p-3 font-mono text-slate-400">${l.resolved_provider || '-'} ${l.fallback ? '<span class="text-amber-400">(fallback)</span>' : ''}</td>
                            <td class="p-3">${statusBadge}</td>
                            <td class="p-3">${cacheBadge}</td>
                            <td class="p-3">${(l.prompt_tokens + l.completion_tokens) || 0}</td>
                            <td class="p-3 text-emerald-400 font-mono">$${(l.cost_usd || 0).toFixed(6)}</td>
                            <td class="p-3">${l.latency_ms}ms</td>
                        `;
                        logBody.appendChild(tr);
                    });

                    // Update Cache
                    document.getElementById('kpi-cache').innerText = (cacheRes.hit_rate * 100).toFixed(1) + '%';
                    document.getElementById('kpi-router').innerText = (evalRes.accuracy * 100).toFixed(1) + '%';
                } catch(e) {
                    console.error('Error refreshing console:', e);
                }
            }

            refreshAll();
            setInterval(refreshAll, 3000);
        </script>
    </body>
    </html>
    """
