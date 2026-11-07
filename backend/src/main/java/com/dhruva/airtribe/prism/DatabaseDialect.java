package com.dhruva.airtribe.prism;

final class DatabaseDialect {
    enum Kind { SQLITE, POSTGRESQL }
    private DatabaseDialect() {}

    static Kind detect(String jdbcUrl) {
        if (jdbcUrl == null) throw new IllegalArgumentException("A JDBC URL is required");
        if (jdbcUrl.startsWith("jdbc:sqlite:")) return Kind.SQLITE;
        if (jdbcUrl.startsWith("jdbc:postgresql:")) return Kind.POSTGRESQL;
        throw new IllegalArgumentException("Unsupported JDBC database. Use SQLite locally or PostgreSQL for enterprise deployments.");
    }

    static String cacheTableSql(Kind kind) {
        String id = kind == Kind.POSTGRESQL ? "BIGSERIAL PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        return "CREATE TABLE IF NOT EXISTS cache_entries(id " + id + ", virtual_key TEXT NOT NULL, model TEXT NOT NULL, prompt TEXT NOT NULL, response TEXT NOT NULL, created_at TEXT NOT NULL, hit_count INTEGER NOT NULL DEFAULT 0)";
    }
}
