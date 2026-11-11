#!/usr/bin/env python3
"""Keep the local SQLite dev database and PostgreSQL/Supabase aligned.

PostgreSQL is the production source of truth. SQLite is the local demo/dev
database. This script makes the relationship explicit instead of letting the two
drift silently.

Modes
-----
    --check          Report schema and row drift. Read-only. Default.
    --repair-sqlite  Add columns that exist in PostgreSQL but not in SQLite.
    --pull           Copy PostgreSQL -> SQLite (production mirror for local dev).
    --push           Copy SQLite -> PostgreSQL (explicit local-history import).

Safety
------
* Never deletes a row in either database.
* Never rewrites an existing admin password hash unless --allow-password-change.
* --push refuses to run when PostgreSQL contains rows the local database does
  not, unless --force-push is given, so production history cannot be replaced
  by an older local snapshot.
* Output never prints a password, hash, token, or connection secret.

Examples
--------
    python scripts/sync_databases.py --check
    python scripts/sync_databases.py --pull --dry-run
    python scripts/sync_databases.py --pull
    python scripts/sync_databases.py --push --dry-run
"""
from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class Table:
    name: str
    key: tuple[str, ...]
    # Columns that are database-generated and must not be copied on insert.
    generated: tuple[str, ...] = ()
    # Columns that are secrets and are never printed.
    secret: tuple[str, ...] = field(default_factory=tuple)
    # Cache data is environment-local and must never cross databases.
    environment_local: bool = False


TABLES: tuple[Table, ...] = (
    Table("plans", ("name",), generated=("id",)),
    Table("teams", ("name",), generated=("id",)),
    Table("auth_users", ("id",), secret=("password_hash",)),
    Table("access_users", ("username",), generated=("id",), secret=("password_hash",)),
    Table("tenant_keys", ("virtual_key",)),
    Table("request_logs", ("request_id",)),
    Table("usage_monthly", ("virtual_key", "month")),
    Table("cache_entries", ("id",), environment_local=True),
    Table("cache_hits", ("id",), environment_local=True),
)

SYNCED_TABLES = tuple(table for table in TABLES if not table.environment_local)

SECRET_COLUMNS = {column for table in TABLES for column in table.secret}


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
        raise ValueError("DATABASE_URL must be a PostgreSQL URL; refusing to sync without it")
    parsed = urlsplit(url)
    query = parse_qs(parsed.query)
    return {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "dbname": parsed.path.lstrip("/"),
        "user": env("DATABASE_USERNAME") or unquote((query.get("user") or [None])[0] or parsed.username or ""),
        "password": env("DATABASE_PASSWORD") or unquote((query.get("password") or [None])[0] or parsed.password or ""),
        "sslmode": (query.get("sslmode") or ["require"])[0],
        "connect_timeout": 20,
    }


def sqlite_path() -> Path:
    path = Path(env("PRISM_SQLITE_PATH", str(ROOT / "prism-java.db")))
    if not path.is_absolute():
        path = ROOT / path
    if not path.exists():
        raise FileNotFoundError(f"Local SQLite database not found: {path}")
    return path


# --------------------------------------------------------------------------
# Introspection
# --------------------------------------------------------------------------

def sqlite_columns(conn: sqlite3.Connection, table: str) -> list[str]:
    return [row[1] for row in conn.execute(f"PRAGMA table_info({quote(table)})")]


def postgres_columns(conn, table: str) -> list[str]:
    rows = conn.execute(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema='public' AND table_name=%s ORDER BY ordinal_position",
        (table,),
    ).fetchall()
    return [row[0] for row in rows]


