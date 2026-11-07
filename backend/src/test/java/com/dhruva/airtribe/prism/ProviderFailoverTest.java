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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ProviderFailoverTest {
    @Autowired PrismService prism;
    HttpServer server;
    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void retriesPrimaryWithBackoffThenFailsOverToConfiguredProvider() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{int n=calls.incrementAndGet();byte[] body;if(n<3){body="{\"error\":\"busy\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,body.length);}else{body=("{\"id\":\"mock\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);}exchange.getResponseBody().write(body);exchange.close();});server.start();
        @SuppressWarnings("unchecked") Map<String,PrismService.Provider> providers=(Map<String,PrismService.Provider>)ReflectionTestUtils.getField(prism,"providers");
        providers.put("alpha",new PrismService.Provider("alpha","http://127.0.0.1:1/v1","mock-alpha"));
        providers.put("beta",new PrismService.Provider("beta","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","mock-beta"));
        PrismService.Tenant tenant=(PrismService.Tenant)prism.tenantList().stream().filter(t->t.team().equals("research")).findFirst().orElseThrow();
        PrismService.Result result=prism.complete(tenant,Map.of("model","fast","messages",List.of(Map.of("role","user","content","test failover"))));
        assertEquals("beta/beta-small",result.provider());assertTrue(result.fallback());assertEquals(4,result.retries());assertEquals(3,calls.get());assertEquals(1.2e-6,result.cost(),1e-10);
    }
}
