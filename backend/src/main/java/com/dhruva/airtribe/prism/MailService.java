package com.dhruva.airtribe.prism;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * Sends transactional email through SMTP when it is configured. When it is not,
 * the service clearly reports that delivery was skipped so the caller can fall
 * back to the development reset link instead of silently pretending to send.
 */
@Service
public class MailService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailService.class);
    private final JavaMailSenderImpl sender;
    private final String from;
    private final String fromName;
    private final boolean exposeResetLink;

    public MailService(
            @Value("${prism.mail.host:}") String host,
            @Value("${prism.mail.port:587}") int port,
            @Value("${prism.mail.username:}") String username,
            @Value("${prism.mail.password:}") String password,
            @Value("${prism.mail.from:}") String from,
            @Value("${prism.mail.from-name:Prism Gateway}") String fromName,
            @Value("${prism.mail.starttls:true}") boolean starttls,
            @Value("${prism.mail.ssl:false}") boolean ssl,
            @Value("${prism.mail.expose-reset-link:false}") boolean exposeResetLink) {
        this.exposeResetLink = exposeResetLink;
        this.fromName = fromName == null || fromName.isBlank() ? "Prism Gateway" : fromName.trim();
        String resolvedFrom = from == null ? "" : from.trim();
        if (resolvedFrom.isBlank()) resolvedFrom = username == null ? "" : username.trim();
        this.from = resolvedFrom;
        if (host == null || host.isBlank()) {
            this.sender = null;
            LOGGER.warn("prism_mail_not_configured Set PRISM_MAIL_HOST, PRISM_MAIL_USERNAME, PRISM_MAIL_PASSWORD and PRISM_MAIL_FROM to deliver password reset emails.");
            return;
        }
        JavaMailSenderImpl mail = new JavaMailSenderImpl();
        mail.setHost(host.trim());
        mail.setPort(port);
        mail.setUsername(username == null ? "" : username.trim());
        mail.setPassword(password == null ? "" : password);
        mail.setDefaultEncoding("UTF-8");
        Properties props = new Properties();
        props.put("mail.smtp.auth", String.valueOf(username != null && !username.isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        mail.setJavaMailProperties(props);
        this.sender = mail;
        LOGGER.info("prism_mail_configured host={} port={} starttls={} ssl={}", host, port, starttls, ssl);
    }

    public boolean configured() { return sender != null; }
    public boolean exposeResetLink() { return exposeResetLink; }

    public boolean sendPasswordReset(String to, String name, String link, long ttlMinutes) {
        String subject = "Reset your Prism password";
        String greeting = name == null || name.isBlank() ? "there" : name.trim();
        String text = "Hi " + greeting + ",\n\n"
                + "We received a request to reset the password for your Prism account.\n\n"
                + "Reset your password: " + link + "\n\n"
                + "This link expires in " + ttlMinutes + " minutes and can be used once. "
                + "If you did not request this, you can safely ignore this email.\n\n"
                + "— Prism Gateway";
        String html = "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:560px;margin:0 auto;padding:28px;color:#1f2937\">"
                + "<h2 style=\"margin:0 0 14px\">Reset your Prism password</h2>"
                + "<p>Hi " + escape(greeting) + ",</p>"
                + "<p>We received a request to reset the password for your Prism account.</p>"
                + "<p style=\"margin:26px 0\"><a href=\"" + escape(link) + "\" "
                + "style=\"background:#6d5efc;color:#fff;text-decoration:none;padding:12px 20px;border-radius:6px;display:inline-block\">"
                + "Reset password</a></p>"
                + "<p style=\"font-size:13px;color:#4b5563\">This link expires in " + ttlMinutes + " minutes and can be used once.</p>"
                + "<p style=\"font-size:13px;color:#6b7280\">If you did not request this, you can safely ignore this email.</p>"
                + "<p style=\"font-size:12px;color:#9ca3af\">If the button does not work, paste this URL into your browser:<br>" + escape(link) + "</p>"
                + "<p style=\"font-size:12px;color:#9ca3af\">— Prism Gateway</p></div>";
        return send(to, subject, text, html);
    }

    private boolean send(String to, String subject, String text, String html) {
        if (sender == null || from == null || from.isBlank()) {
            LOGGER.warn("prism_mail_skipped reason=smtp_not_configured to={}", mask(to));
            return false;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            sender.send(message);
            LOGGER.info("prism_mail_sent subject={} to={}", subject, mask(to));
            return true;
        } catch (Exception ex) {
            LOGGER.error("prism_mail_failed subject={} to={} error={}", subject, mask(to), ex.getMessage());
            return false;
        }
    }

    private String mask(String email) {
        if (email == null || email.isBlank()) return "-";
        int at = email.indexOf('@');
        if (at <= 1) return "*" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
