-- Admission and provider health must be shared by every gateway replica.
-- A process-local counter lets each replica admit a full quota and route
-- around a provider another replica already marked as degraded.

CREATE TABLE IF NOT EXISTS admission_counters (
    key_ref      TEXT    NOT NULL,
    kind         TEXT    NOT NULL,
    window_start BIGINT  NOT NULL,
    used         BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (key_ref, kind, window_start)
);

CREATE INDEX IF NOT EXISTS admission_counters_window_idx
    ON admission_counters (kind, window_start);

CREATE TABLE IF NOT EXISTS admission_leases (
    key_ref    TEXT PRIMARY KEY,
    owner      TEXT   NOT NULL,
    expires_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS admission_leases_expiry_idx
    ON admission_leases (expires_at);

CREATE TABLE IF NOT EXISTS provider_health_samples (
    provider   TEXT   NOT NULL,
    at_ms      BIGINT NOT NULL,
    success    INTEGER NOT NULL,
    latency_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS provider_health_provider_at_idx
    ON provider_health_samples (provider, at_ms);
