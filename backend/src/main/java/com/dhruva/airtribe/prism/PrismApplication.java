package com.dhruva.airtribe.prism;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PrismApplication {
    public static void main(String[] args) {
        logDatasourceTarget();
        SpringApplication.run(PrismApplication.class, args);
    }

    /**
     * Log where the gateway will actually connect, before Spring or Flyway opens
     * a socket. Hosting dashboards hide typos, and the most common Supabase
     * failure is a direct IPv6-only host or a pooler username without the
     * project ref. Nothing here prints a password or the full URL.
     */
    static void logDatasourceTarget() {
        String url = env("SPRING_DATASOURCE_URL");
        String source = "SPRING_DATASOURCE_URL";
        if (url.isBlank()) {
            url = env("DATABASE_URL");
            source = "DATABASE_URL";
        }
        if (url.isBlank()) {
            System.out.println("[prism] datasource target: none configured; the default SQLite profile will be used");
            return;
        }
        if (!url.startsWith("jdbc:")) {
            System.out.println("[prism] datasource target: " + source
                    + " is not a jdbc: URL; Spring needs jdbc:postgresql://host:port/database");
            return;
        }

        String authority = url.substring("jdbc:".length());
        authority = authority.substring(authority.indexOf("://") + 3);
        int slash = authority.indexOf('/');
        String hostPort = slash >= 0 ? authority.substring(0, slash) : authority;
        String database = slash >= 0 ? authority.substring(slash + 1) : "";
        int query = database.indexOf('?');
        String sslmode = "unspecified";
        if (query >= 0) {
            String params = database.substring(query + 1);
            database = database.substring(0, query);
            for (String param : params.split("&")) {
                if (param.startsWith("sslmode=")) sslmode = param.substring("sslmode=".length());
            }
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        String port = colon >= 0 ? hostPort.substring(colon + 1) : "";

        String username = env("DATABASE_USERNAME");
        if (username.isBlank()) username = env("SPRING_DATASOURCE_USERNAME");
        boolean pooler = host.endsWith(".pooler.supabase.com");
        boolean directSupabase = host.endsWith(".supabase.co") && !pooler;

        System.out.println("[prism] datasource source=" + source
                + " host=" + host
                + " port=" + port
                + " database=" + database
                + " user=" + (username.isBlank() ? "unset" : username)
                + " sslmode=" + sslmode
                + " pooler=" + pooler);

        if (directSupabase) {
            System.out.println("[prism] WARNING: " + host + " is Supabase's direct host. "
                    + "It is IPv6-only on most projects, and Render has no outbound IPv6. "
                    + "Use the shared pooler host (aws-0-<region>.pooler.supabase.com:5432).");
        }
        if (pooler && !username.contains(".")) {
            System.out.println("[prism] WARNING: the shared pooler needs DATABASE_USERNAME=postgres.<project-ref>. "
                    + "A bare 'postgres' user will be rejected.");
        }
        if (pooler && "unspecified".equals(sslmode)) {
            System.out.println("[prism] WARNING: sslmode is missing; append ?sslmode=require to DATABASE_URL.");
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
