package com.dhruva.airtribe.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RazorpayPaymentGatewayTest {

    private static HttpServer mockServer;
    private static int port;
    private static final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private static volatile int mockStatus = 200;
    private static volatile String mockResponseBody = "{\"id\":\"order_mock_999\",\"amount\":49900,\"currency\":\"INR\"}";

    @BeforeAll
    static void startServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.createContext("/orders", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] responseBytes = mockResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(mockStatus, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        mockServer.start();
        port = mockServer.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void providerNameIsRazorpay() {
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway("k", "s", "http://localhost", "w");
        assertEquals("razorpay", gateway.providerName());
    }

    @Test
    void isConfiguredReturnsTrueOnlyWhenKeyAndSecretArePresent() {
        RazorpayPaymentGateway configured = new RazorpayPaymentGateway("key_123", "secret_456", "http://localhost", "w");
        assertTrue(configured.isConfigured());
        assertEquals("key_123", configured.keyId());

        RazorpayPaymentGateway missingKey = new RazorpayPaymentGateway("", "secret_456", "http://localhost", "w");
        assertFalse(missingKey.isConfigured());

        RazorpayPaymentGateway missingSecret = new RazorpayPaymentGateway("key_123", "   ", "http://localhost", "w");
        assertFalse(missingSecret.isConfigured());
    }

    @Test
    void createOrderThrowsWhenNotConfigured() {
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway("", "", "http://localhost", "");
        assertThrows(IllegalStateException.class, () ->
                gateway.createOrder(1L, "pro", 49900, "INR", "Prism", Map.of("name", "Test", "email", "test@test.com")));
    }

    @Test
    void createOrderSendsCorrectPayloadAndReturnsCheckoutMap() {
        mockStatus = 200;
        mockResponseBody = "{\"id\":\"order_rzp_abc123\",\"amount\":49900,\"currency\":\"INR\"}";

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway(
                "rzp_key_test", "rzp_sec_test", "http://127.0.0.1:" + port, "wh_sec", client);

        Map<String, Object> order = gateway.createOrder(
                42L, "pro", 49900, "INR", "Prism Gateway", Map.of("name", "John Doe", "email", "john@example.com"));

        assertNotNull(order);
        assertEquals("order_rzp_abc123", order.get("order_id"));
        assertEquals(49900, order.get("amount"));
        assertEquals("INR", order.get("currency"));
        assertEquals("rzp_key_test", order.get("key_id"));
        assertEquals("Prism Gateway", order.get("name"));
        assertEquals("Pro · monthly subscription", order.get("description"));
        assertEquals("pro", order.get("plan"));

        assertTrue(lastAuthHeader.get().startsWith("Basic "));
        assertTrue(lastRequestBody.get().contains("\"amount\":49900"));
        assertTrue(lastRequestBody.get().contains("\"currency\":\"INR\""));
        assertTrue(lastRequestBody.get().contains("john@example.com"));
    }

    @Test
    void createOrderThrowsWhenGatewayReturnsErrorStatus() {
        mockStatus = 401;
        mockResponseBody = "{\"error\":{\"description\":\"Authentication failed\"}}";

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway(
                "bad_key", "bad_sec", "http://127.0.0.1:" + port, "wh_sec", client);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                gateway.createOrder(42L, "pro", 49900, "INR", "Prism", Map.of("name", "John", "email", "john@example.com")));
        assertTrue(ex.getMessage().contains("Razorpay could not create the payment order"));
    }

    @Test
    void verifyPaymentSignatureValidatesCorrectHmac() {
        String keySecret = "secret_xyz";
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway("key", keySecret, "http://localhost", "wh");

        String orderId = "order_123";
        String paymentId = "pay_456";
        String validSignature = RazorpayPaymentGateway.hmacHex(keySecret, orderId + "|" + paymentId);

        assertTrue(gateway.verifyPaymentSignature(orderId, paymentId, validSignature));
        assertFalse(gateway.verifyPaymentSignature(orderId, paymentId, "invalid_sig_abc"));
        assertFalse(gateway.verifyPaymentSignature(orderId, "wrong_payment_id", validSignature));
    }

    @Test
    void verifyPaymentSignatureThrowsWhenSecretBlank() {
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway("key", "", "http://localhost", "wh");
        assertThrows(IllegalStateException.class, () -> gateway.verifyPaymentSignature("ord", "pay", "sig"));
    }

    @Test
    void verifyWebhookSignatureValidatesPayloadAuthenticity() {
        String webhookSecret = "whsec_789";
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway("key", "sec", "http://localhost", webhookSecret);

        String payload = "{\"event\":\"payment.captured\",\"payload\":{}}";
        String validSignature = RazorpayPaymentGateway.hmacHex(webhookSecret, payload);

        assertTrue(gateway.verifyWebhookSignature(payload, validSignature));
        assertFalse(gateway.verifyWebhookSignature(payload + "tampered", validSignature));
        assertFalse(gateway.verifyWebhookSignature(payload, "bad_signature"));
        assertFalse(gateway.verifyWebhookSignature(payload, null));
        assertFalse(gateway.verifyWebhookSignature(payload, "   "));
    }

    @Test
    void verifyWebhookSignatureThrowsWhenWebhookSecretNotConfigured() {
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway("key", "sec", "http://localhost", "");
        assertThrows(IllegalStateException.class, () -> gateway.verifyWebhookSignature("{}", "sig"));
    }
}
