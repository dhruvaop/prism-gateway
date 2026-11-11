package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Component
public class JwtTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper json;

    public JwtTokenService(@Value("${prism.auth.jwt-secret}") String secret,
                           @Value("${prism.auth.jwt-ttl-seconds:86400}") long ttlSeconds,
                           ObjectMapper json) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.json = json;
    }

    public String issue(AuthUser user) {
        long now = Instant.now().getEpochSecond();
        try {
            String header = ENCODER.encodeToString(json.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = ENCODER.encodeToString(json.writeValueAsBytes(Map.of(
                    "sub", user.id(), "email", user.email(), "name", user.name(), "role", user.role(),
                    "iat", now, "exp", now + ttlSeconds)));
            String content = header + "." + payload;
            return content + "." + ENCODER.encodeToString(sign(content));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create access token", e);
        }
    }

    public boolean isValid(String token) {
        return claims(token) != null;
    }

    public boolean isAdmin(String token) {
        Map<?, ?> claims = claims(token);
        return claims != null && "ADMIN".equals(claims.get("role"));
    }

    public String subject(String token) {
        Map<?, ?> claims = claims(token);
        return claims == null ? null : String.valueOf(claims.get("sub"));
    }

    private Map<?, ?> claims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            byte[] actual = DECODER.decode(parts[2]);
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!java.security.MessageDigest.isEqual(actual, expected)) return null;
            Map<?, ?> claims = json.readValue(DECODER.decode(parts[1]), Map.class);
            Object expiry = claims.get("exp");
            return expiry instanceof Number n && n.longValue() > Instant.now().getEpochSecond() ? claims : null;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }
}
