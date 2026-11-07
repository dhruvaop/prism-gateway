CREATE TABLE request_logs (
    request_id TEXT PRIMARY KEY,
    virtual_key TEXT,
    team TEXT,
    requested_model TEXT,
    resolved_provider TEXT,
    resolved_model TEXT,
    status TEXT NOT NULL,
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    cache TEXT,
    fallback INTEGER NOT NULL DEFAULT 0,
    route_reason TEXT,
    retries INTEGER NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
CREATE INDEX request_logs_created_at_idx ON request_logs(created_at DESC);
CREATE INDEX request_logs_key_created_at_idx ON request_logs(virtual_key, created_at DESC);

CREATE TABLE usage_monthly (
    virtual_key TEXT NOT NULL,
    month TEXT NOT NULL,
    team TEXT,
    requests BIGINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    cache_hits BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (virtual_key, month)
);

CREATE TABLE cache_entries (
    id BIGSERIAL PRIMARY KEY,
    virtual_key TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt TEXT NOT NULL,
    response TEXT NOT NULL,
    created_at TEXT NOT NULL,
    hit_count BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX cache_entries_tenant_model_idx ON cache_entries(virtual_key, model, id DESC);
