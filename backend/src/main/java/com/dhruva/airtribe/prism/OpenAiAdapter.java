package com.dhruva.airtribe.prism;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional OpenAI provider, enabled by configuring a key. */
@Component
public final class OpenAiAdapter extends OpenAiCompatibleAdapter {
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public OpenAiAdapter(
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.model:gpt-4o-mini}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override public String id() { return "openai"; }
    @Override public String label() { return "OpenAI"; }
    @Override public int order() { return 3; }
    @Override public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }
    @Override public String apiKey() { return apiKey; }
    @Override public String baseUrl() { return baseUrl; }
    @Override public String model() { return model; }
    @Override public boolean preferAsPrimary() { return false; }

    @Override public ModelIdentity identify(String upstreamModel) {
        if (upstreamModel == null) return null;
        String id = upstreamModel.toLowerCase();
        if (!id.startsWith("gpt-") && !id.startsWith("openai/")) return null;
        return new ModelIdentity("OpenAI", "GPT", null);
    }
}
