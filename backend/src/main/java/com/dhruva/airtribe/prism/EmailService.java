package com.dhruva.airtribe.prism;

/**
 * Abstraction for transactional email delivery.
 *
 * <p>Adheres to the <b>Dependency Inversion Principle (DIP)</b> and
 * <b>Open/Closed Principle (OCP)</b>: callers depend on this interface,
 * allowing alternative mail transports (SMTP, SendGrid, Amazon SES, mock/dev)
 * to be plugged in without changing domain services.</p>
 */
public interface EmailService {

    /** Returns true if email transport credentials and configuration are active. */
    boolean configured();

    /** Indicates whether unconfigured mail should expose reset links directly (e.g. in local development/testing). */
    boolean exposeResetLink();

    /**
     * Sends a password reset notification email.
     *
     * @param to         recipient email address
     * @param name       recipient display name
     * @param link       unique password reset url
     * @param ttlMinutes duration in minutes before link expiration
     * @return true if the email was successfully dispatched
     */
    boolean sendPasswordReset(String to, String name, String link, long ttlMinutes);
}
