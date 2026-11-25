package com.dhruva.airtribe.prism;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Admission state shared by every gateway replica.
 *
 * <p>The old implementation kept RPM sliding windows, TPM windows and the
 * per-key budget semaphore in static maps inside a single JVM. With more than
 * one replica that allowed each process to admit its own full quota and to
 * spend the same budget independently. All three controls are now claimed with
 * a single atomic SQL statement against the shared database, using a fixed
 * 60-second window so a claim is safe regardless of how many processes race.
 *
 * <p>The budget lease is a row lock emulated with a conditional upsert that only
 * succeeds when no live lease exists for the key. Stale leases are reclaimed
 * atomically after a timeout so a crashed replica cannot block a key forever.
 */
@Service
public final class AdmissionService {
    private static final long WINDOW_MILLIS = 60_000;

    private final JdbcTemplate db;
    private final DatabaseDialect.Kind dialect;

    @Value("${prism.admission.reset-on-start:false}") private boolean resetOnStart;
    // Initial values keep manually constructed instances (unit tests, embedders)
    // safe even when Spring injection is not available.
    @Value("${prism.admission.lease-timeout-ms:120000}") private long leaseTimeoutMs = 120_000;
    @Value("${prism.admission.max-wait-ms:30000}") private long maxWaitMs = 30_000;

    public AdmissionService(JdbcTemplate db, @Value("${spring.datasource.url}") String databaseUrl) {
        this.db = db;
        this.dialect = DatabaseDialect.detect(databaseUrl);
    }

    @PostConstruct
    public void initialize() {
        db.execute("CREATE TABLE IF NOT EXISTS admission_counters(key_ref TEXT NOT NULL, kind TEXT NOT NULL, window_start BIGINT NOT NULL, used BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(key_ref,kind,window_start))");
        db.execute("CREATE TABLE IF NOT EXISTS admission_leases(key_ref TEXT PRIMARY KEY, owner TEXT NOT NULL, expires_at BIGINT NOT NULL)");
        db.execute("CREATE INDEX IF NOT EXISTS admission_counters_window_idx ON admission_counters(kind,window_start)");
        if (resetOnStart) {
            if (dialect != DatabaseDialect.Kind.SQLITE)
                throw new IllegalStateException("prism.admission.reset-on-start=true is only allowed for an isolated SQLite test database; shared PostgreSQL admission state must not be wiped on boot");
            reset();
        }
    }

    /** Test/maintenance helper: clears all in-flight admission state. */
    public void reset() {
        db.update("DELETE FROM admission_counters");
        db.update("DELETE FROM admission_leases");
    }

    private long bucket() { return System.currentTimeMillis() / WINDOW_MILLIS; }

    /**
     * Atomically claims one request in the current window. Returns false when
     * the shared counter already reached the configured RPM limit.
     */
    public boolean claimRequest(String key, int rpm) {
        if (rpm <= 0) return true;
        long window = bucket();
        String sql = "INSERT INTO admission_counters(key_ref,kind,window_start,used) VALUES(?,?,?,1) "
                + "ON CONFLICT(key_ref,kind,window_start) DO UPDATE SET used=admission_counters.used+1 WHERE admission_counters.used<?";
        boolean admitted = db.update(sql, key, "rpm", window, rpm) > 0;
        prune(window);
        return admitted;
    }

    /** Requests admitted in the current window, counted across all replicas. */
    public long requestsUsed(String key) {
        Long used = db.queryForObject("SELECT COALESCE(SUM(used),0) FROM admission_counters WHERE key_ref=? AND kind='rpm' AND window_start=?", Long.class, key, bucket());
        return used == null ? 0 : used;
    }

    /** Tokens already recorded in the shared TPM window. */
    public long tokensUsed(String key) {
        Long used = db.queryForObject("SELECT COALESCE(SUM(used),0) FROM admission_counters WHERE key_ref=? AND kind='tpm' AND window_start=?", Long.class, key, bucket());
        return used == null ? 0 : used;
    }

    /** Atomically adds completed token usage to the shared TPM window. */
    public void recordTokens(String key, int tokens) {
        if (key == null || tokens <= 0) return;
        long window = bucket();
        db.update("INSERT INTO admission_counters(key_ref,kind,window_start,used) VALUES(?,?,?,?) "
                + "ON CONFLICT(key_ref,kind,window_start) DO UPDATE SET used=admission_counters.used+excluded.used",
                key, "tpm", window, tokens);
        prune(window);
    }

    private void prune(long window) { db.update("DELETE FROM admission_counters WHERE window_start<?", window - 1); }

    /**
     * Acquires the per-key budget lease. Only one replica can hold it, so the
     * spend check, upstream call and usage write form one serializable unit.
     */
    public Runnable acquire(String key) {
        String owner = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (true) {
            long now = System.currentTimeMillis();
            // One conditional upsert: a live lease is never deleted first, so a
            // concurrent replica cannot slip in during the delete/insert gap.
            // An expired lease is stolen atomically by the replica that wins.
            int inserted = db.update(
                    "INSERT INTO admission_leases(key_ref,owner,expires_at) VALUES(?,?,?) "
                    + "ON CONFLICT(key_ref) DO UPDATE SET owner=excluded.owner, expires_at=excluded.expires_at "
                    + "WHERE admission_leases.expires_at < ?",
                    key, owner, now + leaseTimeoutMs, now);
            if (inserted > 0) {
                return () -> db.update("DELETE FROM admission_leases WHERE key_ref=? AND owner=?", key, owner);
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new PrismService.GatewayException(503, "request_interrupted", "Timed out waiting for this key's budget lease");
            }
            try { Thread.sleep(20); } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new PrismService.GatewayException(503, "request_interrupted", "Request interrupted while waiting for budget accounting");
            }
        }
    }
}
