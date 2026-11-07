#!/usr/bin/env python3
"""Copy Prism's local SQLite runtime data into an already-migrated PostgreSQL DB.

The destination must be a fresh Prism database (the four seeded tenant rows are
allowed). Flyway owns schema creation; this script copies rows only.
"""
from __future__ import annotations

import os
import sqlite3
import sys
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def env(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    if value is not None:
        return value
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, item = line.split("=", 1)
                if key.strip() == name:
                    return item.strip().strip("\"'")
    return default


def postgres_options() -> dict[str, object]:
    url = env("DATABASE_URL")
    if url.startswith("jdbc:"):
        url = url[5:]
    if not url.startswith(("postgres://", "postgresql://")):
        raise ValueError("DATABASE_URL must be a PostgreSQL JDBC URL from the Supabase Connect dialog")
    parsed = urlsplit(url)
    query = parse_qs(parsed.query)
    return {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "dbname": parsed.path.lstrip("/"),
        "user": env("DATABASE_USERNAME") or unquote((query.get("user") or [None])[0] or parsed.username or ""),
        "password": env("DATABASE_PASSWORD") or unquote((query.get("password") or [None])[0] or parsed.password or ""),
        "sslmode": (query.get("sslmode") or ["require"])[0],
        "connect_timeout": 10,
    }


def rows(source: sqlite3.Connection, table: str) -> list[dict]:
    return [dict(row) for row in source.execute(f"SELECT * FROM {table}").fetchall()]


def main() -> int:
    sqlite_path = Path(env("PRISM_SQLITE_PATH", str(ROOT / "prism-java.db")))
    if not sqlite_path.is_absolute():
        sqlite_path = ROOT / sqlite_path
    if not sqlite_path.exists():
        raise FileNotFoundError(f"Local SQLite database not found: {sqlite_path}")

    try:
        import psycopg
    except ImportError as exc:
        raise RuntimeError("Install the one-time migration dependency with: python -m pip install -r requirements-migrate.txt") from exc

    source = sqlite3.connect(sqlite_path)
    source.row_factory = sqlite3.Row
    try:
        data = {table: rows(source, table) for table in ("tenant_keys", "request_logs", "usage_monthly", "cache_entries")}
    finally:
        source.close()

    with psycopg.connect(**postgres_options()) as target:
        with target.cursor() as cur:
            # The app startup/Flyway may have seeded tenants. Refuse to merge
            # runtime history into a non-empty destination to prevent duplicates.
            for table in ("request_logs", "usage_monthly", "cache_entries"):
                cur.execute(f"SELECT COUNT(*) FROM {table}")
                if cur.fetchone()[0] != 0:
                    raise RuntimeError(f"Destination table {table} is not empty; refusing to mix histories")

            for row in data["tenant_keys"]:
                cur.execute("""INSERT INTO tenant_keys(virtual_key,team,monthly_budget_usd,requests_per_minute,tokens_per_minute,model_allowlist,cache_enabled,similarity_threshold,enabled)
                    VALUES(%(virtual_key)s,%(team)s,%(monthly_budget_usd)s,%(requests_per_minute)s,%(tokens_per_minute)s,%(model_allowlist)s,%(cache_enabled)s,%(similarity_threshold)s,%(enabled)s)
                    ON CONFLICT(virtual_key) DO UPDATE SET team=excluded.team,monthly_budget_usd=excluded.monthly_budget_usd,requests_per_minute=excluded.requests_per_minute,tokens_per_minute=excluded.tokens_per_minute,model_allowlist=excluded.model_allowlist,cache_enabled=excluded.cache_enabled,similarity_threshold=excluded.similarity_threshold,enabled=excluded.enabled""", row)
            for row in data["request_logs"]:
                columns = list(row)
                names = ",".join(columns)
                binds = ",".join(f"%({name})s" for name in columns)
                updates = ",".join(f"{name}=excluded.{name}" for name in columns if name != "request_id")
                cur.execute(f"INSERT INTO request_logs({names}) VALUES({binds}) ON CONFLICT(request_id) DO UPDATE SET {updates}", row)
            for row in data["usage_monthly"]:
                cur.execute("""INSERT INTO usage_monthly(virtual_key,month,team,requests,prompt_tokens,completion_tokens,cost_usd,cache_hits)
                    VALUES(%(virtual_key)s,%(month)s,%(team)s,%(requests)s,%(prompt_tokens)s,%(completion_tokens)s,%(cost_usd)s,%(cache_hits)s)
                    ON CONFLICT(virtual_key,month) DO UPDATE SET team=excluded.team,requests=excluded.requests,prompt_tokens=excluded.prompt_tokens,completion_tokens=excluded.completion_tokens,cost_usd=excluded.cost_usd,cache_hits=excluded.cache_hits""", row)
            for row in data["cache_entries"]:
                cur.execute("""INSERT INTO cache_entries(id,virtual_key,model,prompt,response,created_at,hit_count)
                    VALUES(%(id)s,%(virtual_key)s,%(model)s,%(prompt)s,%(response)s,%(created_at)s,%(hit_count)s)""", row)
            cur.execute("SELECT setval(pg_get_serial_sequence('cache_entries','id'), GREATEST(COALESCE((SELECT MAX(id) FROM cache_entries),1),1), EXISTS(SELECT 1 FROM cache_entries))")

    for table, records in data.items():
        print(f"{table}: imported {len(records)} rows")
    print("SQLite runtime data copied successfully. Local source database was left unchanged.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Migration failed: {error}", file=sys.stderr)
        raise SystemExit(1)
