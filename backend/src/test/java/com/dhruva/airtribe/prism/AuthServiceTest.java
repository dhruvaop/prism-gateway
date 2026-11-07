package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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

        var result = service.signUp(" A User ", "A@Example.com", "correct horse battery");

        assertEquals("a@example.com", result.user().email());
        assertTrue(jwt.isValid(result.token()));
        var values = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db).update(anyString(), values.capture());
        String storedHash = String.valueOf(values.getValue()[3]);
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
}
