package com.dhruva.airtribe.prism;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Issues single-use, hashed, expiring password reset tokens. The raw token is
 * only ever placed in the email link; the database stores its SHA-256 hash.
 */
@Service
public class PasswordResetService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final String GENERIC_MESSAGE = "If an account exists for that email, a reset link has been sent.";
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 72;

    private final JdbcTemplate db;
    private final EmailService mail;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final long ttlMinutes;
    private final long cooldownSeconds;
    private final String chatUrl;
    private final String consoleUrl;

    public PasswordResetService(JdbcTemplate db, EmailService mail,
                                @Value("${prism.password-reset.ttl-minutes:30}") long ttlMinutes,
                                @Value("${prism.password-reset.cooldown-seconds:60}") long cooldownSeconds,
                                @Value("${prism.app.chat-url:http://localhost:5173}") String chatUrl,
                                @Value("${prism.app.console-url:http://localhost:5173}") String consoleUrl) {
        this.db = db;
        this.mail = mail;
        this.ttlMinutes = ttlMinutes;
        this.cooldownSeconds = cooldownSeconds;
        this.chatUrl = trimSlash(chatUrl);
        this.consoleUrl = trimSlash(consoleUrl);
    }

    public record ResetDelivery(String message, String delivery, String resetLink) {}

    public ResetDelivery requestChatReset(String email) { return request(email, "chat"); }
    public ResetDelivery requestAdminReset(String email) { return request(email, "admin"); }
    @Transactional public void resetChatToken(String token, String password) { reset(token, password, "chat"); }
    @Transactional public void resetAdminToken(String token, String password) { reset(token, password, "admin"); }

    /** An authenticated administrator initiates a reset for a specific chat account. */
    public ResetDelivery requestChatResetForUser(long userId) {
        cleanupOldTokens();
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id,name,email,access_mode FROM access_users WHERE id=?", userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("User not found.");
        Map<String, Object> account = rows.get(0);
        String mode = String.valueOf(account.get("access_mode"));
        if (!"chat".equals(mode) && !"both".equals(mode)) {
            throw new IllegalArgumentException("API-only users do not have a chat password to reset.");
        }
        String email = account.get("email") == null ? "" : String.valueOf(account.get("email"));
        if (!EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Add a valid email address to this user before sending a reset link.");
        }
        return issue(account, "chat", true);
    }

    private ResetDelivery request(String email, String type) {
        String clean = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        cleanupOldTokens();
        if (!EMAIL.matcher(clean).matches()) return new ResetDelivery(GENERIC_MESSAGE, "none", null);

        Map<String, Object> account = findAccount(clean, type);
        if (account == null) {
            LOGGER.info("password_reset_requested type={} outcome=no_account", type);
            return new ResetDelivery(GENERIC_MESSAGE, "none", null);
        }

        return issue(account, type, false);
    }

    private ResetDelivery issue(Map<String, Object> account, String type, boolean adminInitiated) {
        String clean = String.valueOf(account.get("email")).trim().toLowerCase(Locale.ROOT);
        String userId = String.valueOf(account.get("id"));
        if (!adminInitiated && inCooldown(type, userId)) {
            LOGGER.info("password_reset_requested type={} userId={} outcome=cooldown", type, userId);
            return new ResetDelivery(GENERIC_MESSAGE, "none", null);
        }

        String token = randomToken();
        String now = Instant.now().toString();
        String expiresAt = Instant.now().plus(Duration.ofMinutes(ttlMinutes)).toString();
        db.update("UPDATE password_resets SET used_at=? WHERE user_type=? AND user_id=? AND used_at IS NULL", now, type, userId);
        db.update("INSERT INTO password_resets(token_hash,user_type,user_id,email,expires_at,used_at,created_at) VALUES(?,?,?,?,?,NULL,?)",
                hash(token), type, userId, clean, expiresAt, now);

        String link = ("chat".equals(type) ? chatUrl + "/chat.html" : consoleUrl + "/") + "?reset=" + token;
        String name = account.get("name") == null ? "" : String.valueOf(account.get("name"));
        boolean sent = mail.sendPasswordReset(clean, name, link, ttlMinutes);
        LOGGER.info("password_reset_requested type={} userId={} adminInitiated={} outcome={}", type, userId, adminInitiated, sent ? "email_sent" : "email_not_configured");

        if (sent) return new ResetDelivery(adminInitiated ? "Password reset email sent to the user." : GENERIC_MESSAGE, "email", null);
        if (mail.exposeResetLink()) {
            return new ResetDelivery(adminInitiated ? "Email is not configured. Share this development reset link with the user." : "Email delivery is not configured. Use this development reset link.", "dev", link);
        }
        return new ResetDelivery(adminInitiated ? "A reset was created, but it could not be delivered. Check the SMTP settings." : GENERIC_MESSAGE, "none", null);
    }

    private void reset(String token, String password, String type) {
        String cleanToken = token == null ? "" : token.trim();
        if (cleanToken.isBlank()) throw new IllegalArgumentException("This reset link is missing its token.");
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new IllegalArgumentException("Choose a password between " + MIN_PASSWORD + " and " + MAX_PASSWORD + " characters.");
        }
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id,user_id,expires_at FROM password_resets WHERE token_hash=? AND user_type=? AND used_at IS NULL ORDER BY id DESC LIMIT 1",
                hash(cleanToken), type);
        if (rows.isEmpty()) throw new IllegalArgumentException("This reset link is invalid or has already been used.");
        Map<String, Object> row = rows.get(0);
        try {
            if (Instant.parse(String.valueOf(row.get("expires_at"))).isBefore(Instant.now())) {
                db.update("UPDATE password_resets SET used_at=? WHERE id=?", Instant.now().toString(), row.get("id"));
                throw new IllegalArgumentException("This reset link has expired. Request a new one.");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("This reset link is invalid.");
        }
        String userId = String.valueOf(row.get("user_id"));
        String hash = passwords.encode(password);
        int claimed = db.update("UPDATE password_resets SET used_at=? WHERE id=? AND used_at IS NULL", Instant.now().toString(), row.get("id"));
        if (claimed != 1) throw new IllegalArgumentException("This reset link is invalid or has already been used.");
        int changed;
        if ("chat".equals(type)) {
            changed = db.update("UPDATE access_users SET password_hash=? WHERE id=?", hash, Long.parseLong(userId));
        } else {
            changed = db.update("UPDATE auth_users SET password_hash=? WHERE id=?", hash, userId);
        }
        if (changed == 0) throw new IllegalArgumentException("This account no longer exists.");
        LOGGER.info("password_reset_completed type={} userId={}", type, userId);
    }

    private Map<String, Object> findAccount(String email, String type) {
        List<Map<String, Object>> rows;
        if ("chat".equals(type)) {
            rows = db.queryForList("SELECT id,name,email,enabled,access_mode FROM access_users WHERE lower(email)=?", email);
            for (Map<String, Object> row : rows) {
                boolean enabled = row.get("enabled") != null && ((Number) row.get("enabled")).intValue() == 1;
                String mode = row.get("access_mode") == null ? "" : String.valueOf(row.get("access_mode"));
                if (enabled && ("chat".equals(mode) || "both".equals(mode))) return row;
            }
            return null;
        }
        rows = db.queryForList("SELECT id,name,email FROM auth_users WHERE lower(email)=?", email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean inCooldown(String type, String userId) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT created_at FROM password_resets WHERE user_type=? AND user_id=? AND used_at IS NULL ORDER BY id DESC LIMIT 1",
                type, userId);
        if (rows.isEmpty()) return false;
        try {
            Instant created = Instant.parse(String.valueOf(rows.get(0).get("created_at")));
            return created.plusSeconds(cooldownSeconds).isAfter(Instant.now());
        } catch (Exception ex) {
            return false;
        }
    }

    private void cleanupOldTokens() {
        try {
            db.update("DELETE FROM password_resets WHERE created_at < ?", Instant.now().minus(Duration.ofDays(2)).toString());
        } catch (Exception ex) {
            LOGGER.warn("password_reset_cleanup_failed error={}", ex.getMessage());
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash reset token", ex);
        }
    }

    private String trimSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
