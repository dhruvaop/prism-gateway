package com.dhruva.airtribe.prism;

import java.util.Map;

/**
 * Abstraction for payment gateway providers (Razorpay, Stripe, Cashfree, etc.).
 *
 * <p>Adheres to the <b>Dependency Inversion Principle (DIP)</b> and <b>Open/Closed Principle (OCP)</b>:
 * core billing logic depends on this abstraction, allowing alternative payment gateways
 * to be integrated or substituted without modifying the subscription management logic.</p>
 */
public interface PaymentGateway {

    /** Returns the unique identifier of the payment provider (e.g. "razorpay"). */
    String providerName();

    /** Indicates whether the payment gateway credentials are configured and active. */
    boolean isConfigured();

    /** Returns the public key id required by the frontend client-side checkout. */
    String keyId();

    /**
     * Creates an order with the external payment gateway.
     *
     * @param userId        the user initiating checkout
     * @param planId        the chosen plan (pro, enterprise)
     * @param amountPaise   the order amount in smallest currency unit (paise)
     * @param currency      the 3-letter currency code (INR)
     * @param checkoutName  the public merchant/gateway display name
     * @param user          user details containing name and email
     * @return the checkout payload for client SDK instantiation
     */
    Map<String, Object> createOrder(long userId, String planId, int amountPaise, String currency,
                                    String checkoutName, Map<String, Object> user);

    /**
     * Verifies the client-side payment completion signature.
     *
     * @param orderId   the gateway order ID
     * @param paymentId the gateway payment ID
     * @param signature the cryptographic signature received from the client SDK
     * @return true if the signature is valid
     */
    boolean verifyPaymentSignature(String orderId, String paymentId, String signature);

    /**
     * Verifies the authenticity of an incoming server-to-server webhook payload.
     *
     * @param rawBody   the raw HTTP request body string
     * @param signature the cryptographic signature from request headers
     * @return true if the webhook payload is authentic
     */
    boolean verifyWebhookSignature(String rawBody, String signature);
}
