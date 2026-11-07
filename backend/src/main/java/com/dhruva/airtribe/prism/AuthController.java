package com.dhruva.airtribe.prism;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/signup")
    public ResponseEntity<?> signUp(@RequestBody AuthRequest body) {
        try { return ResponseEntity.status(HttpStatus.CREATED).body(auth.signUp(body == null ? null : body.name(), body == null ? null : body.email(), body == null ? null : body.password())); }
        catch (AuthService.AuthException e) { return error(e); }
    }

    @PostMapping("/signin")
    public ResponseEntity<?> signIn(@RequestBody AuthRequest body) {
        try { return ResponseEntity.ok(auth.signIn(body == null ? null : body.email(), body == null ? null : body.password())); }
        catch (AuthService.AuthException e) { return error(e); }
    }

    private ResponseEntity<Map<String, Object>> error(AuthService.AuthException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", Map.of("message", e.getMessage())));
    }
    public record AuthRequest(String name, String email, String password) {}
}
