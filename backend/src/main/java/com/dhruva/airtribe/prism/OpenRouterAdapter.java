package com.dhruva.airtribe.prism;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** OpenRouter provider: task-specific models and reasoning request controls. */
@Component
public final class OpenRouterAdapter extends OpenAiCompatibleAdapter {
    private final String apiKey;
    private final String baseUrl;
    private final boolean preferAsPrimary;
    private final String model;
    private final int maxTokens;
    private final String simple;
    private final String search;
    private final String research;
    private final String reasoning;
    private final String code;
    private final boolean searchOnline;

    public OpenRouterAdapter(
            @Value("${openrouter.api-key:}") String apiKey,
            @Value("${openrouter.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${openrouter.prefer-as-primary:false}") boolean preferAsPrimary,
            @Value("${openrouter.model:nvidia/nemotron-3.5-lightning:free}") String model,
            @Value("${openrouter.max-tokens:4096}") int maxTokens,
            @Value("${openrouter.model-simple:}") String simple,
            @Value("${openrouter.model-search:}") String search,
            @Value("${openrouter.model-research:}") String research,
            @Value("${openrouter.model-reasoning:}") String reasoning,
            @Value("${openrouter.model-code:}") String code,
            @Value("${openrouter.search-online:false}") boolean searchOnline) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.preferAsPrimary = preferAsPrimary;
        this.model = model;
        this.maxTokens = maxTokens;
        this.simple = simple;
        this.search = search;
        this.research = research;
        this.reasoning = reasoning;
        this.code = code;
        this.searchOnline = searchOnline;
    }

    @Override public String id() { return "openrouter"; }
    @Override public String label() { return "OpenRouter · Nemotron/task routes"; }
    @Override public int order() { return 1; }
    @Override public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }
    @Override public String apiKey() { return apiKey; }
    @Override public String baseUrl() { return baseUrl; }
    @Override public String model() { return model; }
    @Override public boolean preferAsPrimary() { return preferAsPrimary; }
    @Override public boolean searchOnline() { return searchOnline; }

    @Override public Map<String, String> taskModels() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("simple", taskModel(simple, model, false));
        models.put("search", taskModel(search, model, searchOnline));
        models.put("research", taskModel(research, model, false));
        models.put("reasoning", taskModel(reasoning, model, false));
        models.put("code", taskModel(code, model, false));
        return models;
    }

    @Override public void prepareRequest(Map<String, Object> outbound, boolean stream) {
        if (maxTokens > 0) outbound.putIfAbsent("max_tokens", maxTokens);
        outbound.put("reasoning", Map.of("effort", "minimal", "exclude", true));
        if (stream) outbound.put("stream_options", Map.of("include_usage", true));
    }

    /** Task-specific routes share one provider and must not degrade its health. */
    @Override public boolean trackHealth(String route) {
        return route.equals("openrouter-fast") || route.equals("openrouter-smart");
    }

    private String taskModel(String configured, String fallback, boolean online) {
        String value = configured == null || configured.isBlank() ? fallback : configured.trim();
        return online && !value.endsWith(":online") ? value + ":online" : value;
    }
}
