package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceEnvironmentPostProcessorTest {

    @Test void normalizesPostgresSchemeAndAddsSafeDefaults() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("enterprise");
        environment.setProperty("DATABASE_URL", "postgres://app_user:s3cret@db.internal:5432/prism");

        new DatasourceEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        String url = environment.getProperty("spring.datasource.url");
        assertNotNull(url);
        assertTrue(url.startsWith("jdbc:postgresql://db.internal:5432/prism?"), url);
        assertTrue(url.contains("sslmode=require"), url);
        assertTrue(url.contains("connectTimeout=10"), url);
        assertEquals("app_user", environment.getProperty("spring.datasource.username"));
        assertEquals("s3cret", environment.getProperty("spring.datasource.password"));
    }

    @Test void leavesNonPostgresUrlsUntouched() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("enterprise");
        environment.setProperty("DATABASE_URL", "not-a-database-url");

        new DatasourceEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("spring.datasource.url"));
    }

    @Test void ignoresPostgresWhenTheEnterpriseProfileIsNotActive() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DATABASE_URL", "jdbc:postgresql://db.internal:5432/prism?sslmode=require");

        new DatasourceEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("spring.datasource.url"));
    }
}
