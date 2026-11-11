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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD coverage for the Razorpay order leg of checkout. A local fake replaces
 * api.razorpay.com so the test never touches live billing credentials.
 */
@SpringBootTest(properties = {
        "prism.billing.razorpay-key-id=rzp_test_key",
        "prism.billing.razorpay-key-secret=test-key-secret",
        "prism.billing.razorpay-webhook-secret=test-webhook-secret",
        "prism.billing.pro-amount-paise=49900",
        "prism.billing.enterprise-amount-paise=199900"
})
class BillingOrderTest {
    private static final AtomicReference<String> AUTH = new AtomicReference<>();
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final HttpServer RAZORPAY = startServer();

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/orders", exchange -> {
                AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String response = "{\"id\":\"order_test_123\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"created\"}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("prism.billing.razorpay-base-url", () -> "http://127.0.0.1:" + RAZORPAY.getAddress().getPort() + "/v1");
    }

    @Autowired BillingService billing;
    @Autowired ManagedAccessService managed;
    @Autowired JdbcTemplate db;

    private final List<Long> created = new ArrayList<>();

    @AfterEach void cleanUp() {
        for (Long id : created) {
            db.update("DELETE FROM subscriptions WHERE user_id=?", id);
            db.update("DELETE FROM tenant_keys WHERE user_id=?", id);
            db.update("DELETE FROM access_users WHERE id=?", id);
        }
        created.clear();
        AUTH.set(null);
        BODY.set(null);
    }

    @Test void createOrderReturnsCheckoutPayloadAndPersistsAPendingOrder() {
        long userId = createUser();
        Map<String, Object> order = billing.createOrder(userId, "pro");

        assertEquals("order_test_123", order.get("order_id"));
        assertEquals(49900, order.get("amount"));
        assertEquals("INR", order.get("currency"));
        assertEquals("rzp_test_key", order.get("key_id"));
        assertEquals("pro", order.get("plan"));
        assertNotNull(order.get("prefill"));
        assertFalse(order.containsKey("key_secret"));
        assertFalse(order.containsKey("razorpay_key_secret"));
        assertFalse(String.valueOf(order).contains("test-key-secret"));

        Map<String, Object> row = db.queryForMap(
                "SELECT plan,status,amount_paise,razorpay_order_id FROM subscriptions WHERE razorpay_order_id=?",
                "order_test_123");
        assertEquals("pro", row.get("plan"));
        assertEquals("pending", row.get("status"));
        assertEquals(49900, ((Number) row.get("amount_paise")).intValue());

        assertNotNull(AUTH.get(), "the order call must authenticate against Razorpay");
        assertTrue(AUTH.get().startsWith("Basic "));
        String decoded = new String(java.util.Base64.getDecoder().decode(AUTH.get().substring(6)), StandardCharsets.UTF_8);
        assertEquals("rzp_test_key:test-key-secret", decoded);
        assertTrue(BODY.get().contains("\"amount\":49900"));
        assertTrue(BODY.get().contains("\"plan\":\"pro\""));
    }

    @Test void createOrderRejectsAnUnknownPlanBeforeCallingRazorpay() {
        long userId = createUser();
        assertThrows(IllegalArgumentException.class, () -> billing.createOrder(userId, "platinum"));
        assertNull(AUTH.get(), "an invalid plan must not reach the payment provider");
    }

    private long createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, Object> user = managed.createUser(Map.of(
                "name", "Order Tester",
                "username", "ord" + suffix,
                "email", "ord" + suffix + "@example.test",
                "password", "OrderPass!2026",
                "team", "Self-serve",
                "access_mode", "both"));
        long id = ((Number) user.get("user_id")).longValue();
        created.add(id);
        return id;
    }
}
