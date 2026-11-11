"""Pre-flight budget checker and atomic spend accounting for Prism Gateway.
"""

from __future__ import annotations

from typing import Dict, Tuple
from src.db import atomic_increment_spend, get_tenant


def format_budget(amount: float) -> str:
    """Format budget cleanly: e.g. 50 -> '$50.00', 5.0 -> '$5.00', 0.00001 -> '$0.00001'."""
    if amount >= 0.01:
        return f"${amount:.2f}"
    formatted = f"{amount:.6f}".rstrip("0")
    if formatted.endswith("."):
        formatted += "00"
    elif len(formatted.split(".")[1]) < 2:
        formatted += "0"
    return f"${formatted}"


def check_budget(virtual_key: str, tenant: Dict) -> Tuple[bool, str]:
    """Pre-flight check if tenant's monthly budget is exhausted.
    Returns (allowed: bool, reason: str).
    """
    monthly_budget = tenant.get("monthly_budget_usd", 0.0)
    current_spend = tenant.get("current_spend_usd", 0.0)

    if current_spend >= monthly_budget:
        budget_str = format_budget(monthly_budget)
        return False, f"Monthly budget of {budget_str} exhausted for this key"
    return True, "ok"


def record_spend(virtual_key: str, cost_usd: float) -> float:
    """Atomically record cost and increment tenant's monthly spend."""
    if cost_usd <= 0.0:
        return 0.0
    return atomic_increment_spend(virtual_key, cost_usd)
