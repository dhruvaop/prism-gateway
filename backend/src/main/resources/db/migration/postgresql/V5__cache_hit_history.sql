CREATE TABLE cache_hits (
    id BIGSERIAL PRIMARY KEY,
    virtual_key TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt TEXT NOT NULL,
    similarity DOUBLE PRECISION NOT NULL,
    cache_entry_id BIGINT,
    created_at TEXT NOT NULL
);

CREATE INDEX cache_hits_key_created_idx ON cache_hits(virtual_key, created_at DESC);
