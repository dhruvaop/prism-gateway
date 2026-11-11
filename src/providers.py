"""Provider Adapter Layer for Prism Gateway.
Handles alias resolution, ordered fallback chains, exponential backoff retries,
upstream timeouts, and pass-through streaming.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any, AsyncGenerator, Dict, List, Optional, Tuple
import httpx

from src.classifier import CLASSIFIER
from src.config import (
    MODEL_ALIASES,
    MODEL_PRICING,
    OPENROUTER_API_KEY,
    OPENROUTER_BASE_URL,
    PROVIDERS,
    RETRY_CONFIG,
    calculate_cost,
)

logger = logging.getLogger("prism.providers")


class UpstreamError(Exception):
    """Raised when all providers in the fallback chain fail."""
    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message)
        self.status_code = status_code


def resolve_model_chain(requested_model: str, prompt_text: str = "") -> Tuple[List[str], Optional[str]]:
    """Resolve an alias or model name to an ordered chain of provider models.
    For 'auto', classifies prompt difficulty and routes to fast or smart.
    Returns: (model_chain: List[str], route_reason: Optional[str])
    """
    route_reason = None
    target_alias = requested_model

    if requested_model == "auto":
        tier, confidence, reason = CLASSIFIER.classify(prompt_text)
        target_alias = tier
        route_reason = f"auto -> {tier}: {reason}"

    if target_alias in MODEL_ALIASES:
        alias_cfg = MODEL_ALIASES[target_alias]
        primary = alias_cfg.get("primary")
        fallbacks = alias_cfg.get("fallbacks", [])
        chain = [primary] + [f for f in fallbacks if f != primary]
        return chain, route_reason

    # Direct model requested
    return [requested_model], route_reason


def get_provider_for_model(model_name: str) -> Optional[Dict[str, Any]]:
    """Map a model identifier to its registered upstream provider config."""
    if model_name.startswith("openrouter/"):
        return {
            "name": "openrouter",
            "base_url": OPENROUTER_BASE_URL,
            "api_key": OPENROUTER_API_KEY,
            "actual_model": model_name.replace("openrouter/", "")
        }

    provider_name = model_name.split("-")[0] if "-" in model_name else model_name
    if provider_name in PROVIDERS:
        cfg = dict(PROVIDERS[provider_name])
        cfg["actual_model"] = model_name
        return cfg

    # Default fallback to alpha
    if "alpha" in PROVIDERS:
        cfg = dict(PROVIDERS["alpha"])
        cfg["actual_model"] = model_name
        return cfg

    return None


async def execute_non_streaming(
    requested_model: str,
    request_body: Dict[str, Any],
    prompt_text: str = "",
    timeout_seconds: float = 8.0
) -> Dict[str, Any]:
    """Execute non-streaming chat completion across the model fallback chain with retries."""
    chain, route_reason = resolve_model_chain(requested_model, prompt_text)
    max_attempts = RETRY_CONFIG.get("max_attempts", 3)
    initial_backoff = RETRY_CONFIG.get("initial_backoff_ms", 200) / 1000.0
    backoff_multiplier = RETRY_CONFIG.get("backoff_multiplier", 2)

    total_retries = 0
    errors = []

    async with httpx.AsyncClient(timeout=timeout_seconds) as client:
        for idx, model in enumerate(chain):
            is_fallback = (idx > 0)
            provider_cfg = get_provider_for_model(model)
            if not provider_cfg:
                continue

            base_url = provider_cfg["base_url"].rstrip("/")
            api_key = provider_cfg.get("api_key", "mock-key")
            actual_model = provider_cfg.get("actual_model", model)

            url = f"{base_url}/chat/completions"
            body = dict(request_body)
            body["model"] = actual_model
            body["stream"] = False

            headers = {
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json"
            }

            for attempt in range(max_attempts):
                try:
                    resp = await client.post(url, json=body, headers=headers)
                    if resp.status_code == 200:
                        payload = resp.json()
                        usage = payload.get("usage", {})
                        prompt_toks = usage.get("prompt_tokens", 0)
                        comp_toks = usage.get("completion_tokens", 0)
                        cost_usd = calculate_cost(model, prompt_toks, comp_toks)

                        return {
                            "payload": payload,
                            "resolved_provider": f"{provider_cfg['name']}/{actual_model}",
                            "resolved_model": actual_model,
                            "is_fallback": is_fallback,
                            "cost_usd": cost_usd,
                            "prompt_tokens": prompt_toks,
                            "completion_tokens": comp_toks,
                            "route_reason": route_reason,
                            "retries": total_retries
                        }
                    elif resp.status_code in (429, 500, 502, 503, 504):
                        total_retries += 1
                        errors.append(f"Provider {provider_cfg['name']} returned HTTP {resp.status_code}")
                        await asyncio.sleep(initial_backoff * (backoff_multiplier ** attempt))
                    else:
                        # Client or unretryable error
                        errors.append(f"Provider {provider_cfg['name']} HTTP {resp.status_code}: {resp.text[:100]}")
                        break
                except (httpx.TimeoutException, httpx.NetworkError) as exc:
                    total_retries += 1
                    errors.append(f"Provider {provider_cfg['name']} network error: {type(exc).__name__}")
                    await asyncio.sleep(initial_backoff * (backoff_multiplier ** attempt))

    raise UpstreamError(f"All providers failed in chain: {'; '.join(errors[-3:])}")


async def execute_streaming(
    requested_model: str,
    request_body: Dict[str, Any],
    prompt_text: str = "",
    timeout_seconds: float = 12.0
) -> Tuple[AsyncGenerator[str, None], Dict[str, Any]]:
    """Execute streaming completion across fallback chain with pass-through zero-buffering."""
    chain, route_reason = resolve_model_chain(requested_model, prompt_text)
    total_retries = 0

    client = httpx.AsyncClient(timeout=timeout_seconds)

    for idx, model in enumerate(chain):
        is_fallback = (idx > 0)
        provider_cfg = get_provider_for_model(model)
        if not provider_cfg:
            continue

        base_url = provider_cfg["base_url"].rstrip("/")
        api_key = provider_cfg.get("api_key", "mock-key")
        actual_model = provider_cfg.get("actual_model", model)

        url = f"{base_url}/chat/completions"
        body = dict(request_body)
        body["model"] = actual_model
        body["stream"] = True

        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        }

        try:
            req = client.build_request("POST", url, json=body, headers=headers)
            resp = await client.send(req, stream=True)

            if resp.status_code == 200:
                metadata = {
                    "resolved_provider": f"{provider_cfg['name']}/{actual_model}",
                    "resolved_model": actual_model,
                    "is_fallback": is_fallback,
                    "route_reason": route_reason,
                    "retries": total_retries,
                    "prompt_tokens": 0,
                    "completion_tokens": 0,
                    "cost_usd": 0.0
                }

                async def stream_generator() -> AsyncGenerator[str, None]:
                    try:
                        buffer = ""
                        async for chunk in resp.aiter_bytes():
                            text = chunk.decode("utf-8", errors="replace")
                            buffer += text
                            while "\n" in buffer:
                                line, buffer = buffer.split("\n", 1)
                                line_clean = line.strip()
                                if line_clean.startswith("data: ") and line_clean != "data: [DONE]":
                                    try:
                                        payload = json.loads(line_clean[6:])
                                        usage = payload.get("usage")
                                        if usage:
                                            metadata["prompt_tokens"] = usage.get("prompt_tokens", 0)
                                            metadata["completion_tokens"] = usage.get("completion_tokens", 0)
                                            metadata["cost_usd"] = calculate_cost(
                                                model, metadata["prompt_tokens"], metadata["completion_tokens"]
                                            )
                                    except Exception:
                                        pass
                                yield line + "\n"
                        if buffer:
                            yield buffer
                    finally:
                        await resp.aclose()
                        await client.aclose()

                return stream_generator(), metadata
            else:
                total_retries += 1
                await resp.aclose()
        except Exception:
            total_retries += 1

    await client.aclose()
    raise UpstreamError("All providers in fallback chain failed for streaming request")
