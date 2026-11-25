package com.dhruva.airtribe.prism;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Resolves the adapter that owns a provider, route or upstream model. */
@Component
public final class ProviderAdapterRegistry {
    private final List<ProviderAdapter> adapters;
    private final ProviderAdapter fallback = new GenericOpenAiAdapter();

    public ProviderAdapterRegistry(List<ProviderAdapter> adapters) {
        this.adapters = new ArrayList<>(adapters);
        this.adapters.sort(Comparator.comparingInt(ProviderAdapter::order));
    }

    public List<ProviderAdapter> all() { return List.copyOf(adapters); }

    public ProviderAdapter adapterFor(String providerName) {
        if (providerName != null) {
            for (ProviderAdapter adapter : adapters) {
                if (adapter.id().equalsIgnoreCase(providerName)) return adapter;
            }
        }
        return fallback;
    }

    /**
     * Resolves a route such as {@code custom-labs-fast} to its owning adapter.
     * Longest id wins, so adapters whose ids contain hyphens are no longer
     * misparsed by taking the text before the first hyphen.
     */
    public ProviderAdapter adapterForRoute(String route) {
        if (route == null || route.isBlank()) return null;
        ProviderAdapter match = null;
        int longest = -1;
        for (ProviderAdapter adapter : adapters) {
            String prefix = adapter.id() + "-";
            if (route.startsWith(prefix) && prefix.length() > longest) {
                match = adapter;
                longest = prefix.length();
            }
        }
        return match;
    }

    /** The configured primary provider, or null when routing stays on mocks. */
    public ProviderAdapter primary() {
        for (ProviderAdapter adapter : adapters) {
            if (adapter.enabled() && adapter.preferAsPrimary()) return adapter;
        }
        return null;
    }

    /** Provider-specific metadata; null means the gateway's generic catalogue applies. */
    public ModelIdentity identify(String upstreamModel) {
        for (ProviderAdapter adapter : adapters) {
            ModelIdentity identity = adapter.identify(upstreamModel);
            if (identity != null) return identity;
        }
        return null;
    }

    /** Labels used by the admin console, derived from the registered adapters. */
    public List<Map<String, Object>> profiles(java.util.function.Predicate<String> available) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProviderAdapter adapter : adapters) {
            out.add(Map.of("id", adapter.id(), "label", adapter.label(),
                    "available", available.test(adapter.id())));
        }
        return out;
    }
}
