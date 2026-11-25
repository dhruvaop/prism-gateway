package com.dhruva.airtribe.prism;

/**
 * Disabled, capability-less adapter used only when an unknown provider name is
 * requested. It owns no routes and is never offered in admin profiles, but it
 * lets the gateway answer metadata questions without special cases in the
 * registry.
 */
final class GenericOpenAiAdapter extends OpenAiCompatibleAdapter {
    @Override public String id() { return "openai-compatible"; }
    @Override public String label() { return "OpenAI-compatible"; }
    @Override public int order() { return 100; }
    @Override public boolean enabled() { return false; }
    @Override public String apiKey() { return ""; }
    @Override public String baseUrl() { return ""; }
    @Override public String model() { return ""; }
    @Override public boolean preferAsPrimary() { return false; }
}
