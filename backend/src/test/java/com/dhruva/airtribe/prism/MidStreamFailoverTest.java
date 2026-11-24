package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A provider that disconnects after emitting part of a stream must not leave
 * half-written SSE in the client response. The whole upstream stream is buffered
 * first, so the gateway can retry and fail over before writing any byte.
 */
@SpringBootTest
class MidStreamFailoverTest {
    @Autowired PrismService prism;

    HttpServer failing;
    HttpServer healthy;
    AtomicInteger failingCalls = new AtomicInteger();
    Map<String, PrismService.Provider> providers;
    Map<String, PrismService.Provider> original;

    @BeforeEach @SuppressWarnings("unchecked") void setUp() throws Exception {
        failing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        failing.createContext("/v1/chat/completions", exchange -> {
            failingCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] partial = ("data: {\"id\":\"broken\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial-from-alpha\"},\"finish_reason\":null}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            // Chunked response with no [DONE]: the provider disappears mid-stream.
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(partial);
            exchange.getResponseBody().flush();
            exchange.close();
        });
        failing.start();

        healthy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        healthy.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("data: {\"id\":\"ok\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"answer-from-beta\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"ok\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        healthy.start();

        providers = (Map<String, PrismService.Provider>) ReflectionTestUtils.getField(prism, "providers");
        original = new HashMap<>(providers);
        providers.put("alpha", new PrismService.Provider("alpha", "http://127.0.0.1:" + failing.getAddress().getPort() + "/v1", "mock-alpha"));
        providers.put("beta", new PrismService.Provider("beta", "http://127.0.0.1:" + healthy.getAddress().getPort() + "/v1", "mock-beta"));
        ProviderHealthMonitor health = (ProviderHealthMonitor) ReflectionTestUtils.getField(prism, "providerHealth");
        if (health != null) health.reset();
    }

    @AfterEach void tearDown() {
        if (failing != null) failing.stop(0);
        if (healthy != null) healthy.stop(0);
        if (providers != null && original != null) {
            providers.clear();
            providers.putAll(original);
        }
        ProviderHealthMonitor health = (ProviderHealthMonitor) ReflectionTestUtils.getField(prism, "providerHealth");
        if (health != null) health.reset();
    }

    @Test void truncatedUpstreamStreamFailsOverBeforeAnyClientBytesAreWritten() throws Exception {
        PrismService.Tenant tenant = prism.tenantList().stream()
                .filter(t -> t.team().equals("research")).findFirst().orElseThrow();
        Map<String, Object> body = Map.of("model", "fast", "stream", true,
                "messages", List.of(Map.of("role", "user", "content", "unique mid-stream disconnect failover test")));

        PrismService.StreamSession session = prism.beginStream(tenant, body);
        assertEquals("beta/beta-small", session.provider());
        assertTrue(session.fallback());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        prism.writeStream(session, out);
        String response = out.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("answer-from-beta"));
        assertTrue(response.endsWith("data: [DONE]\n\n"));
        assertFalse(response.contains("partial-from-alpha"), "the failed attempt must never reach the client");
        assertTrue(failingCalls.get() >= 1, "the truncated provider must actually have been attempted");
    }
}
