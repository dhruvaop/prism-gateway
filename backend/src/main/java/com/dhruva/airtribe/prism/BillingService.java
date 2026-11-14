package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
 * Trial metering, payment order management, and subscription activation.
 *
 * <p>Adheres to SOLID principles:
 * <ul>
 *   <li><b>Single Responsibility Principle (SRP):</b> Manages user entitlements and subscription lifecycle,
 *       delegating payment gateway mechanics and cryptographic verification to {@link PaymentGateway}.</li>
 *   <li><b>Open/Closed Principle (OCP) &amp; Dependency Inversion Principle (DIP):</b> Operates against
 *       the {@link PaymentGateway} abstraction, enabling alternative payment processors (Stripe, etc.)
 *       to be supported without altering subscription domain rules.</li>
 *   <li><b>Interface Segregation Principle (ISP):</b> Implements {@link EntitlementService} (for consumers
 *       needing quota checks like ChatAppController) and {@link BillingOperations} (for checkout/webhook routes).</li>
 * </ul>
 * </p>
 */
@Service
public class BillingService implements EntitlementService, BillingOperations {
    private static final Logger LOGGER = LoggerFactory.getLogger(BillingService.class);

    private final JdbcTemplate db;
    private final PaymentGateway paymentGateway;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final int trialRequests;
    private final int subscriptionDays;
    private final int proAmountPaise;
    private final int enterpriseAmountPaise;
    private final String currency;
    private final String checkoutName;
    private final String trialWebhookUrl;
    private final String trialWebhookSecret;
    private final String chatUrl;

    @Autowired
    public BillingService(JdbcTemplate db,
                          PaymentGateway paymentGateway,
                          @Value("${prism.billing.trial-requests:5}") int trialRequests,
                          @Value("${prism.billing.subscription-days:30}") int subscriptionDays,
                          @Value("${prism.billing.pro-amount-paise:49900}") int proAmountPaise,
                          @Value("${prism.billing.enterprise-amount-paise:199900}") int enterpriseAmountPaise,
                          @Value("${prism.billing.currency:INR}") String currency,
                          @Value("${prism.billing.checkout-name:Prism Gateway}") String checkoutName,
                          @Value("${prism.billing.trial-webhook-url:}") String trialWebhookUrl,
                          @Value("${prism.billing.trial-webhook-secret:}") String trialWebhookSecret,
                          @Value("${prism.app.chat-url:http://localhost:5173}") String chatUrl) {
        this.db = db;
        this.paymentGateway = paymentGateway;
        this.trialRequests = Math.max(0, trialRequests);
        this.subscriptionDays = Math.max(1, subscriptionDays);
        this.proAmountPaise = Math.max(100, proAmountPaise);
        this.enterpriseAmountPaise = Math.max(100, enterpriseAmountPaise);
        this.currency = currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase(Locale.ROOT);
        this.checkoutName = checkoutName == null || checkoutName.isBlank() ? "Prism Gateway" : checkoutName.trim();
        this.trialWebhookUrl = trialWebhookUrl == null ? "" : trialWebhookUrl.trim();
        this.trialWebhookSecret = trialWebhookSecret == null ? "" : trialWebhookSecret.trim();
        this.chatUrl = chatUrl == null ? "" : chatUrl.trim().replaceAll("/+$", "");
    }

