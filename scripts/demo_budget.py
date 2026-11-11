#!/usr/bin/env python3
"""Live Demo Drill: Budget Exhaustion with tiny-budget key."""

import json
import sqlite3
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB_PATH = ROOT / "prism.db"

# 1. Reset spend to 0.0 for budget-demo key
conn = sqlite3.connect(str(DB_PATH))
conn.execute("UPDATE tenants SET current_spend_usd = 0.0 WHERE virtual_key = 'prism-sk-budget-demo-0j1k2l'")
conn.commit()
conn.close()

def send_req(step: int, prompt: str):
    req = urllib.request.Request(
        "http://localhost:8000/v1/chat/completions",
        data=json.dumps({
            "model": "fast",
            "messages": [{"role": "user", "content": prompt}]
        }).encode(),
        headers={
            "Authorization": "Bearer prism-sk-budget-demo-0j1k2l",
            "Content-Type": "application/json"
        },
        method="POST"
    )
    try:
        with urllib.request.urlopen(req) as resp:
            headers = {k.lower(): v for k, v in resp.headers.items()}
            cost = headers.get("x-prism-cost-usd", "0.0")
            print(f"  Step {step}: HTTP {resp.status} OK (Cost: ${cost} USD)")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"  Step {step}: HTTP {e.code} REJECTED -> {body}")

print("=" * 60)
print("  LIVE DRILL: BUDGET EXHAUSTION (prism-sk-budget-demo-0j1k2l)")
print("  Configured Monthly Budget: $0.000010 USD")
print("=" * 60)

print("\n[1] Sending Request 1 (should be admitted, consuming the tiny budget):")
send_req(1, "What is a message queue?")

print("\n[2] Sending Request 2 (should be rejected pre-flight with budget_exceeded):")
send_req(2, "What is an event bus?")

print("\n" + "=" * 60)
