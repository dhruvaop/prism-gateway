package com.dhruva.airtribe.prism;

import java.util.*;

/** Isolated store used by unit tests and by embedders without a database. */
public final class InMemoryProviderHealthStore implements ProviderHealthStore {
    private final Map<String, List<ProviderHealthMonitor.Sample>> samples = new HashMap<>();

    @Override public synchronized void record(ProviderHealthMonitor.Sample sample) {
        samples.computeIfAbsent(sample.provider(), ignored -> new ArrayList<>()).add(sample);
    }

    @Override public synchronized List<ProviderHealthMonitor.Sample> samples(String provider, long cutoffMillis) {
        return samples.getOrDefault(provider, List.of()).stream().filter(s -> s.at() >= cutoffMillis).toList();
    }

    @Override public synchronized Set<String> providers() { return new HashSet<>(samples.keySet()); }

    @Override public synchronized void prune(long cutoffMillis) {
        samples.values().forEach(list -> list.removeIf(sample -> sample.at() < cutoffMillis));
    }

    @Override public synchronized void reset() { samples.clear(); }
}
