package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Architectural tests verifying adherence to SOLID principles across
 * BillingService, PaymentGateway, EntitlementService, and EmailService.
 */
class SolidDesignPrinciplesTest {

    private void createSchema(JdbcTemplate db) {
        db.execute("CREATE TABLE IF NOT EXISTS access_users ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, username TEXT, "
                + "email TEXT, password_hash TEXT, team_id INTEGER, access_mode TEXT NOT NULL DEFAULT 'both', "
                + "virtual_key TEXT, enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL DEFAULT '', "
                + "trial_requests_used INTEGER NOT NULL DEFAULT 0, trial_webhook_sent_at TEXT, self_signup INTEGER NOT NULL DEFAULT 0)");

        db.execute("CREATE TABLE IF NOT EXISTS subscriptions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, plan TEXT NOT NULL, "
                + "status TEXT NOT NULL, amount_paise INTEGER NOT NULL, currency TEXT NOT NULL, "
                + "razorpay_order_id TEXT UNIQUE, razorpay_payment_id TEXT UNIQUE, starts_at TEXT, ends_at TEXT, created_at TEXT NOT NULL)");

        db.execute("CREATE TABLE IF NOT EXISTS password_resets ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, token_hash TEXT NOT NULL UNIQUE, user_type TEXT NOT NULL, "
                + "user_id TEXT NOT NULL, email TEXT NOT NULL, expires_at TEXT NOT NULL, used_at TEXT, created_at TEXT NOT NULL)");
    }

    /**
     * SRP & DIP: BillingService depends on PaymentGateway abstraction,
     * not direct HTTP or vendor SDKs. Core domain logic executes cleanly with any gateway.
     */
    @Test
    void billingServiceDelegatesToPaymentGatewayAbstraction() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var db = new JdbcTemplate(ds);
        createSchema(db);

        db.update("INSERT INTO access_users (id, name, email) VALUES (1, 'Test User', 'user@example.com')");

        PaymentGateway mockGateway = mock(PaymentGateway.class);
        when(mockGateway.isConfigured()).thenReturn(true);
        when(mockGateway.createOrder(eq(1L), eq("pro"), eq(49900), eq("INR"), anyString(), anyMap()))
                .thenReturn(Map.of("order_id", "mock_ord_101", "amount", 49900, "plan", "pro"));

        BillingService billing = new BillingService(
                db, mockGateway, 5, 30, 49900, 199900, "INR", "Prism", "", "", "");

        Map<String, Object> order = billing.createOrder(1L, "pro");

        assertNotNull(order);
        assertEquals("mock_ord_101", order.get("order_id"));
        verify(mockGateway).createOrder(eq(1L), eq("pro"), eq(49900), eq("INR"), eq("Prism"), anyMap());

        // Verify that database row was inserted
        Map<String, Object> row = db.queryForMap("SELECT * FROM subscriptions WHERE razorpay_order_id = 'mock_ord_101'");
        assertEquals("pro", row.get("plan"));
        assertEquals("pending", row.get("status"));
    }

    /**
     * OCP: An alternative payment gateway (e.g. Stripe or Custom) can be plugged in
     * without modifying BillingService.
     */
    @Test
    void openClosedPrincipleAllowsPluggingAlternativePaymentGateways() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var db = new JdbcTemplate(ds);
        createSchema(db);

        db.update("INSERT INTO access_users (id, name, email) VALUES (2, 'Stripe User', 'stripe@example.com')");

        // Custom alternative gateway
        PaymentGateway alternativeGateway = new PaymentGateway() {
            @Override public String providerName() { return "custom-stripe"; }
            @Override public boolean isConfigured() { return true; }
            @Override public String keyId() { return "pk_live_stripe123"; }
            @Override public Map<String, Object> createOrder(long userId, String planId, int amountPaise, String currency, String checkoutName, Map<String, Object> user) {
                return Map.of("order_id", "stripe_pi_789", "amount", amountPaise, "currency", currency, "provider", providerName());
            }
            @Override public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) { return true; }
            @Override public boolean verifyWebhookSignature(String rawBody, String signature) { return true; }
        };

        BillingService billing = new BillingService(
                db, alternativeGateway, 5, 30, 49900, 199900, "INR", "Prism", "", "", "");

        Map<String, Object> status = billing.status(2L);
        assertEquals("pk_live_stripe123", status.get("razorpay_key_id"));
        assertTrue((Boolean) status.get("enabled"));

        Map<String, Object> order = billing.createOrder(2L, "pro");
        assertEquals("stripe_pi_789", order.get("order_id"));
        assertEquals("custom-stripe", order.get("provider"));
    }

    /**
     * ISP: Consumers requiring only quota checking (e.g., EntitlementService)
     * are decoupled from payment checkout and webhook management.
     */
    @Test
    void interfaceSegregationSegregatesEntitlementFromCheckoutOperations() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var db = new JdbcTemplate(ds);
        createSchema(db);

        db.update("INSERT INTO access_users (id, name, email, trial_requests_used, self_signup) VALUES (5, 'Chat User', 'chat@example.com', 2, 1)");

        PaymentGateway mockGateway = mock(PaymentGateway.class);
        BillingService billing = new BillingService(
                db, mockGateway, 5, 30, 49900, 199900, "INR", "Prism", "", "", "");

        // Consumer only interacting with EntitlementService contract
        EntitlementService entitlementService = billing;
        BillingService.Entitlement ent = entitlementService.entitlement(5L);
        assertTrue(ent.allowed());
        assertEquals(5, ent.trialLimit());
        assertEquals(2, ent.trialUsed());
        assertEquals(3, ent.trialRemaining());

        assertTrue(entitlementService.reserveRequest(5L));

        // Consumer only interacting with BillingOperations contract
        BillingOperations billingOperations = billing;
        Map<String, Object> status = billingOperations.status(5L);
        assertNotNull(status.get("plans"));
    }

    /**
     * DIP: PasswordResetService depends on EmailService abstraction,
     * allowing test/mock email dispatchers without real SMTP servers.
     */
    @Test
    void passwordResetServiceDecoupledFromConcreteMailService() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var db = new JdbcTemplate(ds);
        createSchema(db);

        db.update("INSERT INTO access_users (id, email, name) VALUES (1, 'reset@example.com', 'Reset User')");

        EmailService mockEmail = mock(EmailService.class);
        when(mockEmail.configured()).thenReturn(true);
        when(mockEmail.sendPasswordReset(eq("reset@example.com"), eq("Reset User"), anyString(), eq(30L)))
                .thenReturn(true);

        PasswordResetService resetService = new PasswordResetService(
                db, mockEmail, 30, 60, "http://chat.example.com", "http://console.example.com");

        PasswordResetService.ResetDelivery delivery = resetService.requestChatReset("reset@example.com");
        assertEquals("email", delivery.delivery());
        assertNull(delivery.resetLink()); // Link is emailed, not exposed in response

        verify(mockEmail).sendPasswordReset(eq("reset@example.com"), eq("Reset User"), anyString(), eq(30L));
    }
}
