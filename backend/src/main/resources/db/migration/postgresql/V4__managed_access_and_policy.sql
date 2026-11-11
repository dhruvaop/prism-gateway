CREATE TABLE plans (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    monthly_budget_usd DOUBLE PRECISION NOT NULL,
    requests_per_minute INTEGER NOT NULL,
    tokens_per_minute INTEGER,
    token_cap BIGINT,
    model_allowlist TEXT NOT NULL,
    verify_models TEXT NOT NULL DEFAULT '[]',
    cache_enabled INTEGER NOT NULL DEFAULT 1,
    similarity_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.92,
    cache_ttl_seconds BIGINT,
    guardrail_injection TEXT NOT NULL DEFAULT 'block',
    guardrail_pii TEXT NOT NULL DEFAULT 'flag'
);

CREATE TABLE teams (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    plan_id BIGINT NOT NULL REFERENCES plans(id)
);

CREATE TABLE access_users (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    team_id BIGINT NOT NULL REFERENCES teams(id),
    access_mode TEXT NOT NULL DEFAULT 'both',
    virtual_key TEXT UNIQUE,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);

ALTER TABLE tenant_keys ADD COLUMN user_id BIGINT REFERENCES access_users(id);
ALTER TABLE tenant_keys ADD COLUMN token_cap BIGINT;
ALTER TABLE tenant_keys ADD COLUMN cache_ttl_seconds BIGINT;
ALTER TABLE tenant_keys ADD COLUMN guardrail_injection TEXT NOT NULL DEFAULT 'off';
ALTER TABLE tenant_keys ADD COLUMN guardrail_pii TEXT NOT NULL DEFAULT 'off';
ALTER TABLE tenant_keys ADD COLUMN verify_models TEXT NOT NULL DEFAULT '[]';

CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES access_users(id) ON DELETE CASCADE,
    title TEXT NOT NULL DEFAULT 'New conversation',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE conversation_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX conversations_user_updated_idx ON conversations(user_id, updated_at DESC);
CREATE INDEX conversation_messages_conversation_idx ON conversation_messages(conversation_id, id);

INSERT INTO plans(name, monthly_budget_usd, requests_per_minute, tokens_per_minute,
                  model_allowlist, verify_models, cache_enabled, similarity_threshold,
                  guardrail_injection, guardrail_pii)
VALUES ('Default', 50, 60, 0, '["fast","smart","auto"]', '[]', 1, 0.92, 'off', 'off');

INSERT INTO teams(name, plan_id)
SELECT 'Platform', id FROM plans WHERE name = 'Default';
