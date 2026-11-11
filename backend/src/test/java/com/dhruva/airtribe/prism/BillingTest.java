package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "prism.billing.trial-requests=2",
        "prism.billing.razorpay-key-id=rzp_test_key",
        "prism.billing.razorpay-key-secret=test-key-secret",
        "prism.billing.razorpay-webhook-secret=test-webhook-secret"
})
class BillingTest {
    private static final HttpServer WEBHOOK;
    private static final AtomicInteger WEBHOOK_HITS = new AtomicInteger();
    private static final AtomicReference<String> WEBHOOK_BODY = new AtomicReference<>();
    private static final AtomicReference<String> WEBHOOK_SIGNATURE = new AtomicReference<>();

    static {
        try {
            WEBHOOK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            WEBHOOK.createContext("/trial", exchange -> {
                WEBHOOK_HITS.incrementAndGet();
                WEBHOOK_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                WEBHOOK_SIGNATURE.set(exchange.getRequestHeaders().getFirst("X-Prism-Signature"));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            WEBHOOK.start();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void webhookProperties(DynamicPropertyRegistry registry) {
        registry.add("prism.billing.trial-webhook-url", () -> "http://127.0.0.1:" + WEBHOOK.getAddress().getPort() + "/trial");
        registry.add("prism.billing.trial-webhook-secret", () -> "trial-webhook-secret");
    }

    @Autowired BillingService billing;
    @Autowired ManagedAccessService managed;
    @Autowired PrismService prism;
    @Autowired JdbcTemplate db;

    private final List<Long> created = new ArrayList<>();

    @AfterEach void cleanUp() {
        for (Long id : created) {
            db.update("DELETE FROM subscriptions WHERE user_id=?", id);
            db.update("DELETE FROM tenant_keys WHERE user_id=?", id);
            db.update("DELETE FROM access_users WHERE id=?", id);
        }
        created.clear();
    }

    @Test void demoEndsAfterTwoRequestsAndNotifiesTheWebhookOnce() throws Exception {
        Map<String, Object> signup = managed.selfSignup(Map.of(
                "name", "Trial Tester",
                "email", "trial-" + UUID.randomUUID() + "@example.test",
                "password", "TrialPass!2026"));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) signup.get("user");
        long id = ((Number) user.get("id")).longValue();
        created.add(id);
        String key = String.valueOf(db.queryForObject("SELECT virtual_key FROM access_users WHERE id=?", String.class, id));

        BillingService.Entitlement first = billing.entitlement(id);
        assertTrue(first.allowed());
        assertEquals(2, first.trialRemaining());
        assertTrue(billing.reserveRequest(id));
        assertTrue(billing.entitlement(id).allowed());
        assertEquals(1, billing.entitlement(id).trialRemaining());
        assertTrue(billing.reserveRequest(id));
        assertFalse(billing.entitlement(id).allowed());
        assertEquals(0, billing.entitlement(id).trialRemaining());

        long deadline = System.currentTimeMillis() + 6000;
        while (WEBHOOK_HITS.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, WEBHOOK_HITS.get(), "the trial-exhausted webhook must fire once");
        assertTrue(WEBHOOK_BODY.get().contains("\"event\":\"trial.exhausted\""));
        assertTrue(WEBHOOK_BODY.get().contains("\"trial_used\":2"));
        assertNotNull(WEBHOOK_SIGNATURE.get());
        assertTrue(WEBHOOK_SIGNATURE.get().startsWith("sha256="));
        billing.recordRequest(id);
        Thread.sleep(300);
        assertEquals(1, WEBHOOK_HITS.get(), "exhausted trials must not emit duplicate webhooks");
        prism.removeManagedTenant(key);
    }

    @Test void verifiesCheckoutSignatureAndActivatesTheSubscription() {
        long id = createUser();
        String order = "order_" + UUID.randomUUID().toString().replace("-", "");
        insertPending(id, order, "pro", 49900);
        String payment = "pay_" + UUID.randomUUID().toString().replace("-", "");
        String signature = BillingService.hmacHex("test-key-secret", order + "|" + payment);

        Map<String, Object> status = billing.verifyPayment(id, Map.of(
                "razorpay_order_id", order,
                "razorpay_payment_id", payment,
                "razorpay_signature", signature));

        @SuppressWarnings("unchecked")
        Map<String, Object> subscription = (Map<String, Object>) status.get("subscription");
        assertNotNull(subscription);
        assertEquals("pro", subscription.get("plan"));
        assertTrue(billing.entitlement(id).allowed());
        assertTrue(billing.entitlement(id).subscribed());
    }

    @Test void rejectsAnInvalidCheckoutSignature() {
        long id = createUser();
        String order = "order_bad_" + UUID.randomUUID().toString().replace("-", "");
        insertPending(id, order, "enterprise", 199900);
        assertThrows(IllegalArgumentException.class, () -> billing.verifyPayment(id, Map.of(
                "razorpay_order_id", order,
                "razorpay_payment_id", "pay_bad",
                "razorpay_signature", "not-a-valid-signature")));
        assertNull(billing.status(id).get("subscription"));
    }

    @Test void activatesFromASignedRazorpayWebhookIdempotently() {
        long id = createUser();
        String order = "order_webhook_" + UUID.randomUUID().toString().replace("-", "");
        insertPending(id, order, "enterprise", 199900);
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
                + "\"id\":\"pay_webhook_1\",\"order_id\":\"" + order + "\",\"amount\":199900,\"status\":\"captured\"}}}}";
        String signature = BillingService.hmacHex("test-webhook-secret", body);

        billing.handleWebhook(body, signature, "evt_" + UUID.randomUUID());
        assertTrue(billing.entitlement(id).subscribed());
        billing.handleWebhook(body, signature, "evt_" + UUID.randomUUID());
        assertTrue(billing.entitlement(id).subscribed());
    }

    private long createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, Object> created = managed.createUser(Map.of(
                "name", "Billing Tester",
                "username", "bill" + suffix,
                "email", "bill" + suffix + "@example.test",
                "password", "BillingPass!2026",
                "team", "Self-serve",
                "access_mode", "both"));
        long id = ((Number) created.get("user_id")).longValue();
        this.created.add(id);
        return id;
    }

    private void insertPending(long userId, String orderId, String plan, int amountPaise) {
        String now = Instant.now().toString();
        db.update("INSERT INTO subscriptions(user_id,plan,status,amount_paise,currency,razorpay_order_id,starts_at,ends_at,created_at) VALUES(?,?,'pending',?,?,?,?,?,?)",
                userId, plan, amountPaise, "INR", orderId, now, now, now);
    }
}
