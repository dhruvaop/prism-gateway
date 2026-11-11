package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StreamingGatewayTest {
    @Autowired PrismService prism;
    HttpServer server; Map<String,PrismService.Provider> providers; Map<String,PrismService.Provider> original;
    @SuppressWarnings("unchecked") void installProvider() throws Exception {
        providers=(Map<String,PrismService.Provider>)ReflectionTestUtils.getField(prism,"providers");original=new HashMap<>(providers);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);var out=exchange.getResponseBody();String[] chunks={
                "data: {\"id\":\"mock\",\"choices\":[{\"delta\":{\"content\":\"token-1 \"},\"index\":0}]}\n\n",
                "data: {\"id\":\"mock\",\"choices\":[{\"delta\":{\"content\":\"token-2\"},\"index\":0}]}\n\n",
                "data: {\"id\":\"mock\",\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}\n\n",
                "data: [DONE]\n\n"};for(String c:chunks){out.write(c.getBytes(StandardCharsets.UTF_8));out.flush();try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}out.close();});server.start();
        providers.put("alpha",new PrismService.Provider("alpha","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","mock-alpha"));ProviderHealthMonitor health=(ProviderHealthMonitor)ReflectionTestUtils.getField(prism,"providerHealth");if(health!=null)health.reset();
    }
    @AfterEach void restore(){if(server!=null)server.stop(0);if(original!=null){providers.clear();providers.putAll(original);}}
    @Test void streamingForwardsEventsAndRecordsProviderUsageAndCost() throws Exception {
        installProvider();PrismService.Tenant tenant=prism.tenantList().stream().filter(t->t.team().equals("research")).findFirst().orElseThrow();
        Map<String,Object> body=Map.of("model","fast","stream",true,"messages",List.of(Map.of("role","user","content","stream accounting unique test")));
        PrismService.StreamSession session=prism.beginStream(tenant,body);ByteArrayOutputStream out=new ByteArrayOutputStream();prism.writeStream(session,out);String text=out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("token-1 "));assertTrue(text.contains("token-2"));assertTrue(text.endsWith("data: [DONE]\n\n"));assertTrue(text.indexOf("token-1")<text.indexOf("token-2"));
        Map<String,Object> row=prism.logs(tenant.key(),20).stream().filter(x->session.requestId().equals(x.get("request_id"))).findFirst().orElseThrow();
        assertEquals(9,((Number)row.get("prompt_tokens")).intValue());assertEquals(2,((Number)row.get("completion_tokens")).intValue());assertEquals(2.55e-6,((Number)row.get("cost_usd")).doubleValue(),1e-10);
    }
}
