-- Password reset support for chat users and console administrators.
ALTER TABLE access_users ADD COLUMN email TEXT;

CREATE UNIQUE INDEX access_users_email_idx ON access_users(lower(email));

CREATE TABLE password_resets (
    id BIGSERIAL PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    user_type TEXT NOT NULL,
    user_id TEXT NOT NULL,
    email TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    used_at TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX password_resets_user_idx ON password_resets(user_type, user_id);
CREATE INDEX password_resets_expires_idx ON password_resets(expires_at);
