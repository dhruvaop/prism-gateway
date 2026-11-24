package com.dhruva.airtribe.prism;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Google Gemini through its OpenAI-compatible endpoint. */
@Component
public final class GeminiAdapter extends OpenAiCompatibleAdapter {
    private final String apiKey;
    private final String baseUrl;
    private final boolean preferAsPrimary;
    private final String model;

    public GeminiAdapter(
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta/openai}") String baseUrl,
            @Value("${gemini.prefer-as-primary:false}") boolean preferAsPrimary,
            @Value("${gemini.model:gemini-3.1-flash-lite}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.preferAsPrimary = preferAsPrimary;
        this.model = model;
    }

    @Override public String id() { return "gemini"; }
    @Override public String label() { return "Google Gemini"; }
    @Override public int order() { return 2; }
    @Override public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }
    @Override public String apiKey() { return apiKey; }
    @Override public String baseUrl() { return baseUrl; }
    @Override public String model() { return model; }
    @Override public boolean preferAsPrimary() { return preferAsPrimary; }

    @Override public ModelIdentity identify(String upstreamModel) {
        if (upstreamModel == null || !upstreamModel.toLowerCase(Locale.ROOT).contains("gemini")) return null;
        String leaf = upstreamModel.contains("/") ? upstreamModel.substring(upstreamModel.lastIndexOf('/') + 1) : upstreamModel;
        String variant = leaf.toLowerCase(Locale.ROOT).replaceFirst("^gemini[- ]?", "")
                .replaceFirst("^\\d+(?:\\.\\d+)*[- ]?", "").replace('-', ' ').trim();
        if (variant.isEmpty()) return new ModelIdentity("Google", "Gemini", null);
        StringBuilder out = new StringBuilder();
        for (String word : variant.split("\\s+")) {
            if (word.isBlank()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return new ModelIdentity("Google", "Gemini", out.toString());
    }
}
