package com.dhruva.airtribe.prism;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Rolling provider health used to prefer healthy routes.
 *
 * <p>Configuration (window and thresholds) is process configuration; the
 * samples themselves live in a shared store so every gateway replica sees the
 * same view of a failing provider. Unit tests can construct the monitor with
 * an isolated in-memory store.
 */
@Component
public final class ProviderHealthMonitor {
    private final Clock clock;
    private final ProviderHealthStore store;
    private final AtomicInteger recorded = new AtomicInteger();
    @Value("${prism.provider-health.reset-on-start:false}") private boolean resetOnStart;
    @Value("${spring.datasource.url:jdbc:sqlite:}") private String databaseUrl = "jdbc:sqlite:";
    private long windowMillis = 60_000;
    private double errorRateThreshold = 0.5;
    private long p95LatencyThresholdMs = 45_000;
    private int minimumSamples = 3;

    public ProviderHealthMonitor() { this(new InMemoryProviderHealthStore(), Clock.systemUTC()); }

    @Autowired
    public ProviderHealthMonitor(ProviderHealthStore store) { this(store, Clock.systemUTC()); }

    ProviderHealthMonitor(ProviderHealthStore store, Clock clock) { this.store = store; this.clock = clock; }

    /** Isolated test databases start clean; production keeps the shared rolling history. */
    @PostConstruct void initialize() {
        if (!resetOnStart) return;
        if (DatabaseDialect.detect(databaseUrl) != DatabaseDialect.Kind.SQLITE)
            throw new IllegalStateException("prism.provider-health.reset-on-start=true is only allowed for an isolated SQLite test database; shared PostgreSQL health samples must not be wiped on boot");
        reset();
    }

    public void configure(long windowSeconds, double errorThreshold, long p95ThresholdMs) {
        windowMillis = Math.max(5, windowSeconds) * 1_000;
        errorRateThreshold = Math.max(0, Math.min(1, errorThreshold));
        p95LatencyThresholdMs = Math.max(1, p95ThresholdMs);
    }

    public void reset() { store.reset(); }

    public void record(String provider, boolean success, long latencyMs) {
        if (provider == null || provider.isBlank()) return;
        store.record(new Sample(provider, clock.millis(), success, Math.max(0, latencyMs)));
        if (recorded.incrementAndGet() % 100 == 0) store.prune(clock.millis() - windowMillis);
    }

    public boolean isDegraded(String provider) { return health(provider).degraded; }

    /** Moves degraded routes behind healthy ones while preserving order within each group. */
    public String[] preferHealthy(String[] chain, Function<String, String> providerForModel) {
        List<String> healthy = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        for (String model : chain) {
            String provider = providerForModel.apply(model);
            (provider != null && isDegraded(provider) ? degraded : healthy).add(model);
        }
        healthy.addAll(degraded);
        return healthy.toArray(String[]::new);
    }

    /** Orders candidates inside their healthy/degraded groups by recent latency. */
    public String[] preferLowestLatency(String[] chain, Function<String, String> providerForModel, boolean weighted) {
        List<String> ordered = new ArrayList<>(Arrays.asList(chain));
        Map<String,Integer> originalOrder = new HashMap<>();
        for (int i=0;i<chain.length;i++) originalOrder.put(chain[i],i);
        ordered.sort(Comparator.<String>comparingInt(model -> {
            String provider=providerForModel.apply(model);
            return provider!=null&&isDegraded(provider)?1:0;
        }).thenComparingDouble(model -> {
            String provider=providerForModel.apply(model);
            Health h=health(provider==null?"":provider);
            if(h.total==0||h.averageLatencyMs==null)return Double.MAX_VALUE;
            double weight=weighted&&"beta".equalsIgnoreCase(provider)?1.15:1.0;
            return h.averageLatencyMs/weight;
        }).thenComparingInt(originalOrder::get));
        return ordered.toArray(String[]::new);
    }

    public List<Map<String, Object>> snapshot(Collection<String> providerNames) {
        Set<String> names = new TreeSet<>(providerNames);
        names.addAll(store.providers());
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
        List<Sample> history = store.samples(provider, clock.millis() - windowMillis);
        int total = history.size();
        int errors = (int) history.stream().filter(s -> !s.success()).count();
        List<Long> latencies = history.stream().map(Sample::latencyMs).sorted().toList();
        double errorRate = total == 0 ? 0 : (double) errors / total;
        Double average = total == 0 ? null : latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        Long p95 = total == 0 ? null : latencies.get(Math.max(0, (int) Math.ceil(total * .95) - 1));
        boolean degraded = total >= minimumSamples &&
                (errorRate >= errorRateThreshold || (p95 != null && p95 >= p95LatencyThresholdMs));
        return new Health(total, errors, errorRate, average, p95, degraded);
    }

    public record Sample(String provider, long at, boolean success, long latencyMs) {}
    private record Health(int total, int errors, double errorRate, Double averageLatencyMs,
                          Long p95LatencyMs, boolean degraded) {}
}
