"""SQLite persistence layer for Prism Gateway with WAL mode for concurrent operations.
Stores virtual keys, monthly spend, immutable request logs, and semantic cache entries.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from src.config import DATA_DIR, ROOT_DIR, SEED_KEYS_RAW, TENANTS

DB_PATH = ROOT_DIR / "prism.db"
_LOCK = threading.Lock()


def get_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), check_same_thread=False, timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=NORMAL;")
    conn.execute("PRAGMA busy_timeout=5000;")
    return conn


def init_db() -> None:
    """Initialize database tables and seed tenants if not already present."""
    with _LOCK:
        conn = get_connection()
        try:
            with conn:
                conn.execute("""
                CREATE TABLE IF NOT EXISTS tenants (
                    virtual_key TEXT PRIMARY KEY,
                    team TEXT NOT NULL,
                    monthly_budget_usd REAL NOT NULL,
                    rpm_limit INTEGER NOT NULL,
                    tpm_limit INTEGER NOT NULL,
                    model_allowlist TEXT NOT NULL,
                    cache_enabled INTEGER NOT NULL,
                    similarity_threshold REAL,
                    current_spend_usd REAL DEFAULT 0.0,
                    status TEXT DEFAULT 'active',
                    created_at TEXT NOT NULL
                );
                """)

                conn.execute("""
                CREATE TABLE IF NOT EXISTS request_logs (
                    request_id TEXT PRIMARY KEY,
                    virtual_key TEXT NOT NULL,
                    requested_model TEXT NOT NULL,
                    resolved_provider TEXT,
                    resolved_model TEXT,
                    status TEXT NOT NULL,
                    prompt_tokens INTEGER DEFAULT 0,
                    completion_tokens INTEGER DEFAULT 0,
                    cost_usd REAL DEFAULT 0.0,
                    cache TEXT DEFAULT 'miss',
                    fallback INTEGER DEFAULT 0,
                    route_reason TEXT,
                    retries INTEGER DEFAULT 0,
                    latency_ms INTEGER DEFAULT 0,
                    created_at TEXT NOT NULL
                );
                """)

                conn.execute("""
                CREATE INDEX IF NOT EXISTS idx_logs_key_date 
                ON request_logs (virtual_key, created_at);
                """)

                conn.execute("""
                CREATE TABLE IF NOT EXISTS cache_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    virtual_key TEXT NOT NULL,
                    model TEXT NOT NULL,
                    prompt_text TEXT NOT NULL,
                    embedding_json TEXT,
                    response_json TEXT NOT NULL,
                    hit_count INTEGER DEFAULT 0,
                    created_at TEXT NOT NULL
                );
                """)

                conn.execute("""
                CREATE INDEX IF NOT EXISTS idx_cache_key 
                ON cache_entries (virtual_key, model);
                """)

                # Seed tenants from seed_keys.json
                for tenant in SEED_KEYS_RAW.get("tenants", []):
                    key = tenant["virtual_key"]
                    allowlist_str = json.dumps(tenant.get("model_allowlist", []))
                    cache_cfg = tenant.get("semantic_cache", {})
                    cache_enabled = 1 if cache_cfg.get("enabled", False) else 0
                    sim_thresh = cache_cfg.get("similarity_threshold")
                    rpm = tenant.get("rate_limit", {}).get("requests_per_minute", 60)
                    tpm = tenant.get("rate_limit", {}).get("tokens_per_minute", 100000)
                    now_str = datetime.now(timezone.utc).isoformat()

                    # Insert or ignore to keep spend across restarts unless reset
                    conn.execute("""
                    INSERT INTO tenants (
                        virtual_key, team, monthly_budget_usd, rpm_limit, tpm_limit,
                        model_allowlist, cache_enabled, similarity_threshold,
                        current_spend_usd, status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0.0, 'active', ?)
                    ON CONFLICT(virtual_key) DO UPDATE SET
                        team=excluded.team,
                        monthly_budget_usd=excluded.monthly_budget_usd,
                        rpm_limit=excluded.rpm_limit,
                        tpm_limit=excluded.tpm_limit,
                        model_allowlist=excluded.model_allowlist,
                        cache_enabled=excluded.cache_enabled,
                        similarity_threshold=excluded.similarity_threshold;
                    """, (
                        key, tenant["team"], tenant["monthly_budget_usd"], rpm, tpm,
                        allowlist_str, cache_enabled, sim_thresh, now_str
                    ))
        finally:
            conn.close()


def get_tenant(virtual_key: str) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    try:
        cur = conn.execute("SELECT * FROM tenants WHERE virtual_key = ?", (virtual_key,))
        row = cur.fetchone()
        if not row:
            return None
        d = dict(row)
        d["model_allowlist"] = json.loads(d["model_allowlist"])
        d["cache_enabled"] = bool(d["cache_enabled"])
        return d
    finally:
        conn.close()


def get_all_tenants() -> List[Dict[str, Any]]:
    conn = get_connection()
    try:
        cur = conn.execute("SELECT * FROM tenants")
        results = []
        for row in cur.fetchall():
            d = dict(row)
            d["model_allowlist"] = json.loads(d["model_allowlist"])
            d["cache_enabled"] = bool(d["cache_enabled"])
            results.append(d)
        return results
    finally:
        conn.close()


def atomic_increment_spend(virtual_key: str, cost_usd: float) -> float:
    """Atomically increment a tenant's spend and return the new total."""
    with _LOCK:
        conn = get_connection()
        try:
            with conn:
                conn.execute(
                    "UPDATE tenants SET current_spend_usd = round(current_spend_usd + ?, 6) WHERE virtual_key = ?",
                    (cost_usd, virtual_key)
                )
                cur = conn.execute(
                    "SELECT current_spend_usd FROM tenants WHERE virtual_key = ?",
                    (virtual_key,)
                )
                row = cur.fetchone()
                return row["current_spend_usd"] if row else cost_usd
        finally:
            conn.close()