def quote(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def sqlite_count(conn: sqlite3.Connection, table: str) -> int:
    try:
        return conn.execute(f"SELECT COUNT(*) FROM {quote(table)}").fetchone()[0]
    except sqlite3.Error:
        return -1


def postgres_count(conn, table: str) -> int:
    try:
        return conn.execute(f"SELECT COUNT(*) FROM {quote(table)}").fetchone()[0]
    except Exception:
        return -1


# --------------------------------------------------------------------------
# Mapping helpers (foreign keys differ between autoincrement and serial)
# --------------------------------------------------------------------------

def sqlite_name_map(conn: sqlite3.Connection, table: str) -> dict[str, int]:
    try:
        return {str(name): int(pk) for pk, name in conn.execute(f"SELECT id, name FROM {quote(table)}")}
    except sqlite3.Error:
        return {}


def postgres_name_map(conn, table: str) -> dict[str, int]:
    try:
        return {str(name): int(pk) for pk, name in conn.execute(f"SELECT id, name FROM {quote(table)}").fetchall()}
    except Exception:
        return {}


def remap(rows: list[dict], column: str, source: dict[str, int], target: dict[str, int], name_by_id: dict[int, str]) -> None:
    """Translate a foreign key through its natural-key name."""
    for row in rows:
        current = row.get(column)
        if current is None:
            continue
        name = name_by_id.get(int(current))
        if name is not None and name in target:
            row[column] = target[name]


# --------------------------------------------------------------------------
# Reading / writing
# --------------------------------------------------------------------------

def read_sqlite(conn: sqlite3.Connection, table: Table) -> list[dict]:
    try:
        cursor = conn.execute(f"SELECT * FROM {quote(table.name)}")
    except sqlite3.Error:
        return []
    return [dict(row) for row in cursor.fetchall()]


def read_postgres(conn, table: Table) -> list[dict]:
    try:
        cursor = conn.execute(f"SELECT * FROM {quote(table.name)}")
    except Exception:
        return []
    columns = [column.name for column in cursor.description]
    return [dict(zip(columns, row)) for row in cursor.fetchall()]


def common_columns(source: list[str], target: list[str], table: Table, inserting: bool) -> list[str]:
    columns = [column for column in source if column in target]
    if inserting:
        columns = [column for column in columns if column not in table.generated]
    return columns


def upsert_sqlite(conn: sqlite3.Connection, table: Table, rows: list[dict], target_columns: list[str]) -> int:
    if not rows:
        return 0
    columns = common_columns(list(rows[0].keys()), target_columns, table, inserting=True)
    if not columns:
        return 0
    names = ",".join(quote(column) for column in columns)
    binds = ",".join("?" for _ in columns)
    updates = ",".join(f"{quote(column)}=excluded.{quote(column)}" for column in columns if column not in table.key)
    conflict = ",".join(quote(column) for column in table.key)
    sql = f"INSERT INTO {quote(table.name)}({names}) VALUES({binds}) ON CONFLICT({conflict}) DO UPDATE SET {updates}"
    changed = 0
    for row in rows:
        values = [row.get(column) for column in columns]
        if all(value is None for value in values):
            continue
        conn.execute(sql, values)
        changed += 1
    return changed


def upsert_postgres(conn, table: Table, rows: list[dict], target_columns: list[str], allow_password_change: bool) -> int:
    if not rows:
        return 0
    columns = common_columns(list(rows[0].keys()), target_columns, table, inserting=True)
    if not columns:
        return 0
    names = ",".join(quote(column) for column in columns)
    binds = ",".join(f"%({column})s" for column in columns)
    updates = ",".join(f"{quote(column)}=excluded.{quote(column)}" for column in columns if column not in table.key)
    if not allow_password_change:
        updates = ",".join(
            part for part in updates.split(",") if not part.startswith(quote("password_hash"))
        ) or f"{quote(table.key[0])}={quote(table.key[0])}"
    conflict = ",".join(quote(column) for column in table.key)
    sql = f"INSERT INTO {quote(table.name)}({names}) VALUES({binds}) ON CONFLICT({conflict}) DO UPDATE SET {updates}"
    changed = 0
    with conn.cursor() as cur:
        for row in rows:
            if all(row.get(column) is None for column in columns):
                continue
            cur.execute(sql, {column: row.get(column) for column in columns})
            changed += 1
    return changed


# --------------------------------------------------------------------------
# Modes
# --------------------------------------------------------------------------

def key_of(row: dict, table: Table) -> tuple[str, ...]:
    return tuple(str(row.get(column)) for column in table.key)


def check(postgres, sqlite: sqlite3.Connection) -> int:
    print("DATABASE PARITY  (PostgreSQL = production source of truth)")
    print(f"{'table':<20}{'postgres':>10}{'sqlite':>10}   status")
    drift = 0
    for table in TABLES:
        pg_rows = read_postgres(postgres, table)
        lite_rows = read_sqlite(sqlite, table)
        pg_cols = set(postgres_columns(postgres, table.name))
        lite_cols = set(sqlite_columns(sqlite, table.name))
        missing_local_cols = sorted(pg_cols - lite_cols)
        missing_remote_cols = sorted(lite_cols - pg_cols)
        pg_keys = {key_of(row, table) for row in pg_rows}
        lite_keys = {key_of(row, table) for row in lite_rows}
        missing_local = pg_keys - lite_keys
        local_only = lite_keys - pg_keys

        status = "ok"
        if table.environment_local:
            status = "local-only (not synced)"
        elif missing_local:
            status = f"{len(missing_local)} production row(s) missing locally"
            drift += 1
        elif local_only:
            status = f"ok (+{len(local_only)} local-only)"
        if missing_local_cols or missing_remote_cols:
            status = "schema differs"
            drift += 1

        print(f"{table.name:<20}{len(pg_rows):>10}{len(lite_rows):>10}   {status}")
        if missing_local_cols:
            print(f"    missing in sqlite: {', '.join(missing_local_cols)}")
        if missing_remote_cols:
            print(f"    missing in postgres: {', '.join(missing_remote_cols)}")
        if missing_local and not table.environment_local:
            sample = sorted(missing_local)[:3]
            print(f"    missing locally (first 3): {sample}")
    print()
    if drift:
        print(f"Drift detected in {drift} table(s).")
        print("  --pull  mirror PostgreSQL into SQLite (recommended)")
        print("  --push  import local SQLite history into PostgreSQL")
        return 1
    print("Databases are aligned.")
    return 0


def postgres_column_defs(conn, table: str) -> list[tuple[str, str, str, str | None]]:
    return conn.execute(
        "SELECT column_name, data_type, is_nullable, column_default "
        "FROM information_schema.columns "
        "WHERE table_schema='public' AND table_name=%s ORDER BY ordinal_position",
        (table,),
    ).fetchall()


SQLITE_TYPES = {
    "text": "TEXT",
    "character varying": "TEXT",
    "uuid": "TEXT",
    "json": "TEXT",
    "jsonb": "TEXT",
    "integer": "INTEGER",
    "bigint": "INTEGER",
    "smallint": "INTEGER",
    "boolean": "INTEGER",
    "double precision": "REAL",
    "real": "REAL",
    "numeric": "REAL",
    "timestamp with time zone": "TEXT",
    "timestamp without time zone": "TEXT",
    "date": "TEXT",
}


def literal_default(value: str | None) -> str | None:
    """Return a SQLite-safe literal default, or None for generated defaults."""
    if not value:
        return None
    value = value.strip()
    if value.startswith("nextval("):
        return None
    if "::" in value:
        value = value.split("::", 1)[0].strip()
    if value.lower() in ("true", "false"):
        return "1" if value.lower() == "true" else "0"
    if value.startswith("'") and value.endswith("'"):
        return value
    try:
        float(value)
        return value
    except ValueError:
        return None


def repair_sqlite(postgres, sqlite: sqlite3.Connection, dry_run: bool) -> int:
    """Additively align the SQLite schema with PostgreSQL. Never drops or alters."""
    added = 0
    for table in TABLES:
        existing = {column.lower() for column in sqlite_columns(sqlite, table.name)}
        if not existing:
            print(f"{table.name}: table missing in sqlite; run the app once to create it")
            continue
        for name, data_type, nullable, default in postgres_column_defs(postgres, table.name):
            if name.lower() in existing:
                continue
            sqlite_type = SQLITE_TYPES.get(data_type.lower(), "TEXT")
            clause = f"ALTER TABLE {quote(table.name)} ADD COLUMN {quote(name)} {sqlite_type}"
            literal = literal_default(default)
            if literal is not None:
                clause += f" DEFAULT {literal}"
            if nullable == "NO" and literal is not None:
                clause += " NOT NULL"
            if dry_run:
                print(f"{table.name}: would add {name} {sqlite_type}")
                continue
            try:
                sqlite.execute(clause)
                added += 1
                print(f"{table.name}: added {name} {sqlite_type}")
            except sqlite3.Error as error:
                print(f"{table.name}: could not add {name}: {error}")
    if not dry_run:
        sqlite.commit()
        print(f"\nSchema repair complete. {added} column(s) added. Nothing dropped.")
    return 0


def pull(postgres, sqlite: sqlite3.Connection, dry_run: bool) -> int:
    pg_plans = postgres_name_map(postgres, "plans")
    lite_plans = sqlite_name_map(sqlite, "plans")
    pg_teams = postgres_name_map(postgres, "teams")
    lite_teams = sqlite_name_map(sqlite, "teams")
    pg_plan_names = {value: key for key, value in pg_plans.items()}
    pg_team_names = {value: key for key, value in pg_teams.items()}

    total = 0
    for table in SYNCED_TABLES:
        rows = read_postgres(postgres, table)
        if table.name in ("plans", "teams"):
            rows = [{k: v for k, v in row.items() if k != "id"} for row in rows]
        if table.name == "teams":
            remap(rows, "plan_id", pg_plans, lite_plans, pg_plan_names)
        if table.name == "access_users":
            remap(rows, "team_id", pg_teams, lite_teams, pg_team_names)
        if table.name == "tenant_keys":
            pg_users = {str(name): int(pk) for pk, name in postgres.execute("SELECT id, username FROM access_users").fetchall()}
            lite_users = {str(name): int(pk) for pk, name in sqlite.execute("SELECT id, username FROM access_users")}
            pg_user_names = {value: key for key, value in pg_users.items()}
            remap(rows, "user_id", pg_users, lite_users, pg_user_names)
        target_columns = sqlite_columns(sqlite, table.name)
        if dry_run:
            print(f"{table.name}: would upsert {len(rows)} row(s) into sqlite")
            continue
        changed = upsert_sqlite(sqlite, table, rows, target_columns)
        total += changed
        print(f"{table.name}: upserted {changed} row(s) into sqlite")
    if not dry_run:
        sqlite.commit()
        print(f"\nPull complete. {total} row(s) written to sqlite. Nothing deleted.")
    return 0


def push(postgres, sqlite: sqlite3.Connection, dry_run: bool, allow_password_change: bool, force: bool) -> int:
    if not force:
        for table in SYNCED_TABLES:
            pg_count = postgres_count(postgres, table.name)
            lite_count = sqlite_count(sqlite, table.name)
            if pg_count > lite_count:
                print(
                    f"Refusing to push: postgres has {pg_count} {table.name} rows but sqlite has {lite_count}.\n"
                    "PostgreSQL is the source of truth. Use --pull, or --force-push if you are certain.",
                    file=sys.stderr,
                )
                return 2

    lite_plans = sqlite_name_map(sqlite, "plans")
    pg_plans = postgres_name_map(postgres, "plans")
    lite_teams = sqlite_name_map(sqlite, "teams")
    pg_teams = postgres_name_map(postgres, "teams")
    lite_plan_names = {value: key for key, value in lite_plans.items()}
    lite_team_names = {value: key for key, value in lite_teams.items()}

    total = 0
    for table in SYNCED_TABLES:
        rows = read_sqlite(sqlite, table)
        if table.name in ("plans", "teams"):
            rows = [{k: v for k, v in row.items() if k != "id"} for row in rows]
        if table.name == "teams":
            remap(rows, "plan_id", lite_plans, pg_plans, lite_plan_names)
        if table.name == "access_users":
            remap(rows, "team_id", lite_teams, pg_teams, lite_team_names)
        if table.name == "tenant_keys":
            lite_users = {str(name): int(pk) for pk, name in sqlite.execute("SELECT id, username FROM access_users")}
            pg_users = {str(name): int(pk) for pk, name in postgres.execute("SELECT id, username FROM access_users").fetchall()}
            lite_user_names = {value: key for key, value in lite_users.items()}
            remap(rows, "user_id", lite_users, pg_users, lite_user_names)
        target_columns = postgres_columns(postgres, table.name)
        if dry_run:
            print(f"{table.name}: would upsert {len(rows)} row(s) into postgres")
            continue
        changed = upsert_postgres(postgres, table, rows, target_columns, allow_password_change)
        total += changed
        print(f"{table.name}: upserted {changed} row(s) into postgres")
    if not dry_run:
        postgres.commit()
        print(f"\nPush complete. {total} row(s) written to postgres. Nothing deleted.")
        if not allow_password_change:
            print("Existing password hashes were preserved.")
    return 0


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Sync local SQLite and PostgreSQL/Supabase.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="report drift only (default)")
    mode.add_argument("--repair-sqlite", action="store_true", help="add missing columns to SQLite")
    mode.add_argument("--pull", action="store_true", help="PostgreSQL -> SQLite")
    mode.add_argument("--push", action="store_true", help="SQLite -> PostgreSQL")
    parser.add_argument("--dry-run", action="store_true", help="show changes without writing")
    parser.add_argument("--allow-password-change", action="store_true", help="permit password hash overwrites on push")
    parser.add_argument("--force-push", action="store_true", help="push even when PostgreSQL has more rows")
    args = parser.parse_args()

    try:
        import psycopg
    except ImportError:
        print("Install the sync dependency with: python -m pip install -r requirements-migrate.txt", file=sys.stderr)
        return 1

    sqlite = sqlite3.connect(sqlite_path())
    sqlite.row_factory = sqlite3.Row
    try:
        with psycopg.connect(**postgres_options()) as postgres:
            if args.repair_sqlite:
                return repair_sqlite(postgres, sqlite, args.dry_run)
            if args.pull:
                return pull(postgres, sqlite, args.dry_run)
            if args.push:
                return push(postgres, sqlite, args.dry_run, args.allow_password_change, args.force_push)
            return check(postgres, sqlite)
    finally:
        sqlite.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Sync failed: {error}", file=sys.stderr)
        raise SystemExit(1)
