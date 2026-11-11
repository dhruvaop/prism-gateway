package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Virtual keys must behave as routing middleware: two keys served by the same
 * gateway process can resolve to different providers without changing global
 * configuration. Fake upstreams stand in for OpenRouter and Gemini.
 */
@SpringBootTest(properties = {
        "openrouter.api-key=test-openrouter-key",
        "openrouter.prefer-as-primary=false",
        "openrouter.model=nvidia/nemotron-3.5-lightning:free",
        "gemini.api-key=test-gemini-key",
        "gemini.prefer-as-primary=false",
        "gemini.model=gemini-3.1-flash-lite"
})
@AutoConfigureMockMvc
class KeyRoutingProfileTest {
    private static final AtomicInteger OPENROUTER_CALLS = new AtomicInteger();
    private static final AtomicInteger GEMINI_CALLS = new AtomicInteger();
    private static final AtomicReference<String> OPENROUTER_MODEL = new AtomicReference<>();
    private static final AtomicReference<String> GEMINI_MODEL = new AtomicReference<>();
    private static final HttpServer OPENROUTER = fakeProvider("nvidia/nemotron-3.5-lightning:free", OPENROUTER_CALLS, OPENROUTER_MODEL);
    private static final HttpServer GEMINI = fakeProvider("gemini-3.1-flash-lite", GEMINI_CALLS, GEMINI_MODEL);

    private static HttpServer fakeProvider(String model, AtomicInteger calls, AtomicReference<String> seenModel) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                seenModel.set(readModel(exchange));
                String reply = "{\"id\":\"chatcmpl_test\",\"model\":\"" + model + "\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"routed by key\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}";
                respond(exchange, reply);
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    /** Minimal extraction of the upstream model field; the body is small JSON. */
    private static String readModel(HttpExchange exchange) {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int at = body.indexOf("\"model\"");
            if (at < 0) return "";
            int first = body.indexOf('"', at + 7);
            int second = body.indexOf('"', first + 1);
            return first < 0 || second < 0 ? "" : body.substring(first + 1, second);
        } catch (IOException ex) {
            return "";
        }
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("openrouter.base-url", () -> "http://127.0.0.1:" + OPENROUTER.getAddress().getPort() + "/v1");
        registry.add("gemini.base-url", () -> "http://127.0.0.1:" + GEMINI.getAddress().getPort() + "/v1");
    }

    @Autowired PrismService prism;
    @Autowired ManagedAccessService managed;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;

    private final List<String> keys = new ArrayList<>();
    private final List<Long> users = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String key : keys) {
            db.update("DELETE FROM request_logs WHERE virtual_key=?", key);
            db.update("DELETE FROM usage_monthly WHERE virtual_key=?", key);
            db.update("DELETE FROM tenant_keys WHERE virtual_key=?", key);
        }
        keys.clear();
        for (Long id : users) {
            db.update("DELETE FROM tenant_keys WHERE user_id=?", id);
            db.update("DELETE FROM access_users WHERE id=?", id);
        }
        users.clear();
        OPENROUTER_CALLS.set(0);
        GEMINI_CALLS.set(0);
    }

    @Test
    void openRouterProfileSendsAutoRequestsToOpenRouter() {
        PrismService.Tenant tenant = tenant("openrouter");
        PrismService.Result result = prism.complete(tenant, body("auto"));

        assertTrue(result.provider().startsWith("openrouter/"), "expected the OpenRouter provider, got " + result.provider());
        assertEquals("nvidia/nemotron-3.5-lightning:free", result.model());
        assertEquals(1, OPENROUTER_CALLS.get(), "the key's OpenRouter profile must receive one request");
        assertEquals(0, GEMINI_CALLS.get(), "a key pinned to OpenRouter must not silently use Gemini first");
        assertTrue(result.routeReason().contains("routing profile openrouter"), result.routeReason());
        assertTrue(String.valueOf(result.payload()).contains("routed by key"));
    }

    @Test
    void geminiProfileSendsExplicitAliasToGemini() {
        PrismService.Tenant tenant = tenant("gemini");
        PrismService.Result result = prism.complete(tenant, body("fast"));

        assertTrue(result.provider().startsWith("gemini/"), "expected the Gemini provider, got " + result.provider());
        assertEquals("gemini-3.1-flash-lite", result.model());
        assertEquals(1, GEMINI_CALLS.get(), "the key's Gemini profile must receive one request");
        assertEquals(0, OPENROUTER_CALLS.get(), "a key pinned to Gemini must not silently use OpenRouter first");
        assertTrue(result.routeReason().contains("routing profile gemini"), result.routeReason());
    }

    @Test
    void autoProfileKeepsTheGlobalMockFirstBehaviour() {
        PrismService.Tenant tenant = tenant("auto");
        assertEquals("auto", prism.effectiveRoutingProfile(tenant));
        assertEquals("openrouter", prism.effectiveRoutingProfile(tenant("openrouter")));
        assertEquals("gemini", prism.effectiveRoutingProfile(tenant("gemini")));
        assertEquals("mock", prism.effectiveRoutingProfile(tenant("mock")));
        assertEquals("auto", prism.effectiveRoutingProfile(tenant("not-configured")));
    }

    @Test
    void adminCanReassignTheRoutingMiddlewareForAKey() throws Exception {
        String adminEmail = "routing" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.test";
        auth.signUp("Routing Admin", adminEmail, "RoutingPass!2026");
        String token = auth.signIn(adminEmail, "RoutingPass!2026").token();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, Object> user = managed.createUser(Map.of(
                "name", "Routing User",
                "username", "route" + suffix,
                "email", "route" + suffix + "@example.test",
                "password", "RoutingPass!2026",
                "team", "Self-serve",
                "access_mode", "both"));
        long userId = ((Number) user.get("user_id")).longValue();
        users.add(userId);
        String rawKey = String.valueOf(user.get("virtual_key"));
        keys.add(rawKey);
        String ref = ManagedAccessService.keyRef(rawKey);

        mvc.perform(patch("/admin/managed-keys/ref/" + ref)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"routing_profile\":\"gemini\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key.routing_profile").value("gemini"))
                .andExpect(jsonPath("$.key.key_ref").value(ref));

        mvc.perform(get("/admin/keys").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key_ref=='" + ref + "')].routing_profile").value("gemini"));

        mvc.perform(patch("/admin/managed-keys/ref/" + ref)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"routing_profile\":\"not-a-provider\"}"))
                .andExpect(status().isBadRequest());

        assertEquals("gemini", prism.effectiveRoutingProfile(prism.authenticate("Bearer " + rawKey)));
    }

    private PrismService.Tenant tenant(String profile) {
        String key = "prism-sk-routing-" + UUID.randomUUID().toString().replace("-", "");
        keys.add(key);
        return new PrismService.Tenant(key, "routing-test", 50, 60, 0, List.of("fast", "smart", "auto"), false, 0.92, profile);
    }

    private Map<String, Object> body(String model) {
        return Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", "hello there")));
    }
}
