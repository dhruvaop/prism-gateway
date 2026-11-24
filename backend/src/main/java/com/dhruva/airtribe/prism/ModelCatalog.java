package com.dhruva.airtribe.prism;

import java.util.Locale;

/** Generic model metadata for routes no adapter claims specifically. */
final class ModelCatalog {
    private ModelCatalog() {}

    static String creator(String model) {
        String id = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (id.contains("gemini") || id.startsWith("google/")) return "Google";
        if (id.startsWith("nvidia/") || id.contains("nemotron")) return "NVIDIA";
        if (id.startsWith("openai/") || id.startsWith("gpt-")) return "OpenAI";
        if (id.startsWith("anthropic/") || id.contains("claude")) return "Anthropic";
        if (id.startsWith("meta-llama/") || id.contains("llama")) return "Meta";
        if (id.startsWith("mistralai/") || id.contains("mistral") || id.contains("mixtral")) return "Mistral AI";
        if (id.startsWith("deepseek/")) return "DeepSeek";
        if (id.startsWith("qwen/")) return "Alibaba";
        if (id.startsWith("x-ai/") || id.contains("grok")) return "xAI";
        if (id.startsWith("cohere/")) return "Cohere";
        if (id.startsWith("alpha") || id.startsWith("beta")) return "Prism mock provider";
        return "unknown (not in Prism's model catalogue)";
    }

    static String family(String model) {
        String id = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (id.contains("gemini")) return "Gemini";
        if (id.contains("nemotron")) return "Nemotron";
        if (id.contains("gpt")) return "GPT";
        if (id.contains("claude")) return "Claude";
        if (id.contains("llama")) return "Llama";
        if (id.contains("mixtral")) return "Mixtral";
        if (id.contains("mistral")) return "Mistral";
        if (id.contains("deepseek")) return "DeepSeek";
        if (id.contains("qwen")) return "Qwen";
        if (id.contains("grok")) return "Grok";
        if (id.startsWith("alpha") || id.startsWith("beta")) return "Prism mock";
        String leaf = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
        int dash = leaf.indexOf('-');
        return dash > 0 ? leaf.substring(0, dash) : leaf;
    }

    static String variant(String model) {
        if (model == null || !model.toLowerCase(Locale.ROOT).contains("gemini")) return null;
        String leaf = model.contains("/") ? model.substring(model.lastIndexOf('/') + 1) : model;
        String withoutFamily = leaf.toLowerCase(Locale.ROOT).replaceFirst("^gemini[- ]?", "");
        String variant = withoutFamily.replaceFirst("^\\d+(?:\\.\\d+)*[- ]?", "").replace('-', ' ').trim();
        if (variant.isBlank()) return null;
        StringBuilder out = new StringBuilder();
        for (String word : variant.split("\\s+")) {
            if (!word.isBlank()) {
                if (out.length() > 0) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
