package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "gemini.api-key=test-gemini-key",
        "gemini.prefer-as-primary=true",
        "gemini.model=gemini-test-model"
})
class GeminiUpstreamShapeTest {
    @Autowired PrismService prism;
    HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void stripsPrismOnlyControlsBeforeGemini() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"id\":\"mock\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"gemini-shape-ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();

        @SuppressWarnings("unchecked")
        Map<String, PrismService.Provider> providers = (Map<String, PrismService.Provider>) ReflectionTestUtils.getField(prism, "providers");
        providers.put("gemini", new PrismService.Provider("gemini", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-gemini-key"));

        PrismService.Tenant tenant = prism.tenantList().stream().filter(t -> t.team().equals("research")).findFirst().orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "fast");
        body.put("cache", false);
        body.put("verify", false);
        body.put("conversation_id", 42);
        body.put("messages", List.of(Map.of("role", "user", "content", "shape check " + UUID.randomUUID())));

        PrismService.Result result = prism.complete(tenant, body);
        String upstream = received.get();
        assertNotNull(upstream);
        assertTrue(upstream.contains("\"model\":\"gemini-test-model\""));
        assertFalse(upstream.contains("\"cache\""), "cache is a Prism-only control and Gemini rejects unknown fields");
        assertFalse(upstream.contains("\"verify\""), "verify is a Prism-only control and must not reach Gemini");
        assertFalse(upstream.contains("\"conversation_id\""), "conversation_id is a Prism-only control and must not reach Gemini");
        assertEquals("gemini/gemini-test-model", result.provider());
    }

    @Test void tellsGeminiItsExactRoutedModelForIdentityQuestionsAndSkipsCache() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"id\":\"mock\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"gemini-test-model\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();

        @SuppressWarnings("unchecked")
        Map<String, PrismService.Provider> providers = (Map<String, PrismService.Provider>) ReflectionTestUtils.getField(prism, "providers");
        providers.put("gemini", new PrismService.Provider("gemini", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-gemini-key"));

        PrismService.Tenant tenant = new PrismService.Tenant("identity-test-key", "identity-test", 50, 60, 0, List.of("fast", "smart", "auto"), true, 0.92);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "fast");
        body.put("cache", true);
        body.put("messages", List.of(Map.of("role", "user", "content", "which model do you use")));

        PrismService.Result first = prism.complete(tenant, body);
        PrismService.Result second = prism.complete(tenant, body);

        assertEquals(2, calls.get(), "identity questions must bypass the semantic cache");
        assertTrue(received.get().contains("\"role\":\"system\""));
        assertTrue(received.get().contains("gemini-test-model"), "the model must be told its exact routed name");
        assertTrue(received.get().contains("Creator: Google"), "the fact sheet must include the creator");
        assertTrue(received.get().contains("Family: Gemini"), "the fact sheet must include the family");
        assertTrue(received.get().contains("Variant: Test Model"), "the fact sheet must include the variant");
        assertTrue(received.get().contains("Gateway: Prism Gateway"), "the fact sheet must name the gateway");
        assertTrue(received.get().contains("API surface: POST /v1/chat/completions"), "the fact sheet must include the API surface");
        assertTrue(received.get().contains("Modalities: text input, text output"), "the fact sheet must include modalities");
        assertTrue(received.get().contains("Provider route: gemini"), "the fact sheet must include the provider route");
        assertTrue(received.get().contains("Tier: fast"), "the fact sheet must include the tier");
        assertTrue(received.get().contains("Task route: not applicable"), "explicit aliases have no auto task route");
        assertTrue(received.get().contains("Fallback chain:"), "the fact sheet must include the fallback chain");
        assertTrue(received.get().contains("Pricing:"), "the fact sheet must include pricing");
        assertTrue(received.get().contains("Context window / parameter count: not recorded"), "the fact sheet must be explicit about unknown metadata");
        assertEquals("gemini/gemini-test-model", first.provider());
        assertEquals("gemini/gemini-test-model", second.provider());

        body.put("messages", List.of(Map.of("role", "user", "content", "who created you")));
        prism.complete(tenant, body);
        assertEquals(3, calls.get(), "creator questions are identity questions and must bypass the cache");
        assertTrue(received.get().contains("Creator: Google"));
        assertTrue(received.get().contains("Model name: gemini-test-model"));

        body.put("cache", false);
        body.put("messages", List.of(Map.of("role", "user", "content", "what are you doing today " + UUID.randomUUID())));
        prism.complete(tenant, body);
        assertEquals(4, calls.get(), "a benign prompt must reach the upstream, not be answered from the identity-turn cache state");
        assertFalse(received.get().contains("\"role\":\"system\""), "a benign 'what are you doing' prompt is not a model-identity question");

        body.put("messages", List.of(Map.of("role", "user", "content", "shape check " + UUID.randomUUID())));
        prism.complete(tenant, body);
        assertFalse(received.get().contains("\"role\":\"system\""), "benign prompts must not receive the identity system message");
    }
}
