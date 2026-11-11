"""Prism Gateway - User Authentication & Session Security.
Provides cryptographically secure password hashing (PBKDF2-HMAC-SHA256 with 100,000 iterations),
timing-attack resistant password verification, and tokenized session management.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Optional, Tuple


def generate_salt(length: int = 16) -> str:
    """Generate a cryptographically secure random hexadecimal salt."""
    return secrets.token_hex(length)


def hash_password(password: str, salt: Optional[str] = None) -> Tuple[str, str]:
    """Hash a plaintext password with PBKDF2-HMAC-SHA256 and a 16-byte random salt.
    Returns (hex_hash, salt).
    """
    if not salt:
        salt = generate_salt()
    
    key = hashlib.pbkdf2_hmac(
        hash_name="sha256",
        password=password.encode("utf-8"),
        salt=salt.encode("utf-8"),
        iterations=100_000,
    )
    return key.hex(), salt


def verify_password(password: str, stored_hash: str, salt: str) -> bool:
    """Verify a plaintext password against the stored hash and salt using constant-time comparison."""
    computed_hash, _ = hash_password(password, salt)
    return hmac.compare_digest(computed_hash, stored_hash)


def generate_session_token() -> str:
    """Generate a high-entropy URL-safe session token."""
    return secrets.token_urlsafe(32)


def get_session_expiry(hours: int = 72) -> str:
    """Return an ISO 8601 UTC timestamp for session expiry."""
    expiry = datetime.now(timezone.utc) + timedelta(hours=hours)
    return expiry.isoformat()


def is_session_expired(expires_at_iso: str) -> bool:
    """Check if an ISO 8601 timestamp is in the past."""
    try:
        expires_at = datetime.fromisoformat(expires_at_iso)
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        return datetime.now(timezone.utc) > expires_at
    except Exception:
        return True
