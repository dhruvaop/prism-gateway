package com.dhruva.airtribe.prism;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller handling customer billing status, checkout order generation,
 * payment verification, and inbound payment webhooks.
 *
 * <p>Adheres to the <b>Dependency Inversion Principle (DIP)</b> and
 * <b>Interface Segregation Principle (ISP)</b> by depending on {@link BillingOperations}.</p>
 */
@RestController
public class BillingController {
    private static final Logger LOGGER = LoggerFactory.getLogger(BillingController.class);
    private final BillingOperations billing;
    private final ManagedAccessService managed;

    public BillingController(BillingOperations billing, ManagedAccessService managed) {
        this.billing = billing;
        this.managed = managed;
    }

    @GetMapping("/app/billing/status")
    public ResponseEntity<?> status(@RequestHeader(value = "Authorization", required = false) String header) {
        try {
            return ResponseEntity.ok(billing.status(managed.requireChatUser(header)));
        } catch (AuthService.AuthException ex) {
            return ResponseEntity.status(ex.status()).body(error(ex.getMessage(), null));
        }
    }

    @PostMapping("/app/billing/order")
    public ResponseEntity<?> order(@RequestHeader(value = "Authorization", required = false) String header,
                                   @RequestBody(required = false) Map<String, Object> body) {
        try {
            long userId = managed.requireChatUser(header);
            String plan = body == null ? "" : String.valueOf(body.getOrDefault("plan", ""));
            return ResponseEntity.ok(billing.createOrder(userId, plan));
        } catch (AuthService.AuthException ex) {
            return ResponseEntity.status(ex.status()).body(error(ex.getMessage(), null));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage(), null));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(503).body(error(ex.getMessage(), "billing_unavailable"));
        }
    }

    @PostMapping("/app/billing/verify")
    public ResponseEntity<?> verify(@RequestHeader(value = "Authorization", required = false) String header,
                                    @RequestBody(required = false) Map<String, Object> body) {
        try {
            long userId = managed.requireChatUser(header);
            return ResponseEntity.ok(billing.verifyPayment(userId, body == null ? Map.of() : body));
        } catch (AuthService.AuthException ex) {
            return ResponseEntity.status(ex.status()).body(error(ex.getMessage(), null));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage(), null));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(503).body(error(ex.getMessage(), "billing_unavailable"));
        }
    }

    /** Razorpay calls this endpoint after a payment is captured or an order is paid. */
    @PostMapping("/webhooks/razorpay")
    public ResponseEntity<?> razorpayWebhook(@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                             @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
                                             @RequestBody(required = false) String rawBody) {
        try {
            billing.handleWebhook(rawBody == null ? "" : rawBody, signature, eventId);
            return ResponseEntity.ok(Map.of("received", true));
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("razorpay_webhook_rejected reason={}", ex.getMessage());
            return ResponseEntity.badRequest().body(error(ex.getMessage(), "invalid_webhook"));
        } catch (IllegalStateException ex) {
            LOGGER.warn("razorpay_webhook_unavailable reason={}", ex.getMessage());
            return ResponseEntity.status(503).body(error(ex.getMessage(), "billing_unavailable"));
        }
    }

    private Map<String, Object> error(String message, String code) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message);
        if (code != null) detail.put("code", code);
        return Map.of("error", detail);
    }
}
