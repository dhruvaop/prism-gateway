package com.dhruva.airtribe.prism;

import org.springframework.stereotype.Component;

/** Local canned providers loaded from gateway_config.sample.json. */
@Component
public final class MockAdapter implements ProviderAdapter {
    @Override public String id() { return "mock"; }
    @Override public String label() { return "Local mock providers"; }
    @Override public int order() { return 99; }
    @Override public boolean enabled() { return true; }
    @Override public String baseUrl() { return ""; }
    @Override public String apiKey() { return ""; }
    @Override public String model() { return "alpha-small"; }
    @Override public boolean preferAsPrimary() { return false; }
    @Override public boolean searchOnline() { return false; }
    @Override public java.util.Map<String, String> taskModels() { return java.util.Map.of(); }
    @Override public String endpoint(String baseUrl) { return baseUrl + "/chat/completions"; }
    @Override public java.util.Map<String, String> headers(String apiKey) {
        return java.util.Map.of("Content-Type", "application/json");
    }
    @Override public void prepareRequest(java.util.Map<String, Object> outbound, boolean stream) { }
    @Override public boolean trackHealth(String route) { return true; }
    @Override public ModelIdentity identify(String upstreamModel) {
        if (upstreamModel == null || !(upstreamModel.startsWith("alpha") || upstreamModel.startsWith("beta"))) return null;
        return new ModelIdentity("Prism mock provider", "Prism mock", null);
    }
    @Override public void register(ProviderRegistrar registrar) { }
}
