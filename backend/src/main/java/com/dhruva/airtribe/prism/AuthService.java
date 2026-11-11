package com.dhruva.airtribe.prism;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final JwtTokenService tokens;

    public AuthService(JdbcTemplate db, JwtTokenService tokens) {
        this.db = db;
        this.tokens = tokens;
    }

    public AuthResponse signUp(String name, String email, String password) {
        return signUp(name,email,null,password);
    }

    public AuthResponse signUp(String name, String email, String username, String password) {
        String cleanName = name == null ? "" : name.trim();
        String cleanEmail = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (cleanName.length() < 2 || cleanName.length() > 100) throw new AuthException(400, "Enter a name between 2 and 100 characters.");
        if (!EMAIL.matcher(cleanEmail).matches() || cleanEmail.length() > 254) throw new AuthException(400, "Enter a valid email address.");
        String cleanUsername = username == null || username.isBlank() ? cleanEmail.substring(0,cleanEmail.indexOf('@')) : username.trim().toLowerCase(java.util.Locale.ROOT);
        if (!cleanUsername.matches("[a-z0-9._-]{3,32}")) throw new AuthException(400, "Username must be 3–32 characters using letters, numbers, dot, underscore, or hyphen.");
        if (password == null || password.length() < 8 || password.length() > 72) throw new AuthException(400, "Password must be between 8 and 72 characters.");
        AuthUser user = new AuthUser(UUID.randomUUID().toString(), cleanName, cleanEmail, "ADMIN");
        try {
            db.update("INSERT INTO auth_users(id,name,email,username,password_hash,role,created_at) VALUES(?,?,?,?,?,?,?)",
                    user.id(), user.name(), user.email(), cleanUsername, passwords.encode(password), user.role(), Instant.now().toString());
        } catch (DuplicateKeyException e) {
            throw new AuthException(409, "An account with this email or username already exists.");
        }
        LOGGER.info("auth_signup userId={} role=ADMIN", user.id());
        return response(user);
    }

    public AuthResponse signIn(String emailOrUsername, String password) {
        String login = emailOrUsername == null ? "" : emailOrUsername.trim().toLowerCase(java.util.Locale.ROOT);
        var matches = db.query("SELECT id,name,email,password_hash,role FROM auth_users WHERE lower(email)=? OR lower(username)=?",
                (rs, row) -> new UserPassword(new AuthUser(rs.getString("id"), rs.getString("name"), rs.getString("email"), rs.getString("role")), rs.getString("password_hash")), login, login);
        if (matches.isEmpty() || password == null || !passwords.matches(password, matches.get(0).passwordHash())) {
            LOGGER.warn("auth_signin_rejected reason=invalid_credentials");
            throw new AuthException(401, "Username/email or password is incorrect.");
        }
        LOGGER.info("auth_signin userId={}", matches.get(0).user().id());
        return response(matches.get(0).user());
    }

    public boolean isValidToken(String token) { return tokens.isValid(token); }
    public boolean isAdminToken(String token) { return tokens.isAdmin(token); }
    /** Public signup is only used to bootstrap the very first administrator. */
    public boolean hasAdmins() {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM auth_users WHERE role='ADMIN'", Integer.class);
        return count != null && count > 0;
    }
    public String issueChatToken(long userId,String username,String name) { return issueChatToken(userId,username,name,null); }
    public String issueChatToken(long userId,String username,String name,String email) {
        String resolved=email==null||email.isBlank()?username+"@prism.local":email.trim().toLowerCase(java.util.Locale.ROOT);
        return tokens.issue(new AuthUser(Long.toString(userId),name,resolved,"CHAT_USER"));
    }
    public String tokenSubject(String token) { return tokens.subject(token); }
    private AuthResponse response(AuthUser user) { return new AuthResponse(tokens.issue(user), user); }
    private record UserPassword(AuthUser user, String passwordHash) {}
    public record AuthResponse(String token, AuthUser user) {}
    public static class AuthException extends RuntimeException {
        private final int status;
        public AuthException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