    /** Overload for programmatic construction preserving backwards compatibility. */
    public BillingService(JdbcTemplate db,
                          int trialRequests,
                          int subscriptionDays,
                          int proAmountPaise,
                          int enterpriseAmountPaise,
                          String currency,
                          String keyId,
                          String keySecret,
                          String razorpayBaseUrl,
                          String merchantId,
                          String webhookSecret,
                          String checkoutName,
                          String trialWebhookUrl,
                          String trialWebhookSecret,
                          String chatUrl) {
        this(db, new RazorpayPaymentGateway(keyId, keySecret, razorpayBaseUrl, webhookSecret),
                trialRequests, subscriptionDays, proAmountPaise, enterpriseAmountPaise, currency,
                checkoutName, trialWebhookUrl, trialWebhookSecret, chatUrl);
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

    @Override
    public Map<String, Object> status(long userId) {
        Entitlement entitlement = entitlement(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unlimited", entitlement.unlimited());
        out.put("trial", Map.of("limit", entitlement.trialLimit(), "used", entitlement.trialUsed(), "remaining", entitlement.trialRemaining()));
        out.put("subscription", activeSubscription(userId));
        out.put("plans", plans().stream().map(this::publicPlan).toList());
        out.put("currency", currency);
        out.put("razorpay_key_id", paymentGateway.keyId());
        out.put("enabled", paymentGateway.isConfigured());
        return out;
    }

    @Override
    public Entitlement entitlement(long userId) {
        Map<String, Object> subscription = activeSubscription(userId);
        boolean subscribed = subscription != null;
        // Accounts provisioned by an administrator are unlimited. Only public
        // self-signups go through the demo quota and paywall.
        if (!selfSignup(userId)) return new Entitlement(true, subscribed, true, 0, 0, 0);
        int used = trialUsed(userId);
        int remaining = Math.max(0, trialRequests - used);
        return new Entitlement(subscribed || remaining > 0, subscribed, false, trialRequests, used, remaining);
    }

    /** Counts the request that was just admitted. Only self-serve trials are metered. */
    @Override
    public void recordRequest(long userId) {
        reserveRequest(userId);
    }

    /**
     * Atomically claims one demo request. The conditional UPDATE makes the
     * check-and-increment a single database operation, so parallel requests
     * cannot push a self-serve account past its free limit.
     */
    @Override
    public boolean reserveRequest(long userId) {
        if (!selfSignup(userId)) return true;
        if (activeSubscription(userId) != null) return true;
        int updated = db.update("UPDATE access_users SET trial_requests_used=trial_requests_used+1 WHERE id=? AND trial_requests_used<?", userId, trialRequests);
        if (updated == 0) return false;
        int used = trialUsed(userId);
        if (used >= trialRequests) emitTrialExhausted(userId, used);
        return true;
    }

    @Override
    public Map<String, Object> createOrder(long userId, String planId) {
        if (!paymentGateway.isConfigured()) throw new IllegalStateException("Razorpay is not configured on this gateway.");
        Plan plan = plan(planId);
        Map<String, Object> user = profile(userId);

        Map<String, Object> out = paymentGateway.createOrder(userId, plan.id(), plan.amountPaise(), currency, checkoutName, user);
        String orderId = String.valueOf(out.get("order_id"));
        String now = Instant.now().toString();
        db.update("INSERT INTO subscriptions(user_id,plan,status,amount_paise,currency,razorpay_order_id,starts_at,ends_at,created_at) VALUES(?,?,'pending',?,?,?,?,?,?)",
                userId, plan.id(), plan.amountPaise(), currency, orderId, now, now, now);
        LOGGER.info("razorpay_order_created userId={} plan={} amountPaise={} orderId={}", userId, plan.id(), plan.amountPaise(), orderId);
        return out;
    }

    @Override
    public Map<String, Object> verifyPayment(long userId, Map<String, Object> body) {
        String orderId = text(body, "razorpay_order_id");
        String paymentId = text(body, "razorpay_payment_id");
        String signature = text(body, "razorpay_signature");
        if (orderId.isBlank() || paymentId.isBlank() || signature.isBlank()) throw new IllegalArgumentException("The Razorpay payment response is incomplete.");
        if (!paymentGateway.isConfigured()) throw new IllegalStateException("Razorpay is not configured on this gateway.");
        Map<String, Object> subscription;
        try {
            subscription = db.queryForMap("SELECT * FROM subscriptions WHERE razorpay_order_id=? AND user_id=?", orderId, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("This payment order does not belong to your account.");
        }
        if ("active".equals(String.valueOf(subscription.get("status")))) return status(userId);
        if (!paymentGateway.verifyPaymentSignature(orderId, paymentId, signature)) {
            LOGGER.warn("razorpay_signature_rejected userId={} orderId={}", userId, orderId);
            throw new IllegalArgumentException("Payment signature verification failed.");
        }
        activate(subscription, paymentId);
        LOGGER.info("razorpay_subscription_activated userId={} orderId={} paymentId={}", userId, orderId, paymentId);
        return status(userId);
    }

    /** Handles external webhook notifications verified through {@link PaymentGateway}. */
    @Override
    public void handleWebhook(String rawBody, String signature, String eventId) {
        if (!paymentGateway.verifyWebhookSignature(rawBody, signature)) {
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
                    id, paymentGateway.providerName(), type, rawBody, Instant.now().toString());
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

    /** Exposes HMAC-SHA256 signature utility for test validation and webhook signing. */
    public static String hmacHex(String secret, String value) {
        return RazorpayPaymentGateway.hmacHex(secret, value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return UUID.randomUUID().toString();
        }
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
