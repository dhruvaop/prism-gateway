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

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        return Map.of("signup_open", !auth.hasAdmins());
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signUp(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody(required = false) AuthRequest body) {
        try {
            if (auth.hasAdmins()) {
                String token = authorization != null && authorization.startsWith("Bearer ")
                        ? authorization.substring(7).trim() : "";
                if (!auth.isAdminToken(token)) {
                    throw new AuthService.AuthException(403, "Administrator accounts can only be created by a signed-in administrator.");
                }
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(auth.signUp(body == null ? null : body.name(), body == null ? null : body.email(), body == null ? null : body.username(), body == null ? null : body.password()));
        }
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
    public record AuthRequest(String name, String email, String username, String password) {}
}
