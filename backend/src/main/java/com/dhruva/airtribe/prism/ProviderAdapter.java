package com.dhruva.airtribe.prism;

import java.util.Map;

/**
 * Everything the gateway needs to know about one upstream provider.
 *
 * <p>Adding a provider means adding one implementation of this interface.
 * Routing, retries, streaming, accounting and redaction call only these
 * methods, so none of those paths need to change for a new provider.
 */
public interface ProviderAdapter {
    String id();
    String label();
    int order();
    boolean enabled();

    String baseUrl();
    String apiKey();
    String model();
    boolean preferAsPrimary();
    boolean searchOnline();
    Map<String, String> taskModels();

    /** Full URL for an OpenAI-compatible chat completions request. */
    String endpoint(String baseUrl);
    /** Headers for the upstream call. Never logged by the gateway. */
    Map<String, String> headers(String apiKey);
    /** Provider-specific request shaping, applied after Prism-only fields are removed. */
    void prepareRequest(Map<String, Object> outbound, boolean stream);
    /** Whether failures on this route should contribute to shared provider health. */
    boolean trackHealth(String route);
    /** Provider-specific model facts, or null to use the gateway's generic catalogue. */
    ModelIdentity identify(String upstreamModel);

    /** Registers providers, routes and task models with the gateway. */
    void register(ProviderRegistrar registrar);
}
