package com.dhruva.airtribe.prism;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the real PostgreSQL endpoint before Spring creates the pool.
 *
 * <p>Two failure modes are common when a managed host such as Render talks to
 * Supabase:
 *
 * <ul>
 *   <li>The direct host ({@code db.<ref>.supabase.co}) is IPv6-only, while the
 *       host has no outbound IPv6.</li>
 *   <li>The configured URL or username is stale, so the first connection dies
 *       during the TLS handshake and the real cause is buried under a generic
 *       SSL error.</li>
 * </ul>
 *
 * <p>This post-processor normalises the URL, rewrites a direct Supabase host to
 * the shared IPv4 pooler, and probes candidates with a short timeout. The first
 * working candidate is passed to Hikari and Flyway. Every attempt is logged
 * with its host and port only; passwords and full URLs are never printed.
 */
public class DatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String JDBC_PREFIX = "jdbc:postgresql://";
    private static final String[] DEFAULT_REGIONS = {
            "ap-southeast-1", "ap-south-1", "us-east-1", "us-west-1",
            "eu-central-1", "eu-west-1", "ap-northeast-1", "ca-central-1", "sa-east-1"
    };

    @Override
    public int getOrder() {
        // ConfigDataEnvironmentPostProcessor runs at HIGHEST_PRECEDENCE + 10 and
        // is what makes spring.profiles.active visible here.
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = firstNonBlank(
                environment.getProperty("SPRING_DATASOURCE_URL"),
                environment.getProperty("DATABASE_URL"));
        if (raw == null || raw.isBlank()) {
            return;
        }

        boolean enterprise = Arrays.asList(environment.getActiveProfiles()).contains("enterprise")
                || "true".equalsIgnoreCase(environment.getProperty("PRISM_REQUIRE_POSTGRES"));
        if (!enterprise) {
            return;
        }

        Endpoint configured = Endpoint.parse(raw.trim());
        if (configured == null) {
            System.out.println("[prism] datasource: DATABASE_URL is not a PostgreSQL JDBC URL; leaving it unchanged");
            return;
        }

        String username = firstNonBlank(
                environment.getProperty("DATABASE_USERNAME"),
                environment.getProperty("SPRING_DATASOURCE_USERNAME"),
                configured.username);
        String password = firstNonBlank(
                environment.getProperty("DATABASE_PASSWORD"),
                environment.getProperty("SPRING_DATASOURCE_PASSWORD"),
                configured.password);

        List<Endpoint> candidates = candidates(configured, username);
        Endpoint chosen = null;
        String effectiveUser = username == null || username.isBlank() ? "postgres" : username;
        if (password != null && !password.isBlank() && configured.host.contains("supabase")) {
            for (Endpoint candidate : candidates) {
                String candidateUser = normalizeUsername(candidate, username);
                if (probe(candidate, candidateUser, password)) {
                    chosen = candidate;
                    effectiveUser = candidateUser;
                    break;
                }
            }
        }

        Endpoint effective = chosen != null ? chosen : candidates.get(0);
        if (chosen == null) {
            effectiveUser = normalizeUsername(effective, username);
        }
        if (effective.pooler && !effectiveUser.contains(".") && effective.projectRef != null) {
            effectiveUser = "postgres." + effective.projectRef;
            System.out.println("[prism] datasource: pooler username normalised to postgres.<project-ref>");
        }

        System.out.println("[prism] datasource: using " + effective.describe()
                + " (candidate " + (candidates.indexOf(effective) + 1) + " of " + candidates.size() + ")");

        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("spring.datasource.url", effective.jdbcUrl());
        resolved.put("spring.datasource.username", effectiveUser);
        if (password != null && !password.isBlank()) {
            resolved.put("spring.datasource.password", password);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("prismDatasource", resolved));
    }

    private List<Endpoint> candidates(Endpoint configured, String username) {
        List<Endpoint> candidates = new ArrayList<>();
        candidates.add(configured);

        if (configured.isDirectSupabase()) {
            String ref = configured.projectRef;
            if (ref != null) {
                String explicit = System.getenv("SUPABASE_POOLER_HOST");
                if (explicit != null && !explicit.isBlank()) {
                    candidates.add(Endpoint.pooler(explicit.trim(), configured.port, configured.database, ref));
                } else {
                    String regionEnv = System.getenv("SUPABASE_REGION");
                    List<String> regions = regionEnv == null || regionEnv.isBlank()
                            ? Arrays.asList(DEFAULT_REGIONS)
                            : List.of(regionEnv.trim());
                    for (String region : regions) {
                        candidates.add(Endpoint.pooler("aws-0-" + region + ".pooler.supabase.com",
                                configured.port, configured.database, ref));
                    }
                }
            }
        } else if (configured.pooler) {
            // The transaction pooler is a second IPv4 route when session mode is blocked.
            candidates.add(configured.withPort("6543", true));
        }

        // De-duplicate by URL.
        Map<String, Endpoint> unique = new LinkedHashMap<>();
        for (Endpoint candidate : candidates) {
            unique.putIfAbsent(candidate.jdbcUrl(), candidate);
        }
        return new ArrayList<>(unique.values());
    }

    private boolean probe(Endpoint endpoint, String username, String password) {
        try {
            Class.forName("org.postgresql.Driver");
            DriverManager.setLoginTimeout(6);
            try (Connection connection = DriverManager.getConnection(endpoint.jdbcUrl(), username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("select 1");
            }
            System.out.println("[prism] datasource: reachable " + endpoint.describe());
            return true;
        } catch (Exception error) {
            Throwable root = error;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("[prism] datasource: unreachable " + endpoint.describe()
                    + " -> " + root.getClass().getSimpleName() + ": " + root.getMessage());
            return false;
        }
    }

    private static String normalizeUsername(Endpoint endpoint, String username) {
        String user = username == null || username.isBlank() ? "postgres" : username.trim();
        if (endpoint.pooler && !user.contains(".") && endpoint.projectRef != null) {
            return "postgres." + endpoint.projectRef;
        }
        return user;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static final class Endpoint {
        private final String host;
        private final String port;
        private final String database;
        private final Map<String, String> params;
        private final String username;
        private final String password;
        private final boolean pooler;
        private final String projectRef;

        private Endpoint(String host, String port, String database, Map<String, String> params,
                         String username, String password, String projectRef) {
            this.host = host;
            this.port = port;
            this.database = database;
            this.params = params;
            this.username = username;
            this.password = password;
            this.pooler = host.endsWith(".pooler.supabase.com");
            this.projectRef = projectRef;
        }

        static Endpoint parse(String raw) {
            String url = raw;
            if (url.startsWith("postgres://")) {
                url = JDBC_PREFIX + url.substring("postgres://".length());
            } else if (url.startsWith("postgresql://")) {
                url = JDBC_PREFIX + url.substring("postgresql://".length());
            }
            if (!url.startsWith(JDBC_PREFIX)) {
                return null;
            }

            String rest = url.substring(JDBC_PREFIX.length());
            int slash = rest.indexOf('/');
            String authority = slash >= 0 ? rest.substring(0, slash) : rest;
            String databaseAndQuery = slash >= 0 ? rest.substring(slash + 1) : "";

            String username = null;
            String password = null;
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                String userInfo = authority.substring(0, at);
                authority = authority.substring(at + 1);
                int colon = userInfo.indexOf(':');
                username = decode(colon >= 0 ? userInfo.substring(0, colon) : userInfo);
                password = colon >= 0 ? decode(userInfo.substring(colon + 1)) : null;
            }

            int question = databaseAndQuery.indexOf('?');
            String database = question >= 0 ? databaseAndQuery.substring(0, question) : databaseAndQuery;
            if (database.isBlank()) {
                database = "postgres";
            }
            Map<String, String> params = new LinkedHashMap<>();
            if (question >= 0) {
                for (String pair : databaseAndQuery.substring(question + 1).split("&")) {
                    if (pair.isBlank()) continue;
                    int equals = pair.indexOf('=');
                    if (equals < 0) {
                        params.put(pair, "");
                    } else {
                        params.put(pair.substring(0, equals), pair.substring(equals + 1));
                    }
                }
            }

            int colon = authority.lastIndexOf(':');
            String host = colon >= 0 ? authority.substring(0, colon) : authority;
            String port = colon >= 0 ? authority.substring(colon + 1) : "5432";
            params.putIfAbsent("sslmode", "require");
            params.putIfAbsent("connectTimeout", "10");
            params.putIfAbsent("socketTimeout", "60");
            params.putIfAbsent("ApplicationName", "prism-gateway");
            String ref = projectRef(host);
            if (ref == null && username != null && username.startsWith("postgres.")) {
                String fromUser = username.substring("postgres.".length());
                if (!fromUser.isBlank()) {
                    ref = fromUser;
                }
            }
            String envRef = System.getenv("SUPABASE_PROJECT_REF");
            if (ref == null && envRef != null && !envRef.isBlank()) {
                ref = envRef.trim();
            }
            return new Endpoint(host, port, database, params, username, password, ref);
        }

        static Endpoint pooler(String host, String port, String database, String ref) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("sslmode", "require");
            params.put("connectTimeout", "10");
            params.put("socketTimeout", "60");
            params.put("ApplicationName", "prism-gateway");
            return new Endpoint(host, port, database, params, null, null, ref);
        }

        Endpoint withPort(String newPort, boolean transactionMode) {
            Map<String, String> copy = new LinkedHashMap<>(params);
            if (transactionMode) {
                copy.put("prepareThreshold", "0");
            }
            return new Endpoint(host, newPort, database, copy, username, password, projectRef);
        }

        boolean isDirectSupabase() {
            return host.endsWith(".supabase.co") && !pooler;
        }

        String jdbcUrl() {
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (query.length() > 0) query.append('&');
                query.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return JDBC_PREFIX + host + ":" + port + "/" + database + "?" + query;
        }

        String describe() {
            return "host=" + host + " port=" + port + " database=" + database
                    + " pooler=" + pooler;
        }

        private static String projectRef(String host) {
            if (host.startsWith("db.") && host.endsWith(".supabase.co")) {
                return host.substring("db.".length(), host.length() - ".supabase.co".length());
            }
            return null;
        }

        private static String decode(String value) {
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return value;
            }
        }
    }
}
