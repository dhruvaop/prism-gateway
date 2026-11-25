package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Open/closed principle for providers: a new upstream is introduced by adding
 * one adapter. Routing, request shaping, metadata and the registry need no
 * changes to the gateway's orchestration code.
 */
class ProviderAdapterOcpTest {
    @Test void customAdapterIsResolvedForRoutingRequestShapingAndMetadata() {
        ProviderAdapter custom = new OpenAiCompatibleAdapter() {
            @Override public String id() { return "custom"; }
            @Override public String label() { return "Custom Labs"; }
            @Override public int order() { return 4; }
            @Override public boolean enabled() { return true; }
            @Override public String apiKey() { return "not-a-real-key"; }
            @Override public String baseUrl() { return "http://custom.example/v1"; }
            @Override public String model() { return "custom-special"; }
            @Override public boolean preferAsPrimary() { return true; }
            @Override public ModelIdentity identify(String upstreamModel) {
                return upstreamModel != null && upstreamModel.startsWith("custom-")
                        ? new ModelIdentity("Custom Labs", "Special", "Large") : null;
            }
            @Override public void prepareRequest(Map<String, Object> outbound, boolean stream) {
                outbound.put("custom_control", stream ? "streaming" : "buffered");
            }
            @Override public void register(ProviderRegistrar registrar) {
                registrar.provider(id(), baseUrl(), apiKey());
                registrar.route("custom-fast", id(), model(), 0, 0);
                registrar.route("custom-smart", id(), model(), 0, 0);
            }
        };

        ProviderAdapterRegistry registry = new ProviderAdapterRegistry(List.of(custom, new MockAdapter()));
        assertSame(custom, registry.adapterFor("custom"));
        assertSame(custom, registry.primary(), "a preferred new adapter becomes primary without changing routing code");
        assertEquals("http://custom.example/v1/chat/completions",
                registry.adapterFor("custom").endpoint("http://custom.example/v1"));

        Map<String, Object> outbound = new LinkedHashMap<>();
        registry.adapterFor("custom").prepareRequest(outbound, true);
        assertEquals("streaming", outbound.get("custom_control"));
        assertTrue(registry.adapterFor("custom").headers("secret").containsKey("Authorization"));
        assertEquals("Custom Labs", registry.identify("custom-special").creator());
        assertEquals("Large", registry.identify("custom-special").variant());
        assertTrue(registry.profiles(id -> true).stream()
                .anyMatch(p -> "custom".equals(p.get("id")) && "Custom Labs".equals(p.get("label"))));

        List<String> registeredProviders = new ArrayList<>();
        Map<String, String> registeredRoutes = new LinkedHashMap<>();
        ProviderRegistrar registrar = new ProviderRegistrar() {
            @Override public void provider(String id, String url, String key) { registeredProviders.add(id); }
            @Override public void route(String route, String provider, String upstreamModel, double input, double output) {
                registeredRoutes.put(route, provider + "/" + upstreamModel);
            }
            @Override public void alias(String name, String primary, List<String> fallbacks) { }
            @Override public boolean hasProvider(String id) { return registeredProviders.contains(id); }
            @Override public boolean hasRoute(String route) { return registeredRoutes.containsKey(route); }
        };
        custom.register(registrar);
        assertEquals(List.of("custom"), registeredProviders);
        assertEquals("custom/custom-special", registeredRoutes.get("custom-fast"));
        assertEquals("custom/custom-special", registeredRoutes.get("custom-smart"));
    }

    @Test void hyphenatedAdapterIdsResolveFromRouteNames() {
        ProviderAdapter custom = new OpenAiCompatibleAdapter() {
            @Override public String id() { return "custom-labs"; }
            @Override public String label() { return "Custom Labs"; }
            @Override public int order() { return 4; }
            @Override public boolean enabled() { return true; }
            @Override public String apiKey() { return "not-a-real-key"; }
            @Override public String baseUrl() { return "http://custom.example/v1"; }
            @Override public String model() { return "custom-special"; }
            @Override public boolean preferAsPrimary() { return true; }
        };
        ProviderAdapterRegistry registry = new ProviderAdapterRegistry(java.util.List.of(custom, new MockAdapter()));
        assertSame(custom, registry.adapterForRoute("custom-labs-fast"));
        assertSame(custom, registry.adapterForRoute("custom-labs-research"));
    }

    @Test void unknownProviderFallbackIsDisabledAndDoesNotOwnRoutes() {
        ProviderAdapterRegistry registry = new ProviderAdapterRegistry(java.util.List.of(new MockAdapter()));
        ProviderAdapter fallback = registry.adapterFor("never-configured");
        assertEquals("openai-compatible", fallback.id());
        assertFalse(fallback.enabled());
        assertEquals("", fallback.baseUrl());
        assertNull(registry.adapterForRoute("never-configured-fast"));
    }
}
