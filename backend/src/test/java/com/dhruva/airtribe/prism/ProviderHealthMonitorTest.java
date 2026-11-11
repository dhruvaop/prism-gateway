package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderHealthMonitorTest {
    @Test void degradesAfterRepeatedProviderFailuresAndRoutesHealthyFirst() {
        ProviderHealthMonitor health = new ProviderHealthMonitor();
        health.record("alpha", false, 30);
        health.record("alpha", false, 40);
        assertFalse(health.isDegraded("alpha"), "fewer than three samples must not trip degradation");
        health.record("alpha", true, 50);
        assertTrue(health.isDegraded("alpha"), "two failures of three crosses the configured error threshold");

        assertArrayEquals(new String[]{"beta-small", "alpha-small"}, health.preferHealthy(
                new String[]{"alpha-small", "beta-small"}, model -> model.startsWith("alpha") ? "alpha" : "beta"));
    }

    @Test void degradesAProviderForHighP95LatencyAndReportsMetrics() {
        ProviderHealthMonitor health = new ProviderHealthMonitor();
        health.configure(60, 0.5, 5_000);
        health.record("alpha", true, 5_000);
        health.record("alpha", true, 5_100);
        health.record("alpha", true, 5_200);
        assertTrue(health.isDegraded("alpha"));
        var row = health.snapshot(List.of("alpha")).get(0);
        assertEquals(false, row.get("healthy"));
        assertEquals(3, row.get("recent_requests"));
        assertEquals(5_200L, row.get("p95_latency_ms"));
    }

    @Test void healthyFallbackOrderRemainsConfiguredOrder() {
        ProviderHealthMonitor health = new ProviderHealthMonitor();
        assertArrayEquals(new String[]{"alpha-small", "beta-small"}, health.preferHealthy(
                new String[]{"alpha-small", "beta-small"}, model -> model.startsWith("alpha") ? "alpha" : "beta"));
    }
}
