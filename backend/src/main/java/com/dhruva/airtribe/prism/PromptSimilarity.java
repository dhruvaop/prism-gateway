package com.dhruva.airtribe.prism;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Prompt tokenisation shared by the persistent semantic cache and the
 * process-local burst cache. Keeping one implementation guarantees that a
 * prompt that scored as a paraphrase in Postgres also scores the same way in
 * memory.
 */
final class PromptSimilarity {
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "is", "are", "do", "i", "my", "me", "to", "of", "for", "on", "in",
            "can", "what", "how", "if", "bought", "steps", "step", "who", "where", "when", "why",
            "does", "did", "would", "could", "should", "please", "get", "this", "that", "these", "those");

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("reset", "reset"), Map.entry("change", "reset"), Map.entry("recover", "reset"),
            Map.entry("refund", "refund"), Map.entry("money", "refund"), Map.entry("back", "refund"),
            Map.entry("policy", "refund"), Map.entry("return", "refund"),
            Map.entry("annual", "annual"), Map.entry("plan", "plan"),
            Map.entry("dashboard", "dashboard"), Map.entry("account", "dashboard"));

    private PromptSimilarity() {
    }

    static String normalize(String prompt) {
        return prompt == null ? "" : prompt.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    static Set<String> tokens(String prompt) {
        Set<String> out = new HashSet<>();
        if (prompt == null) return out;
        for (String word : prompt.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@.]", " ").split("\\s+")) {
            if (word.length() > 1 && !STOP.contains(word)) {
                String term = word.endsWith("s") && word.length() > 4 ? word.substring(0, word.length() - 1) : word;
                out.add(SYNONYMS.getOrDefault(term, term));
            }
        }
        return out;
    }

    static double score(String left, String right) {
        return score(tokens(left), tokens(right));
    }

    static double score(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return (double) intersection.size() / union.size();
    }
}
