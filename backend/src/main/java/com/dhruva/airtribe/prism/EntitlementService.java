package com.dhruva.airtribe.prism;

/**
 * Interface Segregation Principle (ISP):
 * Consumers that only verify quotas and entitlements (e.g. ChatAppController)
 * depend strictly on this interface rather than the entire billing operations suite.
 */
public interface EntitlementService {

    /** Returns the current entitlement details (remaining demo requests or active subscription) for a user. */
    BillingService.Entitlement entitlement(long userId);

    /** Atomically checks and reserves one demo request token. Returns false if quota is exhausted. */
    boolean reserveRequest(long userId);

    /** Counts a request that was admitted. */
    void recordRequest(long userId);
}
