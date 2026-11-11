-- Per-virtual-key routing middleware.
-- Each key can pin its classification/routing to a provider profile so two keys
-- on the same gateway can resolve to different upstreams (OpenRouter/Nemotron,
-- Gemini, OpenAI, or the local mocks) without sharing global configuration.
ALTER TABLE tenant_keys ADD COLUMN routing_profile TEXT NOT NULL DEFAULT 'auto';

-- Keep the seeded demo tenants varied so the admin console demonstrates that
-- routing is decided per key rather than per process.
UPDATE tenant_keys SET routing_profile = 'openrouter' WHERE team = 'search';
UPDATE tenant_keys SET routing_profile = 'gemini' WHERE team = 'research';
UPDATE tenant_keys SET routing_profile = 'mock' WHERE team IN ('free-tier', 'budget-demo');
