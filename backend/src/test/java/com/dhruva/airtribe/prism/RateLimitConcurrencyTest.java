package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RateLimitConcurrencyTest {
    @Autowired PrismService prism;
    @Test void concurrentBurstNeverAdmitsMoreThanConfiguredRpm() throws Exception {
        PrismService.Tenant free=prism.tenantList().stream().filter(t->t.team().equals("free-tier")).findFirst().orElseThrow();
        int total=30; ExecutorService pool=Executors.newFixedThreadPool(10);CountDownLatch start=new CountDownLatch(1);AtomicInteger accepted=new AtomicInteger(),limited=new AtomicInteger();
        for(int i=0;i<total;i++)pool.submit(()->{try{start.await();prism.admit(free,"fast");accepted.incrementAndGet();}catch(PrismService.GatewayException ex){if(ex.type.equals("rate_limit_exceeded"))limited.incrementAndGet();}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
        start.countDown();pool.shutdown();pool.awaitTermination(10,TimeUnit.SECONDS);
        assertEquals(10,accepted.get());assertEquals(20,limited.get());
    }
}
