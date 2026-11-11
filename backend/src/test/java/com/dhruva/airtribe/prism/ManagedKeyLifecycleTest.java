package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ManagedKeyLifecycleTest {
    @Autowired ManagedAccessService managed;
    @Autowired PrismService prism;
    @Autowired JdbcTemplate db;

    private String username, key, rotated;

    @BeforeEach void removeLeftoversFromAnEarlierFailedRun() {
        db.update("DELETE FROM tenant_keys WHERE virtual_key IN (SELECT virtual_key FROM access_users WHERE username LIKE 'eval%')");
        db.update("DELETE FROM access_users WHERE username LIKE 'eval%'");
    }

    @AfterEach void cleanUp() {
        if (username != null) db.update("DELETE FROM access_users WHERE username=?", username);
        if (key != null) { db.update("DELETE FROM tenant_keys WHERE virtual_key=?", key); prism.removeManagedTenant(key); }
        if (rotated != null) { db.update("DELETE FROM tenant_keys WHERE virtual_key=?", rotated); prism.removeManagedTenant(rotated); }
    }

    @Test void generatedKeyCanAuthenticateRotateAndDisable() {
        username = "eval" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, Object> created = managed.createUser(Map.of(
                "name", "Eval User",
                "username", username,
                "email", username + "@example.test",
                "team", "Platform",
                "password", "EvalPass!2026",
                "access_mode", "both"));
        key = String.valueOf(created.get("virtual_key"));

        assertTrue(key.matches("prism-sk-[0-9a-f]{24}"), "generated key must use the prism-sk- prefix and 24 random hex characters");
        assertNotNull(prism.authenticate("Bearer " + key), "a freshly generated key must authenticate");

        rotated = String.valueOf(managed.rotateKey(key).get("key"));
        assertTrue(rotated.matches("prism-sk-[0-9a-f]{24}"));
        assertNotEquals(key, rotated);
        assertThrows(PrismService.GatewayException.class, () -> prism.authenticate("Bearer " + key), "the old key must stop working after rotation");
        assertNotNull(prism.authenticate("Bearer " + rotated), "the rotated key must authenticate");

        managed.setKeyEnabled(rotated, false);
        assertThrows(PrismService.GatewayException.class, () -> prism.authenticate("Bearer " + rotated), "a disabled key must be rejected");
    }

    @Test void seededDemoKeysCannotBeRotatedFromManagedApi() {
        assertThrows(IllegalArgumentException.class, () -> managed.rotateKey("prism-sk-search-1a2b3c"));
    }
}
