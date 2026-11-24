package com.dhruva.airtribe.prism;

import java.util.List;

/** The narrow surface adapters use to register routes without changing orchestration. */
public interface ProviderRegistrar {
    void provider(String id, String url, String key);
    void route(String route, String provider, String upstreamModel, double inputPer1m, double outputPer1m);
    void alias(String name, String primary, List<String> fallbacks);
    boolean hasProvider(String id);
    boolean hasRoute(String route);
}
