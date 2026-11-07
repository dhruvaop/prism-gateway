package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {
    private static final String SECRET = "test-only-secret-that-is-more-than-32-bytes-long";

    @Test void issuesTokensThatValidateAndRejectsTampering() {
        JwtTokenService service = new JwtTokenService(SECRET, 3600, new ObjectMapper());
        String token = service.issue(new AuthUser("user-1", "A User", "a@example.com", "ADMIN"));
        assertTrue(service.isValid(token));
        assertFalse(service.isValid(token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a")));
        assertFalse(service.isValid("not-a-jwt"));
    }

    @Test void rejectsExpiredTokens() {
        JwtTokenService service = new JwtTokenService(SECRET, -1, new ObjectMapper());
        assertFalse(service.isValid(service.issue(new AuthUser("user-1", "A User", "a@example.com", "ADMIN"))));
    }

    @Test void requiresStrongSigningSecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenService("too-short", 3600, new ObjectMapper()));
    }
}
