package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A provider can answer 200 with a stream that contains only reasoning deltas and no
 * assistant text. The gateway must treat that as a failed attempt and fail over instead
 * of returning an empty answer to the chat UI.
 */
@SpringBootTest @AutoConfigureMockMvc
class EmptyReasoningStreamFallbackTest {
    static final String TEST_KEY = "test-empty-reasoning-key";
    static final HttpServer server = startServer();

    static HttpServer startServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/api/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String stream = "data: {\"id\":\"or-empty\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"thinking about it\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"or-empty\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n";
                byte[] out = stream.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            s.start();
            return s;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("openrouter.api-key", () -> TEST_KEY);
        r.add("openrouter.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
        r.add("openrouter.prefer-as-primary", () -> true);
    }

    @Autowired MockMvc mvc;

    @AfterAll
    static void stop() { server.stop(0); }

    @Test
    void reasoningOnlyStreamFailsOverToTheNextProvider() throws Exception {
        MvcResult first = mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer prism-sk-research-4d5e6f")
                        .content("{\"model\":\"fast\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"wherez ??\"}]}"))
                .andReturn();
        MvcResult result = first.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(first)).andReturn() : first;
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("alpha/alpha-small", result.getResponse().getHeader("x-prism-provider"));
        assertTrue(result.getResponse().getContentAsString().contains("data: [DONE]"));
        assertTrue(result.getResponse().getContentAsString().contains("[alpha:alpha-small]"));
    }
}
