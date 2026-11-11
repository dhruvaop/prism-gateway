package com.dhruva.airtribe.prism;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class PasswordResetController {
    private final PasswordResetService resets;
    public PasswordResetController(PasswordResetService resets) { this.resets = resets; }

    @PostMapping("/app/password/forgot")
    public ResponseEntity<?> chatForgot(@RequestBody(required = false) Map<String, Object> body) {
        return forgot(resets.requestChatReset(text(body, "email")));
    }

    @PostMapping("/app/password/reset")
    public ResponseEntity<?> chatReset(@RequestBody(required = false) Map<String, Object> body) {
        return reset(body, resets::resetChatToken);
    }

    @PostMapping("/auth/password/forgot")
    public ResponseEntity<?> adminForgot(@RequestBody(required = false) Map<String, Object> body) {
        return forgot(resets.requestAdminReset(text(body, "email")));
    }

    @PostMapping("/auth/password/reset")
    public ResponseEntity<?> adminReset(@RequestBody(required = false) Map<String, Object> body) {
        return reset(body, resets::resetAdminToken);
    }

    @FunctionalInterface private interface ResetAction { void apply(String token, String password); }

    private ResponseEntity<?> forgot(PasswordResetService.ResetDelivery delivery) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", delivery.message());
        out.put("delivery", delivery.delivery());
        if (delivery.resetLink() != null) out.put("reset_link", delivery.resetLink());
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<?> reset(Map<String, Object> body, ResetAction action) {
        try {
            action.apply(text(body, "token"), text(body, "password"));
            return ResponseEntity.ok(Map.of("message", "Your password has been updated. You can sign in now."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of("message", ex.getMessage())));
        }
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
