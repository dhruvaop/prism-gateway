package com.dhruva.airtribe.prism;

import java.util.Map;

/**
 * Interface Segregation Principle (ISP):
 * Contract for customer billing, checkout orders, and webhook processing.
 */
public interface BillingOperations {

    /** Returns current billing status, available plans, remaining quota, and payment keys. */
    Map<String, Object> status(long userId);

    /** Creates a payment gateway order for the specified plan. */
    Map<String, Object> createOrder(long userId, String planId);

    /** Verifies the cryptographic payment signature and activates the subscription. */
    Map<String, Object> verifyPayment(long userId, Map<String, Object> body);

    /** Processes server-to-server webhook notifications with idempotency and signature validation. */
    void handleWebhook(String rawBody, String signature, String eventId);
}
