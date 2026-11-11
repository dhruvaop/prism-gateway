-- Free demo metering, Razorpay subscriptions, and webhook idempotency.
ALTER TABLE access_users ADD COLUMN trial_requests_used INTEGER NOT NULL DEFAULT 0;
ALTER TABLE access_users ADD COLUMN trial_webhook_sent_at TEXT;
-- Existing and admin-provisioned accounts are not paywalled; only public self-signups are.
ALTER TABLE access_users ADD COLUMN self_signup INTEGER NOT NULL DEFAULT 0;

CREATE TABLE subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES access_users(id) ON DELETE CASCADE,
    plan TEXT NOT NULL,
    status TEXT NOT NULL,
    amount_paise BIGINT NOT NULL,
    currency TEXT NOT NULL,
    razorpay_order_id TEXT UNIQUE,
    razorpay_payment_id TEXT UNIQUE,
    starts_at TEXT,
    ends_at TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX subscriptions_user_idx ON subscriptions(user_id, status);

CREATE TABLE billing_events (
    id BIGSERIAL PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE,
    provider TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO teams(name, plan_id)
SELECT 'Self-serve', id FROM plans WHERE name = 'Default'
AND NOT EXISTS (SELECT 1 FROM teams WHERE name = 'Self-serve');
