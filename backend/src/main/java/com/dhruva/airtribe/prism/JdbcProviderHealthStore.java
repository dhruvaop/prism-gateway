package com.dhruva.airtribe.prism;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared provider health. Every replica writes samples to the same table, so
 * a provider marked degraded by one process is skipped by all of them.
 */
@Component
final class JdbcProviderHealthStore implements ProviderHealthStore {
    private final JdbcTemplate db;

    JdbcProviderHealthStore(JdbcTemplate db) { this.db = db; }

    @PostConstruct
    void initialize() {
        db.execute("CREATE TABLE IF NOT EXISTS provider_health_samples(provider TEXT NOT NULL, at_ms BIGINT NOT NULL, success INTEGER NOT NULL, latency_ms BIGINT NOT NULL)");
        db.execute("CREATE INDEX IF NOT EXISTS provider_health_provider_at_idx ON provider_health_samples(provider,at_ms)");
    }

    @Override public void record(ProviderHealthMonitor.Sample sample) {
        db.update("INSERT INTO provider_health_samples(provider,at_ms,success,latency_ms) VALUES(?,?,?,?)",
                sample.provider(), sample.at(), sample.success() ? 1 : 0, sample.latencyMs());
    }

    @Override public List<ProviderHealthMonitor.Sample> samples(String provider, long cutoffMillis) {
        return db.query("SELECT provider,at_ms,success,latency_ms FROM provider_health_samples WHERE provider=? AND at_ms>=? ORDER BY at_ms",
                (rs, row) -> new ProviderHealthMonitor.Sample(rs.getString(1), rs.getLong(2), rs.getInt(3) != 0, rs.getLong(4)),
                provider, cutoffMillis);
    }

    @Override public Set<String> providers() {
        return new LinkedHashSet<>(db.queryForList("SELECT DISTINCT provider FROM provider_health_samples", String.class));
    }

    @Override public void prune(long cutoffMillis) {
        db.update("DELETE FROM provider_health_samples WHERE at_ms<?", cutoffMillis);
    }

    @Override public void reset() { db.update("DELETE FROM provider_health_samples"); }
}
