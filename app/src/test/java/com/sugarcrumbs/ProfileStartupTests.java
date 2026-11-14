package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dev and prod profiles both boot against a real Postgres, and prod
 * refuses to start when a required env var is missing instead of falling
 * back to a development default.
 */
class ProfileStartupTests {

    @Nested
    @SpringBootTest(properties = {
            "APP_STORAGE_PATH=target/test-uploads",
            "APP_CORS_ALLOWED_ORIGINS=https://shop.example"
    })
    @ActiveProfiles("prod")
    class Prod extends AbstractIntegrationTest {

        @Value("${spring.jpa.show-sql}")
        boolean showSql;

        @Value("${app.storage.local-path}")
        String storagePath;

        @Value("${app.cors.allowed-origins}")
        String corsOrigins;

        @Test
        void bootsWithEverythingTakenFromTheEnvironment() {
            assertThat(showSql).isFalse();
            assertThat(storagePath).isEqualTo("target/test-uploads");
            assertThat(corsOrigins).isEqualTo("https://shop.example");
        }
    }

    @Nested
    @ActiveProfiles("dev")
    class Dev extends AbstractIntegrationTest {

        @Value("${spring.jpa.show-sql}")
        boolean showSql;

        @Value("${app.cors.allowed-origins}")
        String corsOrigins;

        @Test
        void bootsWithLocalDefaults() {
            assertThat(showSql).isTrue();
            assertThat(corsOrigins).isNotBlank();
        }
    }

    /** Boots the app by hand, outside the test context, against the shared test database. */
    @Nested
    class ProdWithMissingVariables {

        static final PostgreSQLContainer postgres = AbstractIntegrationTest.POSTGRES;

        @Test
        void refusesToStartWithoutStoragePath() {
            assertThatThrownBy(() -> startProd(
                    "DB_URL=" + postgres.getJdbcUrl(),
                    "DB_USERNAME=" + postgres.getUsername(),
                    "DB_PASSWORD=" + postgres.getPassword()))
                    .hasStackTraceContaining("APP_STORAGE_PATH");
        }

        @Test
        void refusesToStartWithoutDatabaseUrl() {
            assertThatThrownBy(() -> startProd(
                    "DB_USERNAME=" + postgres.getUsername(),
                    "DB_PASSWORD=" + postgres.getPassword(),
                    "APP_STORAGE_PATH=target/test-uploads"))
                    .hasStackTraceContaining("DB_URL");
        }

        private static void startProd(String... properties) {
            new SpringApplicationBuilder(BakerApplication.class)
                    .profiles("prod")
                    .web(WebApplicationType.NONE)
                    .properties(properties)
                    .run()
                    .close();
        }
    }
}
