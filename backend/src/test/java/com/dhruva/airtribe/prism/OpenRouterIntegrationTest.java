package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest @AutoConfigureMockMvc
class OpenRouterIntegrationTest {
    static final String TEST_KEY="test-only-openrouter-key";
    static final String MODEL="nvidia/nemotron-3.5-lightning:free";
    static final AtomicReference<String> authorization=new AtomicReference<>();
    static final AtomicReference<String> requestBody=new AtomicReference<>();
    static final HttpServer server=startServer();
    static HttpServer startServer(){try{HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.createContext("/api/v1/chat/completions",e->{authorization.set(e.getRequestHeaders().getFirst("Authorization"));String body=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requestBody.set(body);byte[] out;if(body.contains("\"stream\":true")){out=("data: {\"id\":\"or-stream\",\"choices\":[{\"delta\":{\"content\":\"The capital is Paris.\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"or-stream\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/event-stream");}else{out=("{\"id\":\"or-test\",\"object\":\"chat.completion\",\"model\":\""+MODEL+"\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"The capital is Paris.\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":5,\"total_tokens\":12}}").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");}e.sendResponseHeaders(200,out.length);e.getResponseBody().write(out);e.close();});s.start();return s;}catch(Exception ex){throw new RuntimeException(ex);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("openrouter.api-key",()->TEST_KEY);r.add("openrouter.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1");r.add("openrouter.prefer-as-primary",()->true);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @AfterAll static void stop(){server.stop(0);}
    @Test void configuredOpenRouterReceivesThePromptAndReturnsItsText() throws Exception {
        MvcResult first=mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer prism-sk-research-4d5e6f").content("{\"model\":\"fast\",\"messages\":[{\"role\":\"user\",\"content\":\"Who is Modi?\"}]}"))
                .andReturn();MvcResult result=first.getRequest().isAsyncStarted()?mvc.perform(asyncDispatch(first)).andReturn():first;
        assertEquals(200,result.getResponse().getStatus());assertEquals("openrouter/"+MODEL,result.getResponse().getHeader("x-prism-provider"));assertEquals("0.000000000",result.getResponse().getHeader("x-prism-cost-usd"));
        JsonNode body=json.readTree(result.getResponse().getContentAsByteArray());assertEquals("The capital is Paris.",body.at("/choices/0/message/content").asText());assertEquals(7,body.at("/usage/prompt_tokens").asInt());
        assertEquals("Bearer "+TEST_KEY,authorization.get());JsonNode forwarded=json.readTree(requestBody.get());assertEquals(MODEL,forwarded.path("model").asText());
    }
    @Test void playgroundStreamingRequestUsesOpenRouterAndReturnsServerSentEvents() throws Exception {
        MvcResult first=mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer prism-sk-research-4d5e6f").content("{\"model\":\"fast\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Who is Modi?\"}] }" )).andReturn();
        MvcResult result=first.getRequest().isAsyncStarted()?mvc.perform(asyncDispatch(first)).andReturn():first;
        assertEquals(200,result.getResponse().getStatus());assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE,result.getResponse().getContentType());assertTrue(result.getResponse().getContentAsString().contains("The capital is Paris."));assertTrue(result.getResponse().getContentAsString().contains("data: [DONE]"));
        JsonNode forwarded=json.readTree(requestBody.get());assertTrue(forwarded.path("stream").asBoolean());assertTrue(forwarded.path("stream_options").path("include_usage").asBoolean());
    }
}
