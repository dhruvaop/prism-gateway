package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admission state must be shared by gateway replicas. Two independent service
 * instances (as would run in two processes) are used here to prove the counters
 * and the budget lease are in the database rather than in one JVM's memory.
 */
@SpringBootTest
class SharedAdmissionStateTest {
    @Autowired JdbcTemplate db;

    AdmissionService replicaA;
    AdmissionService replicaB;

    @BeforeEach void setUp() {
        replicaA = new AdmissionService(db, "jdbc:sqlite:./target/prism-test.db");
        replicaB = new AdmissionService(db, "jdbc:sqlite:./target/prism-test.db");
        replicaA.initialize();
        replicaB.initialize();
        replicaA.reset();
    }

    @Test void rpmWindowIsClaimedAtomicallyAcrossReplicas() {
        assertTrue(replicaA.claimRequest("shared-rpm-key", 3));
        assertTrue(replicaB.claimRequest("shared-rpm-key", 3));
        assertTrue(replicaA.claimRequest("shared-rpm-key", 3));
        assertFalse(replicaB.claimRequest("shared-rpm-key", 3), "a second replica must see the first replica's claims");
        assertEquals(3, replicaA.requestsUsed("shared-rpm-key"));
        assertEquals(3, replicaB.requestsUsed("shared-rpm-key"));
    }

    @Test void tokenUsageIsVisibleToEveryReplica() {
        replicaA.recordTokens("shared-tpm-key", 40);
        replicaB.recordTokens("shared-tpm-key", 10);
        assertEquals(50, replicaA.tokensUsed("shared-tpm-key"));
        assertEquals(50, replicaB.tokensUsed("shared-tpm-key"));
    }

    @Test void budgetLeaseIsHeldAcrossReplicasUntilReleased() throws Exception {
        Runnable release = replicaA.acquire("shared-lease-key");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Runnable> waiting = pool.submit(() -> replicaB.acquire("shared-lease-key"));
            Thread.sleep(150);
            assertFalse(waiting.isDone(), "the second replica must wait for the shared lease");
            release.run();
            assertNotNull(waiting.get(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void expiredBudgetLeaseIsStolenWithoutDeleteInsertRace() {
        String key = "expired-lease-key";
        Runnable first = replicaA.acquire(key);
        String originalOwner = db.queryForObject("SELECT owner FROM admission_leases WHERE key_ref=?", String.class, key);
        db.update("UPDATE admission_leases SET expires_at=? WHERE key_ref=?", System.currentTimeMillis() - 1, key);
        Runnable stolen = replicaB.acquire(key);
        String newOwner = db.queryForObject("SELECT owner FROM admission_leases WHERE key_ref=?", String.class, key);
        assertNotNull(newOwner);
        assertNotEquals(originalOwner, newOwner, "an expired lease must be reassigned atomically");
        stolen.run();
        first.run();
    }

    @Test void healthSamplesAreSharedAcrossMonitorInstances() {
        ProviderHealthStore store = new InMemoryProviderHealthStore();
        ProviderHealthMonitor replicaOne = new ProviderHealthMonitor(store, Clock.systemUTC());
        ProviderHealthMonitor replicaTwo = new ProviderHealthMonitor(store, Clock.systemUTC());
        replicaOne.record("shared-provider", false, 30);
        replicaOne.record("shared-provider", false, 40);
        replicaTwo.record("shared-provider", true, 50);
        assertTrue(replicaTwo.isDegraded("shared-provider"));
        assertEquals(3, replicaTwo.snapshot(java.util.List.of("shared-provider")).get(0).get("recent_requests"));
    }
}
