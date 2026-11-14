#!/usr/bin/env python3
"""Reset a Prism administrator password.

Updates the BCrypt hash in Supabase and, when present, the local SQLite
database so local and deployed sign-ins stay in sync. The new password is read
from a hidden prompt and is never written to disk or printed.

Usage:
    python scripts/set_admin_password.py you@example.com

Install the one-time dependency first:
    python -m pip install bcrypt
"""
from __future__ import annotations

import argparse
import getpass
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
        raise ValueError("DATABASE_URL must be a PostgreSQL JDBC URL")
    parsed = urlsplit(url)
    query = parse_qs(parsed.query)
    return {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "dbname": parsed.path.lstrip("/"),
        "user": env("DATABASE_USERNAME") or unquote((query.get("user") or [None])[0] or parsed.username or ""),
        "password": env("DATABASE_PASSWORD") or unquote((query.get("password") or [None])[0] or parsed.password or ""),
        "sslmode": (query.get("sslmode") or ["require"])[0],
        "connect_timeout": 15,
    }


def read_password() -> str:
    password = getpass.getpass("New admin password: ")
    if not 8 <= len(password) <= 72:
        raise ValueError("Password must be between 8 and 72 characters.")
    if password != getpass.getpass("Confirm new password: "):
        raise ValueError("Passwords do not match.")
    return password


def main() -> int:
    parser = argparse.ArgumentParser(description="Reset a Prism administrator password.")
    parser.add_argument("account", help="Administrator email or username")
    parser.add_argument("--skip-local", action="store_true", help="Do not update prism-java.db")
    args = parser.parse_args()

    try:
        import bcrypt
    except ImportError as exc:
        raise RuntimeError("Install the dependency with: python -m pip install bcrypt") from exc
    try:
        import psycopg
    except ImportError as exc:
        raise RuntimeError("Install the dependency with: python -m pip install 'psycopg[binary]'") from exc

    password = read_password()
    password_hash = bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=12, prefix=b"2a")).decode("ascii")

    with psycopg.connect(**postgres_options()) as target:
        with target.cursor() as cur:
            cur.execute(
                "UPDATE auth_users SET password_hash=%s "
                "WHERE lower(email)=lower(%s) OR lower(username)=lower(%s) "
                "RETURNING id, email, username",
                (password_hash, args.account, args.account),
            )
            rows = cur.fetchall()
            if len(rows) != 1:
                raise RuntimeError(f"Expected exactly one administrator for {args.account!r}, found {len(rows)}.")
            user_id, email, username = rows[0]
        target.commit()
    print(f"Supabase password updated for {username} ({email}).")

    local_db = ROOT / "prism-java.db"
    if not args.skip_local and local_db.exists():
        with sqlite3.connect(local_db) as local:
            cur = local.execute(
                "UPDATE auth_users SET password_hash=? WHERE id=?",
                (password_hash, user_id),
            )
            if cur.rowcount != 1:
                raise RuntimeError("Supabase was updated, but the local SQLite account was not found.")
        print("Local SQLite password updated too.")

    print("Sign in with the new password. The password was not stored or printed.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Password reset failed: {error}", file=sys.stderr)
        raise SystemExit(1)
