package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BudgetConcurrencyTest {
    @Autowired PrismService prism;
    @Autowired JdbcTemplate db;

    @Test void inFlightRequestCannotAllowConcurrentRequestPastMonthlyBudget() throws Exception {
        PrismService.Tenant tenant=prism.tenantList().stream().filter(t->t.team().equals("budget-demo")).findFirst().orElseThrow();
        String month=YearMonth.now(ZoneOffset.UTC).toString();
        db.update("INSERT INTO usage_monthly(virtual_key,month,team,requests,prompt_tokens,completion_tokens,cost_usd,cache_hits) VALUES(?,?,?,0,0,0,0,0) ON CONFLICT(virtual_key,month) DO UPDATE SET cost_usd=0",tenant.key(),month,tenant.team());
        PrismService.BudgetLease first=prism.acquireBudgetLease(tenant);
        prism.admit(tenant,"fast");
        CountDownLatch waiting=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<String> second=pool.submit(()->{waiting.countDown();try(PrismService.BudgetLease ignored=prism.acquireBudgetLease(tenant)){prism.admit(tenant,"fast");return "admitted";}catch(PrismService.GatewayException ex){return ex.type;}});
        assertTrue(waiting.await(2,TimeUnit.SECONDS));
        assertFalse(second.isDone());
        db.update("UPDATE usage_monthly SET cost_usd=? WHERE virtual_key=? AND month=?",tenant.budget(),tenant.key(),month);
        first.close();
        assertEquals("budget_exceeded",second.get(3,TimeUnit.SECONDS));
        pool.shutdownNow();
    }
}
