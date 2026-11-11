package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest @AutoConfigureMockMvc
class StreamingApiContractTest {
    @Autowired MockMvc mvc;@Autowired PrismService prism;HttpServer server;Map<String,PrismService.Provider> providers,original;
    @SuppressWarnings("unchecked") void startMock() throws Exception {
        providers=(Map<String,PrismService.Provider>)ReflectionTestUtils.getField(prism,"providers");original=new HashMap<>(providers);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",e->{e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);String data="data: {\"id\":\"mock\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\ndata: {\"id\":\"mock\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\ndata: [DONE]\n\n";e.getResponseBody().write(data.getBytes(StandardCharsets.UTF_8));e.close();});server.start();providers.put("alpha",new PrismService.Provider("alpha","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","mock-alpha"));ProviderHealthMonitor health=(ProviderHealthMonitor)ReflectionTestUtils.getField(prism,"providerHealth");if(health!=null)health.reset();
    }
    @AfterEach void restore(){if(server!=null)server.stop(0);if(original!=null){providers.clear();providers.putAll(original);}}
    @Test void streamEndpointReturnsHeaderContractAndSseDone() throws Exception {
        startMock();MvcResult first=mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer prism-sk-research-4d5e6f").content("{\"model\":\"fast\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"unique stream contract case\"}]}"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)).andExpect(header().string("x-prism-provider","alpha/alpha-small")).andExpect(header().string("x-prism-cache","miss")).andExpect(header().string("x-prism-fallback","false")).andExpect(header().string("x-prism-cost-usd","0.000000000")).andReturn();
        MvcResult complete=first.getRequest().isAsyncStarted()?mvc.perform(asyncDispatch(first)).andReturn():first;String body=complete.getResponse().getContentAsString();org.junit.jupiter.api.Assertions.assertTrue(body.contains("hello"));org.junit.jupiter.api.Assertions.assertTrue(body.endsWith("data: [DONE]\n\n"));
    }
}
