"""Thread-safe, race-free sliding window rate limiter.
Guarantees zero over-admission under concurrent bursts as tested by scripts/load_test.py.
"""

from __future__ import annotations

import threading
import time
from typing import Dict, List, Tuple


class SlidingWindowRateLimiter:
    """Sliding-window rate limiter utilizing an atomic lock to eliminate read-then-write race conditions."""

    def __init__(self, window_seconds: float = 60.0):
        self.window_seconds = window_seconds
        self._requests: Dict[str, List[float]] = {}
        self._tokens: Dict[str, List[Tuple[float, int]]] = {}
        self._lock = threading.Lock()

    def acquire(self, key: str, rpm_limit: int, estimated_tokens: int = 0, tpm_limit: int = 1000000) -> Tuple[bool, str]:
        """Atomically check and record a request.
        Returns (allowed: bool, reason: str).
        """
        now = time.monotonic()
        cutoff = now - self.window_seconds

        with self._lock:
            # Clean expired request timestamps
            req_history = self._requests.get(key, [])
            valid_reqs = [t for t in req_history if t > cutoff]

            if len(valid_reqs) >= rpm_limit:
                self._requests[key] = valid_reqs
                return False, f"Rate limit of {rpm_limit} requests per minute exceeded"

            # Check TPM limit if configured
            tok_history = self._tokens.get(key, [])
            valid_toks = [(t, count) for (t, count) in tok_history if t > cutoff]
            current_tok_sum = sum(count for _, count in valid_toks)

            if current_tok_sum + estimated_tokens > tpm_limit:
                self._requests[key] = valid_reqs
                self._tokens[key] = valid_toks
                return False, f"Token rate limit of {tpm_limit} tokens per minute exceeded"

            # Record admission
            valid_reqs.append(now)
            self._requests[key] = valid_reqs
            valid_toks.append((now, estimated_tokens))
            self._tokens[key] = valid_toks
            return True, "ok"

    def record_actual_tokens(self, key: str, actual_tokens: int) -> None:
        """Update recent token count with actual tokens reported by provider."""
        with self._lock:
            tok_history = self._tokens.get(key, [])
            if tok_history:
                last_time, _ = tok_history[-1]
                tok_history[-1] = (last_time, actual_tokens)

    def reset(self, key: Optional[str] = None) -> None:
        """Reset rate limiter state for testing."""
        with self._lock:
            if key:
                self._requests.pop(key, None)
                self._tokens.pop(key, None)
            else:
                self._requests.clear()
                self._tokens.clear()


# Global singleton instance
RATE_LIMITER = SlidingWindowRateLimiter()
