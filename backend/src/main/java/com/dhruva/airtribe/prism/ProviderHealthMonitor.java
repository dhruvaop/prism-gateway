package com.dhruva.airtribe.prism;

import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** Rolling, in-memory provider health used to prefer healthy routes. */
public final class ProviderHealthMonitor {
    private final Clock clock;
    private final Map<String, ArrayDeque<Sample>> samples = new HashMap<>();
    private long windowMillis = 60_000;
    private double errorRateThreshold = 0.5;
    private long p95LatencyThresholdMs = 5_000;
    private int minimumSamples = 3;

    public ProviderHealthMonitor() { this(Clock.systemUTC()); }
    ProviderHealthMonitor(Clock clock) { this.clock = clock; }

    public synchronized void configure(long windowSeconds, double errorThreshold, long p95ThresholdMs) {
        windowMillis = Math.max(5, windowSeconds) * 1_000;
        errorRateThreshold = Math.max(0, Math.min(1, errorThreshold));
        p95LatencyThresholdMs = Math.max(1, p95ThresholdMs);
        pruneAll();
    }

    public synchronized void record(String provider, boolean success, long latencyMs) {
        if (provider == null || provider.isBlank()) return;
        ArrayDeque<Sample> history = samples.computeIfAbsent(provider, ignored -> new ArrayDeque<>());
        prune(history);
        history.addLast(new Sample(clock.millis(), success, Math.max(0, latencyMs)));
    }

    public synchronized boolean isDegraded(String provider) {
        return health(provider).degraded;
    }

    /** Moves degraded routes behind healthy ones while preserving order within each group. */
    public synchronized String[] preferHealthy(String[] chain, Function<String, String> providerForModel) {
        List<String> healthy = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        for (String model : chain) {
            String provider = providerForModel.apply(model);
            (provider != null && isDegraded(provider) ? degraded : healthy).add(model);
        }
        healthy.addAll(degraded);
        return healthy.toArray(String[]::new);
    }

    public synchronized List<Map<String, Object>> snapshot(Collection<String> providerNames) {
        Set<String> names = new TreeSet<>(providerNames);
        names.addAll(samples.keySet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : names) {
            Health h = health(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", name);
            row.put("healthy", !h.degraded);
            row.put("degraded", h.degraded);
            row.put("recent_requests", h.total);
            row.put("recent_errors", h.errors);
            row.put("error_rate", h.errorRate);
            row.put("average_latency_ms", h.averageLatencyMs);
            row.put("p95_latency_ms", h.p95LatencyMs);
            row.put("window_seconds", windowMillis / 1_000);
            row.put("error_rate_threshold", errorRateThreshold);
            row.put("p95_latency_threshold_ms", p95LatencyThresholdMs);
            result.add(row);
        }
        return result;
    }

    private Health health(String provider) {
        ArrayDeque<Sample> history = samples.computeIfAbsent(provider, ignored -> new ArrayDeque<>());
        prune(history);
        int total = history.size();
        int errors = (int) history.stream().filter(s -> !s.success).count();
        List<Long> latencies = history.stream().map(Sample::latencyMs).sorted().toList();
        double errorRate = total == 0 ? 0 : (double) errors / total;
        Double average = total == 0 ? null : latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        Long p95 = total == 0 ? null : latencies.get(Math.max(0, (int) Math.ceil(total * .95) - 1));
        boolean degraded = total >= minimumSamples &&
                (errorRate >= errorRateThreshold || (p95 != null && p95 >= p95LatencyThresholdMs));
        return new Health(total, errors, errorRate, average, p95, degraded);
    }

    private void pruneAll() { for (ArrayDeque<Sample> history : samples.values()) prune(history); }
    private void prune(ArrayDeque<Sample> history) {
        long cutoff = clock.millis() - windowMillis;
        while (!history.isEmpty() && history.peekFirst().at < cutoff) history.removeFirst();
    }

    private record Sample(long at, boolean success, long latencyMs) {}
    private record Health(int total, int errors, double errorRate, Double averageLatencyMs,
                         Long p95LatencyMs, boolean degraded) {}
}
