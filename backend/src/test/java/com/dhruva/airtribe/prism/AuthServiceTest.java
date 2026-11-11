package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    @Test void signupPersistsBcryptHashAndReturnsWorkingToken() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        JwtTokenService jwt = new JwtTokenService("test-only-secret-that-is-more-than-32-bytes-long", 3600, new ObjectMapper());
        AuthService service = new AuthService(db, jwt);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);

        var result = service.signUp(" A User ", "A@Example.com", "a-user", "correct horse battery");

        assertEquals("a@example.com", result.user().email());
        assertTrue(jwt.isValid(result.token()));
        var values = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db).update(anyString(), values.capture());
        String storedHash = String.valueOf(values.getValue()[4]);
        assertNotEquals("correct horse battery", storedHash);
        assertTrue(new BCryptPasswordEncoder().matches("correct horse battery", storedHash));
    }

    @Test void signupRejectsWeakPasswordsBeforeDatabaseWrite() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AuthService service = new AuthService(db, new JwtTokenService("test-only-secret-that-is-more-than-32-bytes-long", 3600, new ObjectMapper()));
        var ex = assertThrows(AuthService.AuthException.class, () -> service.signUp("A User", "a@example.com", "short"));
        assertEquals(400, ex.status());
        verifyNoInteractions(db);
    }

    @Test void signInResolvesEmailAndUsernameAsSeparateNamespaces() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            JdbcTemplate db = new JdbcTemplate(dataSource);
            db.execute("CREATE TABLE auth_users(id TEXT PRIMARY KEY,name TEXT NOT NULL,email TEXT NOT NULL,"
                    + "password_hash TEXT NOT NULL,role TEXT NOT NULL,created_at TEXT NOT NULL,username TEXT)");
            var encoder = new BCryptPasswordEncoder(4);
            db.update("INSERT INTO auth_users VALUES(?,?,?,?,?,?,?)",
                    "1", "Admin", "admin@example.com", encoder.encode("admin-password"), "ADMIN", "2026-01-01T00:00:00Z", "admin");
            db.update("INSERT INTO auth_users VALUES(?,?,?,?,?,?,?)",
                    "2", "Other", "other@example.com", encoder.encode("other-password"), "ADMIN", "2026-01-02T00:00:00Z", "otheruser");
            AuthService service = new AuthService(db, new JwtTokenService("test-only-secret-that-is-more-than-32-bytes-long", 3600, new ObjectMapper()));

            assertEquals("1", service.signIn("admin@example.com", "admin-password").user().id());
            assertEquals("1", service.signIn("ADMIN", "admin-password").user().id());
            assertEquals("2", service.signIn("otheruser", "other-password").user().id());

            // A password is never accepted across accounts.
            assertEquals(401, assertThrows(AuthService.AuthException.class,
                    () -> service.signIn("admin@example.com", "other-password")).status());
            assertEquals(401, assertThrows(AuthService.AuthException.class,
                    () -> service.signIn("", "admin-password")).status());
        } finally {
            dataSource.destroy();
        }
    }
}
