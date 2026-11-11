package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two cache layers must behave as one semantic cache:
 * a repeated question is answered from burst memory, a paraphrase is answered
 * from Postgres and then promoted into burst memory, and the daily purge wipes
 * both layers. The Postgres side is also capped so a small Supabase plan cannot
 * accumulate rows forever.
 */
@SpringBootTest
class SemanticCacheTest {
    @Autowired PrismService prism;
    @Autowired ShortTermCache shortTermCache;
    @Autowired JdbcTemplate db;

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private PrismService.Provider originalAlpha;
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void startProvider() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.start();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
        originalAlpha = providerMap().put("alpha", new PrismService.Provider("alpha",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "cache-test-key"));
        calls.set(0);
    }

    @AfterEach
    void cleanUp() {
        if (originalAlpha == null) providerMap().remove("alpha");
        else providerMap().put("alpha", originalAlpha);
        if (server != null) server.stop(0);
        for (String key : keys) {
            db.update("DELETE FROM cache_hits WHERE virtual_key=?", key);
            db.update("DELETE FROM cache_entries WHERE virtual_key=?", key);
            db.update("DELETE FROM request_logs WHERE virtual_key=?", key);
            db.update("DELETE FROM usage_monthly WHERE virtual_key=?", key);
            shortTermCache.evict(key);
        }
        keys.clear();
    }

    @Test
    void repeatedQuestionIsServedFromBurstMemory() {
        String key = key();
        PrismService.Tenant tenant = tenant(key, true);
        Map<String, Object> body = body("alpha-small", "How do I reset my password on the dashboard?");

        PrismService.Result first = prism.complete(tenant, body);
        PrismService.Result second = prism.complete(tenant, body);

        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit(), "the second identical question must be a cache hit");
        assertEquals("cache", second.provider());
        assertEquals(0.0, second.cost());
        assertEquals(1, calls.get(), "the provider must receive only the first request");
        assertEquals(1, countEntries(key), "the first answer should be stored for later requests");
        assertTrue(((Number) shortTermCache.stats().get("hits")).longValue() >= 1);
    }

    @Test
    void paraphraseHitsPostgresAndPromotesIntoBurstMemory() {
        String key = key();
        PrismService.Tenant tenant = tenant(key, true);

        PrismService.Result first = prism.complete(tenant,
                body("alpha-small", "How do I reset my password on the dashboard?"));
        assertFalse(first.cacheHit());

        assertEquals(1, shortTermCache.evict(key), "clear L1 to force the L2 lookup");
        PrismService.Result second = prism.complete(tenant,
                body("alpha-small", "What are the steps to reset my dashboard password?"));
        assertTrue(second.cacheHit(), "the Postgres semantic cache should match the paraphrase");
        assertEquals(1, calls.get(), "the paraphrase must not reach the provider");

        PrismService.Result third = prism.complete(tenant,
                body("alpha-small", "What are the steps to reset my dashboard password?"));
        assertTrue(third.cacheHit());
        assertEquals(1, calls.get(), "the L2 hit should have promoted the answer into L1");
        assertEquals(1, countHits(key), "only the Postgres promotion is recorded as a DB cache hit");
    }

    @Test
    void cacheFalseBypassesBothLayers() {
        String key = key();
        PrismService.Tenant tenant = tenant(key, true);
        Map<String, Object> body = body("alpha-small", "How do I reset my password on the dashboard?");
        body.put("cache", false);

        assertFalse(prism.complete(tenant, body).cacheHit());
        assertFalse(prism.complete(tenant, body).cacheHit());

        assertEquals(2, calls.get(), "cache:false must always call the provider");
        assertEquals(0, countEntries(key));
    }

    @Test
    void purgeClearsMemoryAndBothPostgresTables() {
        String key = key();
        prism.complete(tenant(key, true), body("alpha-small", "How do I reset my password on the dashboard?"));
        assertTrue(countEntries(key) > 0);
        assertTrue(shortTermCache.size() > 0);

        Map<String, Object> result = prism.purgeCache();

        assertTrue(((Number) result.get("deleted_entries")).intValue() >= 1);
        assertTrue(((Number) result.get("deleted_hits")).intValue() >= 0);
        assertEquals(0, countEntries(key));
        assertEquals(0, countHits(key));
        assertEquals(0, shortTermCache.size());
        assertNotNull(result.get("purged_at"));
    }

    @Test
    void postgresCacheCapsOldRowsPerKey() {
        String key = key();
        for (int i = 0; i < 5; i++) {
            db.update("INSERT INTO cache_entries(virtual_key,model,prompt,response,created_at) VALUES(?,?,?,?,?)",
                    key, "alpha-small", "question " + i, "{}", Instant.now().toString());
        }
        Object previousPerKey = ReflectionTestUtils.getField(prism, "cacheMaxEntriesPerKey");
        Object previousGlobal = ReflectionTestUtils.getField(prism, "cacheMaxEntries");
        try {
            ReflectionTestUtils.setField(prism, "cacheMaxEntriesPerKey", 2);
            ReflectionTestUtils.setField(prism, "cacheMaxEntries", 100);
            prism.enforceCacheBounds(key);
            assertEquals(2, countEntries(key), "only the newest rows for a key should survive");
        } finally {
            ReflectionTestUtils.setField(prism, "cacheMaxEntriesPerKey", ((Number) previousPerKey).intValue());
            ReflectionTestUtils.setField(prism, "cacheMaxEntries", ((Number) previousGlobal).intValue());
        }
    }

    @Test
    void shortTermMemoryIsBoundedAndExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ShortTermCache cache = new ShortTermCache(2, 60, clock);
        Map<String, Object> payload = Map.of("choices", List.of());

        cache.put("k", "m", "alpha question", payload, 1, 1);
        cache.put("k", "m", "beta question", payload, 1, 1);
        cache.put("k", "m", "gamma question", payload, 1, 1);

        assertEquals(2, cache.size(), "the burst cache must not exceed its entry cap");
        assertEquals(1, ((Number) cache.stats().get("evictions")).intValue());
        assertNotNull(cache.find("k", "m", "gamma question", 0.5));

        clock.advanceSeconds(61);
        assertNull(cache.find("k", "m", "gamma question", 0.5));
        assertEquals(0, cache.size(), "expired entries must be removed on the next lookup");
    }

    @Test
    void cacheStatsExposeMemoryLimitsAndDailySchedule() {
        Map<String, Object> stats = prism.cacheStats();
        assertTrue(stats.get("memory") instanceof Map);
        assertTrue(stats.get("limits") instanceof Map);
        Map<?, ?> schedule = (Map<?, ?>) stats.get("schedule");
        assertEquals("Asia/Kolkata", schedule.get("zone"));
        assertEquals("0 0 17 * * *", schedule.get("cron"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        String body = "{\"id\":\"chatcmpl_cache\",\"object\":\"chat.completion\",\"model\":\"alpha-small\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"cached answer "
                + calls.get() + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, PrismService.Provider> providerMap() {
        return (Map<String, PrismService.Provider>) ReflectionTestUtils.getField(prism, "providers");
    }

    private PrismService.Tenant tenant(String key, boolean cacheEnabled) {
        return new PrismService.Tenant(key, "cache-test", 50, 60, 0, List.of("alpha-small"),
                cacheEnabled, 0.92, "auto");
    }

    private Map<String, Object> body(String model, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return body;
    }

    private String key() {
        String key = "prism-sk-cache-" + UUID.randomUUID().toString().replace("-", "");
        keys.add(key);
        return key;
    }

    private int countEntries(String key) {
        return db.queryForObject("SELECT COUNT(*) FROM cache_entries WHERE virtual_key=?", Integer.class, key);
    }

    private int countHits(String key) {
        return db.queryForObject("SELECT COUNT(*) FROM cache_hits WHERE virtual_key=?", Integer.class, key);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
