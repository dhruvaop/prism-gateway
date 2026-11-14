package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Razorpay implementation of {@link PaymentGateway}.
 *
 * <p>Adheres to the <b>Single Responsibility Principle (SRP)</b> by isolating Razorpay REST API
 * communication, basic authentication, and HMAC-SHA256 signature verification.</p>
 */
@Component
public class RazorpayPaymentGateway implements PaymentGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

    private final String keyId;
    private final String keySecret;
    private final String razorpayBaseUrl;
    private final String webhookSecret;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    @Autowired
    public RazorpayPaymentGateway(
            @Value("${prism.billing.razorpay-key-id:}") String keyId,
            @Value("${prism.billing.razorpay-key-secret:}") String keySecret,
            @Value("${prism.billing.razorpay-base-url:https://api.razorpay.com/v1}") String razorpayBaseUrl,
            @Value("${prism.billing.razorpay-webhook-secret:}") String webhookSecret) {
        this(keyId, keySecret, razorpayBaseUrl, webhookSecret,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    RazorpayPaymentGateway(String keyId, String keySecret, String razorpayBaseUrl,
                           String webhookSecret, HttpClient http) {
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keySecret = keySecret == null ? "" : keySecret.trim();
        this.razorpayBaseUrl = (razorpayBaseUrl == null || razorpayBaseUrl.isBlank()
                ? "https://api.razorpay.com/v1"
                : razorpayBaseUrl.trim()).replaceAll("/+$", "");
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.http = http;
    }

    @Override
    public String providerName() {
        return "razorpay";
    }

    @Override
    public boolean isConfigured() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public Map<String, Object> createOrder(long userId, String planId, int amountPaise, String currency,
                                           String checkoutName, Map<String, Object> user) {
        if (!isConfigured()) {
            throw new IllegalStateException("Razorpay is not configured on this gateway.");
        }
        String receipt = "prism_" + userId + "_" + System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", amountPaise);
        request.put("currency", currency);
        request.put("receipt", receipt);
        request.put("notes", Map.of(
                "user_id", String.valueOf(userId),
                "plan", planId,
                "email", String.valueOf(user.get("email"))));

        try {
            HttpRequest order = HttpRequest.newBuilder(URI.create(razorpayBaseUrl + "/orders"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(order, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("razorpay_order_rejected status={}", response.statusCode());
                throw new IllegalStateException("Razorpay could not create the payment order. Check the gateway billing configuration.");
            }
            Map<String, Object> created = json.readValue(response.body(), new TypeReference<>() {});
            String orderId = String.valueOf(created.get("id"));

            String planTitle = planId.isEmpty() ? "Plan" : Character.toUpperCase(planId.charAt(0)) + planId.substring(1).toLowerCase(Locale.ROOT);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("order_id", orderId);
            out.put("amount", amountPaise);
            out.put("currency", currency);
            out.put("key_id", keyId);
            out.put("name", checkoutName);
            out.put("description", planTitle + " · monthly subscription");
            out.put("plan", planId);
            out.put("prefill", Map.of(
                    "name", String.valueOf(user.get("name")),
                    "email", String.valueOf(user.get("email"))));
            out.put("notes", Map.of("user_id", String.valueOf(userId), "plan", planId));
            return out;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("razorpay_order_failed userId={} reason={}", userId, ex.getClass().getSimpleName());
            throw new IllegalStateException("Razorpay could not create the payment order. Try again in a moment.");
        }
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        if (keySecret.isBlank()) {
            throw new IllegalStateException("Razorpay is not configured on this gateway.");
        }
        return constantTimeEquals(hmacHex(keySecret, orderId + "|" + paymentId), signature);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        if (webhookSecret.isBlank()) {
            throw new IllegalStateException("The Razorpay webhook secret is not configured.");
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        return constantTimeEquals(hmacHex(webhookSecret, rawBody), signature.trim());
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    }

    static String hmacHex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign the billing payload.", ex);
        }
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
