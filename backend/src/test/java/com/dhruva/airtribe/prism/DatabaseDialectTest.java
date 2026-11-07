package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseDialectTest {
    @Test void recognizesLocalAndEnterpriseDatabaseUrls() {
        assertEquals(DatabaseDialect.Kind.SQLITE, DatabaseDialect.detect("jdbc:sqlite:./prism.db"));
        assertEquals(DatabaseDialect.Kind.POSTGRESQL, DatabaseDialect.detect("jdbc:postgresql://db:5432/prism"));
    }
    @Test void refusesUnknownDatabaseInsteadOfStartingWithWrongSchema() {
        assertThrows(IllegalArgumentException.class,()->DatabaseDialect.detect("jdbc:mysql://db/prism"));
    }
    @Test void selectsDatabaseSpecificGeneratedCacheIdType() {
        assertTrue(DatabaseDialect.cacheTableSql(DatabaseDialect.Kind.SQLITE).contains("INTEGER PRIMARY KEY AUTOINCREMENT"));
        assertTrue(DatabaseDialect.cacheTableSql(DatabaseDialect.Kind.POSTGRESQL).contains("BIGSERIAL PRIMARY KEY"));
    }
    @Test void enterpriseProfileUsesPostgresAndVersionedMigrations() throws Exception {
        Properties p=new Properties();try(InputStream in=new ClassPathResource("application-enterprise.properties").getInputStream()){p.load(in);}
        assertEquals("org.postgresql.Driver",p.getProperty("spring.datasource.driver-class-name"));
        assertEquals("classpath:db/migration/postgresql",p.getProperty("spring.flyway.locations"));
        try(InputStream in=new ClassPathResource("db/migration/postgresql/V1__create_prism_tables.sql").getInputStream()){
            String ddl=new String(in.readAllBytes(),StandardCharsets.UTF_8);assertTrue(ddl.contains("CREATE TABLE usage_monthly"));assertTrue(ddl.contains("PRIMARY KEY (virtual_key, month)"));assertTrue(ddl.contains("BIGSERIAL PRIMARY KEY"));
        }
    }
}
