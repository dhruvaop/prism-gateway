package com.dhruva.airtribe.prism;

import java.util.LinkedHashMap;
import java.util.Map;

/** Base for providers that speak the OpenAI chat-completions protocol. */
public abstract class OpenAiCompatibleAdapter implements ProviderAdapter {
    @Override public String endpoint(String baseUrl) { return baseUrl.replaceAll("/$", "") + "/chat/completions"; }

    @Override public Map<String, String> headers(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    @Override public void prepareRequest(Map<String, Object> outbound, boolean stream) { }

    @Override public boolean trackHealth(String route) { return true; }

    @Override public ModelIdentity identify(String upstreamModel) { return null; }

    @Override public boolean searchOnline() { return false; }

    @Override public Map<String, String> taskModels() { return Map.of(); }

    @Override public void register(ProviderRegistrar registrar) {
        if (!enabled()) return;
        registrar.provider(id(), baseUrl(), apiKey());
        registrar.route(id() + "-fast", id(), model(), 0, 0);
        registrar.route(id() + "-smart", id(), model(), 0, 0);
        taskModels().forEach((task, upstream) ->
                registrar.route(id() + "-" + task, id(), upstream, 0, 0));
    }
}
