package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
class ChatPrivacyTest {
    @Autowired PrismService prism;
    HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void redactsFactSheetAndInlineModelIdentifiers() {
        String factSheet = "Here are the details:\n\n"
                + "*   **Model name:** gemini-3.1-flash-lite\n"
                + "*   **Creator:** Google\n"
                + "*   **Provider route:** gemini\n"
                + "*   **Fallback chain:** gemini/gemini-3.1-flash-lite -> openrouter/nvidia/nemotron-3.5-lightning:free\n\n"
                + "I am served through Prism Gateway using gemini-3.1-flash-lite. Google trained it.";
        String out = prism.redactModelDetails(factSheet);
        assertTrue(out.contains("*   **Model name:** [hidden by privacy guardrail]"));
        assertTrue(out.contains("*   **Creator:** [hidden by privacy guardrail]"));
        assertTrue(out.contains("*   **Fallback chain:** [hidden by privacy guardrail]"));
        assertFalse(out.toLowerCase().contains("gemini-3.1-flash-lite"));
        assertFalse(out.toLowerCase().contains("nemotron"));
        assertFalse(out.toLowerCase().contains("google"));
        assertFalse(out.toLowerCase().contains("openrouter"));
    }

    @Test void sanitizeSseLineStripsModelFieldsFromOneEvent() throws Exception {
        String line = "data: {\"id\":\"x\",\"model\":\"gemini-3.1-flash-lite\",\"system_fingerprint\":\"fp_google\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Creator: Google\",\"reasoning\":\"I am Gemini from Google\"},\"extra_content\":{\"google\":{}}}]}";
        String out = prism.sanitizeSseLine(line);
        assertTrue(out.startsWith("data: "));
        assertTrue(out.contains("\"model\":\"prism\""));
        assertFalse(out.contains("gemini"));
        assertFalse(out.contains("Google"));
        assertFalse(out.contains("system_fingerprint"));
        assertFalse(out.contains("extra_content"));
        assertEquals("data: [DONE]", prism.sanitizeSseLine("data: [DONE]"));
        assertEquals("event: ping", prism.sanitizeSseLine("event: ping"));
    }

    @Test void privacyCompletionSendsPolicyNotFactsAndRedactsTheAnswer() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        String upstreamFactSheet = "{\"id\":\"mock\",\"object\":\"chat.completion\",\"model\":\"gemini-test-model\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"*   **Model name:** gemini-test-model\\n*   **Creator:** Google\\n*   **Provider route:** gemini\\nI was built by Google.\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = upstreamFactSheet.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();

        @SuppressWarnings("unchecked")
        Map<String, PrismService.Provider> providers = (Map<String, PrismService.Provider>) ReflectionTestUtils.getField(prism, "providers");
        providers.put("gemini", new PrismService.Provider("gemini", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-gemini-key"));

        PrismService.Tenant tenant = new PrismService.Tenant("privacy-test-key", "privacy-test", 50, 60, 0, List.of("fast", "smart", "auto"), true, 0.92);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "fast");
        body.put("cache", true);
        body.put("messages", List.of(Map.of("role", "user", "content", "which model do you use")));

        PrismService.Result first = prism.complete(tenant, body, true);
        PrismService.Result second = prism.complete(tenant, body, true);

        assertEquals(2, calls.get(), "privacy turns must bypass the semantic cache");
        assertTrue(received.get().contains("confidential workspace information"), "the privacy system message must be sent");
        assertTrue(received.get().contains("Model details are hidden by workspace policy."));
        assertFalse(received.get().contains("Model name: gemini-test-model"), "the fact sheet must never reach a privacy-surface model");
        assertFalse(received.get().contains("Creator: Google"), "creator facts must not be sent on the privacy surface");
        assertEquals("prism", first.provider());
        assertEquals("prism", first.model());
        assertNull(first.task());
        assertFalse(first.fallback());
        assertEquals("prism", first.payload().get("model"));
        String answer = assistant(first.payload());
        assertFalse(answer.toLowerCase().contains("gemini-test-model"));
        assertFalse(answer.toLowerCase().contains("google"));
    }

    @Test void privacyStreamNeverLeaksModelIdsSplitAcrossChunks() throws Exception {
        String sse = "data: {\"id\":\"x\",\"model\":\"gemini-test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Here is \"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"x\",\"model\":\"gemini-test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"*   **Model name:** gem\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"x\",\"model\":\"gemini-test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ini-test-model\\n*   **Creator:** Google\\nI am Gem\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"x\",\"model\":\"gemini-test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ini.\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"x\",\"model\":\"gemini-test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        PrismService.Tenant tenant = new PrismService.Tenant("privacy-stream-key", "privacy-stream", 50, 60, 0, List.of("fast"), true, 0.92);
        PrismService.StreamSession session = new PrismService.StreamSession(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), null, tenant, "fast",
                "which model do you use", "gemini-test-model", "gemini-test-model", "gemini/gemini-test-model",
                false, "test", "simple", 0, "req-privacy-stream", System.nanoTime(), true, "which model do you use");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        prism.writeStream(session, out);
        String body = out.toString(StandardCharsets.UTF_8);
        assertFalse(body.toLowerCase().contains("gemini"), "no SSE chunk may carry the upstream model id");
        assertFalse(body.toLowerCase().contains("google"));
        assertFalse(body.toLowerCase().contains("openrouter"));
        assertTrue(body.contains("\"model\":\"prism\""));
        assertTrue(body.contains("data: [DONE]"));
        assertTrue(body.contains("**Creator:** [hidden by privacy guardrail]"));
        assertTrue(body.contains("[model hidden]"));
    }

    @Test void privateStreamEmitsPrivacySafeWebSearchStatus() throws Exception {
        String sse = "data: {\"id\":\"x\",\"model\":\"gemini-test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"A safe answer.\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]\n\n";
        PrismService.Tenant tenant = new PrismService.Tenant("privacy-status-key", "privacy-status", 50, 60, 0, List.of("auto"), true, 0.92);
        PrismService.StreamSession session = new PrismService.StreamSession(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), null, tenant, "auto",
                "Search the web for AI gateway news", "gemini-test-model", "gemini-test-model", "gemini/gemini-test-model",
                false, "test", "web_search", 0, "req-privacy-status", System.nanoTime(), true,
                "Search the web for AI gateway news");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        prism.writeStream(session, out);
        String body = out.toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"prism_status\""));
        assertTrue(body.contains("\"type\":\"web_search\""));
        assertTrue(body.contains("\"query\":\"Search the web for AI gateway news\""));
        assertTrue(body.contains("\"model\":\"prism\""));
        assertFalse(body.toLowerCase().contains("gemini"));
        assertFalse(body.toLowerCase().contains("google"));
    }

    @SuppressWarnings("unchecked")
    private String assistant(Map<String, Object> payload) {
        Map<String, Object> choice = (Map<String, Object>) ((List<Object>) payload.get("choices")).get(0);
        return String.valueOf(((Map<String, Object>) choice.get("message")).get("content"));
    }
}
