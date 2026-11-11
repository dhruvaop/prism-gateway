package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Trial metering, Razorpay checkout, and subscription activation.
 *
 * <p>The Razorpay key secret never leaves the server. Only the public key id is
 * returned to the browser so Checkout can open an order that this service created.</p>
 */
@Service
public class BillingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BillingService.class);

    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final int trialRequests;
    private final int subscriptionDays;
    private final int proAmountPaise;
    private final int enterpriseAmountPaise;
    private final String currency;
    private final String keyId;
    private final String keySecret;
    private final String razorpayBaseUrl;
    private final String merchantId;
    private final String webhookSecret;
    private final String checkoutName;
    private final String trialWebhookUrl;
    private final String trialWebhookSecret;
    private final String chatUrl;

    public BillingService(JdbcTemplate db,
                          @Value("${prism.billing.trial-requests:5}") int trialRequests,
                          @Value("${prism.billing.subscription-days:30}") int subscriptionDays,
                          @Value("${prism.billing.pro-amount-paise:49900}") int proAmountPaise,
                          @Value("${prism.billing.enterprise-amount-paise:199900}") int enterpriseAmountPaise,
                          @Value("${prism.billing.currency:INR}") String currency,
                          @Value("${prism.billing.razorpay-key-id:}") String keyId,
                          @Value("${prism.billing.razorpay-key-secret:}") String keySecret,
                          @Value("${prism.billing.razorpay-base-url:https://api.razorpay.com/v1}") String razorpayBaseUrl,
                          @Value("${prism.billing.razorpay-merchant-id:}") String merchantId,
                          @Value("${prism.billing.razorpay-webhook-secret:}") String webhookSecret,
                          @Value("${prism.billing.checkout-name:Prism Gateway}") String checkoutName,
                          @Value("${prism.billing.trial-webhook-url:}") String trialWebhookUrl,
                          @Value("${prism.billing.trial-webhook-secret:}") String trialWebhookSecret,
                          @Value("${prism.app.chat-url:http://localhost:5173}") String chatUrl) {
        this.db = db;
        this.trialRequests = Math.max(0, trialRequests);
        this.subscriptionDays = Math.max(1, subscriptionDays);
        this.proAmountPaise = Math.max(100, proAmountPaise);
        this.enterpriseAmountPaise = Math.max(100, enterpriseAmountPaise);
        this.currency = currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase(Locale.ROOT);
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keySecret = keySecret == null ? "" : keySecret.trim();
        this.razorpayBaseUrl = (razorpayBaseUrl == null || razorpayBaseUrl.isBlank() ? "https://api.razorpay.com/v1" : razorpayBaseUrl.trim()).replaceAll("/+$", "");
        this.merchantId = merchantId == null ? "" : merchantId.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.checkoutName = checkoutName == null || checkoutName.isBlank() ? "Prism Gateway" : checkoutName.trim();
        this.trialWebhookUrl = trialWebhookUrl == null ? "" : trialWebhookUrl.trim();
        this.trialWebhookSecret = trialWebhookSecret == null ? "" : trialWebhookSecret.trim();
        this.chatUrl = chatUrl == null ? "" : chatUrl.trim().replaceAll("/+$", "");
    }

    public record Plan(String id, String name, int amountPaise, int days) {}
    public record Entitlement(boolean allowed, boolean subscribed, boolean unlimited, int trialLimit, int trialUsed, int trialRemaining) {}

    public List<Plan> plans() {
        return List.of(new Plan("pro", "Pro", proAmountPaise, subscriptionDays),
                new Plan("enterprise", "Enterprise", enterpriseAmountPaise, subscriptionDays));
    }

    public Plan plan(String id) {
        String clean = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return plans().stream().filter(p -> p.id().equals(clean)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose the pro or enterprise plan."));
    }

    public Map<String, Object> status(long userId) {
        Entitlement entitlement = entitlement(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unlimited", entitlement.unlimited());
        out.put("trial", Map.of("limit", entitlement.trialLimit(), "used", entitlement.trialUsed(), "remaining", entitlement.trialRemaining()));
        out.put("subscription", activeSubscription(userId));
        out.put("plans", plans().stream().map(this::publicPlan).toList());
        out.put("currency", currency);
        out.put("razorpay_key_id", keyId);
        out.put("enabled", checkoutEnabled());
        return out;
    }

    public Entitlement entitlement(long userId) {
        Map<String, Object> subscription = activeSubscription(userId);
        boolean subscribed = subscription != null;
        // Accounts provisioned by an administrator are unlimited. Only public
        // self-signups go through the five-request demo and the paywall.
        if (!selfSignup(userId)) return new Entitlement(true, subscribed, true, 0, 0, 0);
        int used = trialUsed(userId);
        int remaining = Math.max(0, trialRequests - used);
        return new Entitlement(subscribed || remaining > 0, subscribed, false, trialRequests, used, remaining);
    }

    /** Counts the request that was just admitted. Only self-serve trials are metered. */
    public void recordRequest(long userId) {
        reserveRequest(userId);
    }

    /**
     * Atomically claims one demo request. The conditional UPDATE makes the
     * check-and-increment a single database operation, so parallel requests
     * cannot push a self-serve account past its free limit.
     */
    public boolean reserveRequest(long userId) {
        if (!selfSignup(userId)) return true;
        if (activeSubscription(userId) != null) return true;
        int updated = db.update("UPDATE access_users SET trial_requests_used=trial_requests_used+1 WHERE id=? AND trial_requests_used<?", userId, trialRequests);
        if (updated == 0) return false;
        int used = trialUsed(userId);
        if (used >= trialRequests) emitTrialExhausted(userId, used);
        return true;
    }

    public Map<String, Object> createOrder(long userId, String planId) {
        if (!checkoutEnabled()) throw new IllegalStateException("Razorpay is not configured on this gateway.");
        Plan plan = plan(planId);
        Map<String, Object> user = profile(userId);
        String receipt = "prism_" + userId + "_" + System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", plan.amountPaise());
        request.put("currency", currency);
        request.put("receipt", receipt);
        request.put("notes", Map.of("user_id", String.valueOf(userId), "plan", plan.id(), "email", String.valueOf(user.get("email"))));
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
            String now = Instant.now().toString();
            db.update("INSERT INTO subscriptions(user_id,plan,status,amount_paise,currency,razorpay_order_id,starts_at,ends_at,created_at) VALUES(?,?,'pending',?,?,?,?,?,?)",
                    userId, plan.id(), plan.amountPaise(), currency, orderId, now, now, now);
            LOGGER.info("razorpay_order_created userId={} plan={} amountPaise={} orderId={}", userId, plan.id(), plan.amountPaise(), orderId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("order_id", orderId);
            out.put("amount", plan.amountPaise());
            out.put("currency", currency);
            out.put("key_id", keyId);
            out.put("name", checkoutName);
            out.put("description", plan.name() + " · monthly subscription");
            out.put("plan", plan.id());
            out.put("prefill", Map.of("name", String.valueOf(user.get("name")), "email", String.valueOf(user.get("email"))));
            out.put("notes", Map.of("user_id", String.valueOf(userId), "plan", plan.id()));
            return out;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("razorpay_order_failed userId={} reason={}", userId, ex.getClass().getSimpleName());
            throw new IllegalStateException("Razorpay could not create the payment order. Try again in a moment.");
        }
    }

    public Map<String, Object> verifyPayment(long userId, Map<String, Object> body) {
        String orderId = text(body, "razorpay_order_id");
        String paymentId = text(body, "razorpay_payment_id");
        String signature = text(body, "razorpay_signature");
        if (orderId.isBlank() || paymentId.isBlank() || signature.isBlank()) throw new IllegalArgumentException("The Razorpay payment response is incomplete.");
        if (keySecret.isBlank()) throw new IllegalStateException("Razorpay is not configured on this gateway.");
        Map<String, Object> subscription;
        try {
            subscription = db.queryForMap("SELECT * FROM subscriptions WHERE razorpay_order_id=? AND user_id=?", orderId, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("This payment order does not belong to your account.");
        }
        if ("active".equals(String.valueOf(subscription.get("status")))) return status(userId);
        if (!constantTimeEquals(hmacHex(keySecret, orderId + "|" + paymentId), signature)) {
            LOGGER.warn("razorpay_signature_rejected userId={} orderId={}", userId, orderId);
            throw new IllegalArgumentException("Payment signature verification failed.");
        }
        activate(subscription, paymentId);
        LOGGER.info("razorpay_subscription_activated userId={} orderId={} paymentId={}", userId, orderId, paymentId);
        return status(userId);
    }

    /** Handles Razorpay's server-to-server webhook, verified with the webhook secret from the dashboard. */
    public void handleWebhook(String rawBody, String signature, String eventId) {
        if (webhookSecret.isBlank()) throw new IllegalStateException("The Razorpay webhook secret is not configured.");
        if (signature == null || signature.isBlank() || !constantTimeEquals(hmacHex(webhookSecret, rawBody), signature.trim())) {
            throw new IllegalArgumentException("Invalid Razorpay webhook signature.");
        }
        Map<String, Object> event;
        try {
            event = json.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Razorpay webhook payload.");
        }
        String type = text(event, "event");
        String id = eventId == null || eventId.isBlank() ? sha256(rawBody) : eventId.trim();
        try {
            db.update("INSERT INTO billing_events(event_id,provider,event_type,payload,created_at) VALUES(?,?,?,?,?)",
                    id, "razorpay", type, rawBody, Instant.now().toString());
        } catch (DuplicateKeyException duplicate) {
            return;
        } catch (Exception ex) {
            LOGGER.warn("billing_event_store_failed type={} reason={}", type, ex.getClass().getSimpleName());
        }
        if (!type.equals("payment.captured") && !type.equals("order.paid")) return;
        Map<String, Object> payment = entity(event, "payment");
        Map<String, Object> order = entity(event, "order");
        String orderId = firstNonBlank(text(payment, "order_id"), text(order, "id"));
        String paymentId = text(payment, "id");
        long paid = number(payment.get("amount")) > 0 ? number(payment.get("amount")) : number(order.get("amount_paid"));
        if (orderId.isBlank() || paymentId.isBlank()) return;
        Map<String, Object> subscription;
        try {
            subscription = db.queryForMap("SELECT * FROM subscriptions WHERE razorpay_order_id=?", orderId);
        } catch (EmptyResultDataAccessException ex) {
            LOGGER.warn("razorpay_webhook_order_unknown orderId={}", orderId);
            return;
        }
        long expected = number(subscription.get("amount_paise"));
        if (paid > 0 && paid < expected) {
            LOGGER.warn("razorpay_webhook_amount_mismatch orderId={} expected={} paid={}", orderId, expected, paid);
            return;
        }
        if (!"active".equals(String.valueOf(subscription.get("status")))) activate(subscription, paymentId);
        LOGGER.info("razorpay_webhook_activated type={} orderId={} paymentId={}", type, orderId, paymentId);
    }

    public Long userIdForKey(String virtualKey) {
        if (virtualKey == null || virtualKey.isBlank()) return null;
        try {
            return db.queryForObject("SELECT user_id FROM tenant_keys WHERE virtual_key=?", Long.class, virtualKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private void activate(Map<String, Object> subscription, String paymentId) {
        long id = number(subscription.get("id"));
        long userId = number(subscription.get("user_id"));
        Plan plan = plan(String.valueOf(subscription.get("plan")));
        Instant now = Instant.now();
        Instant start = now;
        Map<String, Object> current = activeSubscription(userId);
        if (current != null) {
            try {
                Instant end = Instant.parse(String.valueOf(current.get("ends_at")));
                if (end.isAfter(now)) start = end;
            } catch (Exception ignored) { }
        }
        Instant end = start.plus(plan.days(), ChronoUnit.DAYS);
        db.update("UPDATE subscriptions SET status='active',razorpay_payment_id=?,starts_at=?,ends_at=? WHERE id=? AND status='pending'",
                paymentId, start.toString(), end.toString(), id);
    }

    private Map<String, Object> activeSubscription(long userId) {
        try {
            Map<String, Object> row = db.queryForMap("SELECT plan,status,amount_paise,currency,starts_at,ends_at FROM subscriptions WHERE user_id=? AND status='active' AND ends_at>? ORDER BY ends_at DESC LIMIT 1",
                    userId, Instant.now().toString());
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.put("plan", String.valueOf(row.get("plan")));
            out.remove("status");
            return out;
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private int trialUsed(long userId) {
        try {
            Integer used = db.queryForObject("SELECT COALESCE(trial_requests_used,0) FROM access_users WHERE id=?", Integer.class, userId);
            return used == null ? 0 : used;
        } catch (Exception ex) {
            return 0;
        }
    }

    private boolean selfSignup(long userId) {
        try {
            Integer flag = db.queryForObject("SELECT COALESCE(self_signup,0) FROM access_users WHERE id=?", Integer.class, userId);
            return flag != null && flag == 1;
        } catch (Exception ex) {
            return false;
        }
    }

    private Map<String, Object> profile(long userId) {
        try {
            return db.queryForMap("SELECT name,email FROM access_users WHERE id=?", userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("Account not found.");
        }
    }

    private Map<String, Object> publicPlan(Plan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", plan.id());
        out.put("name", plan.name());
        out.put("amount_paise", plan.amountPaise());
        out.put("currency", currency);
        out.put("days", plan.days());
        return out;
    }

    private boolean checkoutEnabled() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    }

    /** Fires once when the last demo request is consumed, if an endpoint is configured. */
    private void emitTrialExhausted(long userId, int used) {
        String claimed = Instant.now().toString();
        int won = db.update("UPDATE access_users SET trial_webhook_sent_at=? WHERE id=? AND trial_webhook_sent_at IS NULL", claimed, userId);
        if (won == 0) return;
        if (trialWebhookUrl.isBlank()) {
            LOGGER.info("trial_webhook_skipped reason=not_configured userId={} used={}", userId, used);
            return;
        }
        CompletableFuture.runAsync(() -> sendTrialWebhook(userId, used));
    }

    private void sendTrialWebhook(long userId, int used) {
        try {
            Map<String, Object> user = profile(userId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "trial.exhausted");
            payload.put("user_id", userId);
            payload.put("name", user.get("name"));
            payload.put("email", user.get("email"));
            payload.put("trial_limit", trialRequests);
            payload.put("trial_used", used);
            payload.put("upgrade_url", chatUrl + "/chat.html");
            payload.put("timestamp", Instant.now().toString());
            String body = json.writeValueAsString(payload);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(trialWebhookUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .header("X-Prism-Event", "trial.exhausted")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (!trialWebhookSecret.isBlank()) request.header("X-Prism-Signature", "sha256=" + hmacHex(trialWebhookSecret, body));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("trial_webhook_failed userId={} status={}", userId, response.statusCode());
                db.update("UPDATE access_users SET trial_webhook_sent_at=NULL WHERE id=?", userId);
            } else {
                LOGGER.info("trial_webhook_delivered userId={} used={}", userId, used);
            }
        } catch (Exception ex) {
            LOGGER.warn("trial_webhook_failed userId={} reason={}", userId, ex.getClass().getSimpleName());
            db.update("UPDATE access_users SET trial_webhook_sent_at=NULL WHERE id=?", userId);
        }
    }

    private Map<String, Object> entity(Map<String, Object> event, String name) {
        Object payload = event.get("payload");
        if (!(payload instanceof Map<?, ?> p)) return Map.of();
        Object wrapper = p.get(name);
        if (!(wrapper instanceof Map<?, ?> w)) return Map.of();
        Object entity = w.get("entity");
        if (!(entity instanceof Map<?, ?> e)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        e.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return UUID.randomUUID().toString();
        }
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }
}