def record_request_log(log_data: Dict[str, Any]) -> None:
    """Record an immutable request log entry."""
    conn = get_connection()
    try:
        with conn:
            conn.execute("""
            INSERT INTO request_logs (
                request_id, virtual_key, requested_model, resolved_provider,
                resolved_model, status, prompt_tokens, completion_tokens,
                cost_usd, cache, fallback, route_reason, retries, latency_ms, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                log_data["request_id"],
                log_data["virtual_key"],
                log_data["requested_model"],
                log_data.get("resolved_provider"),
                log_data.get("resolved_model"),
                log_data["status"],
                log_data.get("prompt_tokens", 0),
                log_data.get("completion_tokens", 0),
                log_data.get("cost_usd", 0.0),
                log_data.get("cache", "miss"),
                1 if log_data.get("fallback") else 0,
                log_data.get("route_reason"),
                log_data.get("retries", 0),
                log_data.get("latency_ms", 0),
                log_data.get("created_at", datetime.now(timezone.utc).isoformat())
            ))
    finally:
        conn.close()


def get_usage_summary(virtual_key: str, from_date: Optional[str] = None, to_date: Optional[str] = None) -> Dict[str, Any]:
    """Calculate spend and token totals for a key over a window."""
    conn = get_connection()
    try:
        query = "SELECT * FROM request_logs WHERE virtual_key = ?"
        params = [virtual_key]
        if from_date:
            query += " AND created_at >= ?"
            params.append(from_date)
        if to_date:
            query += " AND created_at <= ?"
            params.append(to_date)

        cur = conn.execute(query, params)
        rows = cur.fetchall()

        # Count only accepted 200 requests for client reconciliation matching load_test.py
        accepted_rows = [r for r in rows if r["status"] in ("ok", "cache_hit", 200, "200")]
        requests_count = len(accepted_rows)
        prompt_tokens = sum(r["prompt_tokens"] for r in accepted_rows)
        completion_tokens = sum(r["completion_tokens"] for r in accepted_rows)
        cost_usd = round(sum(r["cost_usd"] for r in accepted_rows), 6)
        cache_hits = sum(1 for r in rows if r["cache"] == "hit")

        return {
            "key": virtual_key,
            "from": from_date,
            "to": to_date,
            "requests": requests_count,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "cost_usd": cost_usd,
            "cache_hits": cache_hits
        }
    finally:
        conn.close()


def get_recent_logs(virtual_key: Optional[str] = None, limit: int = 50) -> List[Dict[str, Any]]:
    conn = get_connection()
    try:
        if virtual_key:
            cur = conn.execute(
                "SELECT * FROM request_logs WHERE virtual_key = ? ORDER BY created_at DESC LIMIT ?",
                (virtual_key, limit)
            )
        else:
            cur = conn.execute(
                "SELECT * FROM request_logs ORDER BY created_at DESC LIMIT ?",
                (limit,)
            )
        return [dict(r) for r in cur.fetchall()]
    finally:
        conn.close()
