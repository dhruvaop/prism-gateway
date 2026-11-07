CREATE TABLE tenant_keys (
    virtual_key TEXT PRIMARY KEY,
    team TEXT NOT NULL,
    monthly_budget_usd DOUBLE PRECISION NOT NULL,
    requests_per_minute INTEGER NOT NULL,
    tokens_per_minute INTEGER NOT NULL,
    model_allowlist TEXT NOT NULL,
    cache_enabled INTEGER NOT NULL,
    similarity_threshold DOUBLE PRECISION NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1
);
