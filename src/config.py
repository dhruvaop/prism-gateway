"""Configuration manager for Prism Gateway.
Loads pricing table, seed tenants, and gateway configuration from the data/ directory.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT_DIR / "data"

OPENROUTER_API_KEY = "<OPENROUTER_API_KEY_REDACTED>"
OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"


def load_json(filename: str) -> Dict[str, Any]:
    path = DATA_DIR / filename
    if not path.exists():
        raise FileNotFoundError(f"Missing required configuration file: {path}")
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


# Load seed data
MODEL_PRICING: Dict[str, Dict[str, float]] = {
    k: v for k, v in load_json("model_pricing.json").items() if not k.startswith("_")
}

SEED_KEYS_RAW = load_json("seed_keys.json")
TENANTS: Dict[str, Dict[str, Any]] = {
    t["virtual_key"]: t for t in SEED_KEYS_RAW.get("tenants", [])
}

GATEWAY_CONFIG = load_json("gateway_config.sample.json")
PROVIDERS: Dict[str, Dict[str, Any]] = {
    p["name"]: p for p in GATEWAY_CONFIG.get("providers", [])
}
MODEL_ALIASES: Dict[str, Dict[str, Any]] = GATEWAY_CONFIG.get("model_aliases", {})
RETRY_CONFIG: Dict[str, Any] = GATEWAY_CONFIG.get(
    "retry", {"max_attempts": 3, "initial_backoff_ms": 200, "backoff_multiplier": 2}
)
DEGRADATION_CONFIG: Dict[str, Any] = GATEWAY_CONFIG.get("degradation", {})


def calculate_cost(model: str, prompt_tokens: int, completion_tokens: int) -> float:
    """Calculate request cost in USD using model_pricing.json rates."""
    price = MODEL_PRICING.get(model)
    if not price:
        # Check if provider prefix stripped or fallback
        for priced_model, p in MODEL_PRICING.items():
            if priced_model in model or model in priced_model:
                price = p
                break
    if not price:
        return 0.0

    input_cost = (prompt_tokens * price.get("input_per_1m", 0.0)) / 1_000_000.0
    output_cost = (completion_tokens * price.get("output_per_1m", 0.0)) / 1_000_000.0
    return round(input_cost + output_cost, 6)
