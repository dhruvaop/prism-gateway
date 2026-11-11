package com.dhruva.airtribe.prism;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Process-local burst cache in front of the persistent semantic cache.
 *
 * <p>It is deliberately small and time bounded: repeated or paraphrased
 * questions are served from RAM for a short window, while the database remains
 * the durable semantic store. The cache never contains provider credentials and
 * is always scoped by virtual key + requested model, so teams cannot read each
 * other's answers.</p>
 */
@Component
public class ShortTermCache {
    private final int maxEntries;
    private final long ttlSeconds;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long hits;
    private long misses;
    private long stores;
    private long evictions;
    private long expirations;
    private long purges;
    private Instant lastPurgeAt;

    @Autowired
    public ShortTermCache(@Value("${prism.cache.short-term.max-entries:500}") int maxEntries,
                          @Value("${prism.cache.short-term.ttl-seconds:900}") long ttlSeconds) {
        this(maxEntries, ttlSeconds, Clock.systemUTC());
    }

    ShortTermCache(int maxEntries, long ttlSeconds, Clock clock) {
        this.maxEntries = Math.max(0, maxEntries);
        this.ttlSeconds = Math.max(1, ttlSeconds);
        this.clock = clock;
    }

    public record Hit(Map<String, Object> payload, int promptTokens, int completionTokens) {
    }

    private record Entry(String virtualKey, String model, String prompt, Set<String> promptTokens,
                         Map<String, Object> payload, int promptTokensCount, int completionTokens,
                         Instant expiresAt) {
    }

    synchronized Hit find(String virtualKey, String model, String prompt, double threshold) {
        if (maxEntries == 0 || prompt == null || prompt.isBlank()) return null;
        purgeExpired();
        Set<String> wanted = PromptSimilarity.tokens(prompt);
        if (wanted.isEmpty()) {
            misses++;
            return null;
        }

        String bestKey = null;
        double bestScore = -1;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry candidate = item.getValue();
            if (!candidate.virtualKey().equals(virtualKey) || !candidate.model().equals(model)) continue;
            double score = PromptSimilarity.score(wanted, candidate.promptTokens());
            if (score >= threshold && score > bestScore) {
                bestScore = score;
                bestKey = item.getKey();
            }
        }
        if (bestKey == null) {
            misses++;
            return null;
        }
        Entry hit = entries.get(bestKey);
        hits++;
        return new Hit(hit.payload(), hit.promptTokensCount(), hit.completionTokens());
    }

    synchronized void put(String virtualKey, String model, String prompt, Map<String, Object> payload,
                          int promptTokens, int completionTokens) {
        if (maxEntries == 0 || prompt == null || prompt.isBlank() || payload == null) return;
        Set<String> promptTokensSet = PromptSimilarity.tokens(prompt);
        if (promptTokensSet.isEmpty()) return;
        String key = virtualKey + '\u0000' + model + '\u0000' + PromptSimilarity.normalize(prompt);
        entries.put(key, new Entry(virtualKey, model, prompt, promptTokensSet, payload, promptTokens,
                completionTokens, clock.instant().plusSeconds(ttlSeconds)));
        stores++;
        evictOverflow();
    }

    public synchronized Map<String, Object> clear() {
        int removed = entries.size();
        entries.clear();
        purges++;
        lastPurgeAt = clock.instant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", removed);
        out.put("purged_at", lastPurgeAt.toString());
        return out;
    }

    synchronized int evict(String virtualKey) {
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.virtualKey().equals(virtualKey)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries.size());
        out.put("max_entries", maxEntries);
        out.put("ttl_seconds", ttlSeconds);
        out.put("hits", hits);
        out.put("misses", misses);
        out.put("stores", stores);
        out.put("evictions", evictions);
        out.put("expirations", expirations);
        out.put("purges", purges);
        out.put("last_purge_at", lastPurgeAt == null ? null : lastPurgeAt.toString());
        return out;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (!entry.expiresAt().isAfter(now)) {
                iterator.remove();
                expirations++;
            }
        }
    }

    private void evictOverflow() {
        while (entries.size() > maxEntries) {
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
            evictions++;
        }
    }
}
