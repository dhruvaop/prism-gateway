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
class PasswordResetTest {
    @Autowired ManagedAccessService managed;
    @Autowired PasswordResetService resets;
    @Autowired PrismService prism;
    @Autowired JdbcTemplate db;

    private String username, email, key;

    @BeforeEach void removeLeftovers() {
        db.update("DELETE FROM password_resets WHERE email LIKE 'reset%@example.test'");
        db.update("DELETE FROM tenant_keys WHERE virtual_key IN (SELECT virtual_key FROM access_users WHERE username LIKE 'reset%')");
        db.update("DELETE FROM access_users WHERE username LIKE 'reset%'");
    }

    @AfterEach void cleanUp() {
        if (email != null) db.update("DELETE FROM password_resets WHERE email=?", email);
        if (username != null) db.update("DELETE FROM access_users WHERE username=?", username);
        if (key != null) { db.update("DELETE FROM tenant_keys WHERE virtual_key=?", key); prism.removeManagedTenant(key); }
    }

    @Test void resetLinkChangesPasswordExactlyOnce() {
        username = "reset" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        email = username + "@example.test";
        Map<String, Object> created = managed.createUser(Map.of(
                "name", "Reset User",
                "username", username,
                "email", email,
                "team", "Platform",
                "password", "OldPass!2026",
                "access_mode", "chat"));
        key = String.valueOf(created.get("virtual_key"));

        PasswordResetService.ResetDelivery delivery = resets.requestChatReset(email);
        assertEquals("dev", delivery.delivery(), "SMTP is not configured in tests, so the development link must be returned");
        assertNotNull(delivery.resetLink(), "a development reset link must be produced");
        String token = delivery.resetLink().substring(delivery.resetLink().indexOf("reset=") + 6);

        assertThrows(AuthService.AuthException.class, () -> managed.login(username, "WrongPass!2026"));
        resets.resetChatToken(token, "NewPass!2026");
        assertNotNull(managed.login(username, "NewPass!2026"), "the new password must sign in");
        assertNotNull(managed.login(email, "NewPass!2026"), "the account email must also sign in");
        assertNotNull(managed.login(email.toUpperCase(), "NewPass!2026"), "email sign-in must ignore case");
        assertThrows(AuthService.AuthException.class, () -> managed.login(username, "OldPass!2026"), "the old password must stop working");
        assertThrows(IllegalArgumentException.class, () -> resets.resetChatToken(token, "Another!2026"), "a reset token must be single-use");
    }

    @Test void unknownEmailStaysGeneric() {
        PasswordResetService.ResetDelivery delivery = resets.requestChatReset("nobody-" + UUID.randomUUID() + "@example.test");
        assertEquals("none", delivery.delivery());
        assertNull(delivery.resetLink());
        assertTrue(delivery.message().contains("If an account exists"));
    }
}
