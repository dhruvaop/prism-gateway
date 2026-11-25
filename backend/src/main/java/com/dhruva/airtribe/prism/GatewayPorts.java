package com.dhruva.airtribe.prism;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Narrow ports used by {@link GatewayController}. Keeping them here lets the
 * HTTP transport depend on capabilities instead of concrete service classes,
 * while the existing beans remain the implementations.
 */

interface PrismGatewayOperations {
    PrismService.Tenant authenticate(String auth);
    PrismService.Tenant authenticateChat(String key);
    void validateRequest(Map<String, Object> body);
    void applyPromptPolicies(PrismService.Tenant tenant, Map<String, Object> body);
    PrismService.BudgetLease acquireBudgetLease(PrismService.Tenant tenant);
    void admit(PrismService.Tenant tenant, String model);
    PrismService.Result complete(PrismService.Tenant tenant, Map<String, Object> body);
    PrismService.StreamSession beginStream(PrismService.Tenant tenant, Map<String, Object> body);
    PrismService.StreamSession beginStream(PrismService.Tenant tenant, Map<String, Object> body, boolean hideModelDetails);
    void writeStream(PrismService.StreamSession session, OutputStream out) throws IOException;
    String modelCreator(String model);
    String modelFamily(String model);
    String tierFor(String requested, String task);
    String redactModelDetails(String text);
    void log(PrismService.Tenant tenant, String requested, String status, String provider, String model,
             int promptTokens, int completionTokens, double cost, String cache, boolean fallback,
             String reason, int retries, long latency, String id);
    Map<String, Object> usage(String key);
    Map<String, Object> resetUsage(String key);
    List<Map<String, Object>> logs(String key, int limit);
    Map<String, Object> cacheStats();
    List<Map<String, Object>> cacheEntries(int limit);
    List<Map<String, Object>> cacheHits(int limit);
    void deleteCacheEntry(long id);
    Map<String, Object> purgeCache();
    Map<String, Object> providersHealth();
    Map<String, Object> usageOverview();
    Map<String, Object> routingEval();
    Map<String, Object> routingPolicy();
    List<Map<String, Object>> routingProfiles();
}

interface VirtualKeyLookup {
    String resolveKeyRef(String ref);
    List<Map<String, Object>> keys();
}

interface BillingEntitlementLookup {
    Long userIdForKey(String virtualKey);
    BillingService.Entitlement entitlement(long userId);
    void recordRequest(long userId);
}
