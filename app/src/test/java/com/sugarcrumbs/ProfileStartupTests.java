package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dev and prod profiles both boot against a real Postgres, and prod
 * refuses to start when a required env var is missing instead of falling
 * back to a development default.
 */
class ProfileStartupTests {

    /** A real signing key in the one-line format .env.example documents (base64 PKCS#8 DER). */
    static final String JWT_KEY = newJwtKey();

    static String newJwtKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    @SpringBootTest(properties = {
            "APP_STORAGE_PATH=target/test-uploads",
            "APP_CORS_ALLOWED_ORIGINS=https://shop.example"
    })
    @ActiveProfiles("prod")
    class Prod extends AbstractIntegrationTest {

        // Not in @SpringBootTest(properties): the key is generated, not a constant.
        @DynamicPropertySource
        static void jwtKey(DynamicPropertyRegistry registry) {
            registry.add("APP_JWT_PRIVATE_KEY", () -> JWT_KEY);
        }

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

        /** #26: one JSON object per line, with the ids RequestIdFilter and tracing put in the MDC. */
        @Test
        void logsOneJsonObjectPerLineCarryingTheRequestAndTraceIds(CapturedOutput output) {
            MDC.put("requestId", "json-log-check-01");
            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            try {
                LoggerFactory.getLogger(ProfileStartupTests.class).info("structured logging check");
            } finally {
                MDC.remove("requestId");
                MDC.remove("traceId");
            }

            String line = output.getOut().lines().filter(l -> l.contains("structured logging check"))
                    .findFirst().orElseThrow();
            assertThat(line).startsWith("{").endsWith("}")
                    .contains("\"requestId\":\"json-log-check-01\"")
                    .contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"")
                    .contains("\"message\":\"structured logging check\"");
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
                    "DB_PASSWORD=" + postgres.getPassword(),
                    "APP_JWT_PRIVATE_KEY=" + JWT_KEY))
                    .hasStackTraceContaining("APP_STORAGE_PATH");
        }

        @Test
        void refusesToStartWithoutDatabaseUrl() {
            assertThatThrownBy(() -> startProd(
                    "DB_USERNAME=" + postgres.getUsername(),
                    "DB_PASSWORD=" + postgres.getPassword(),
                    "APP_STORAGE_PATH=target/test-uploads",
                    "APP_JWT_PRIVATE_KEY=" + JWT_KEY))
                    .hasStackTraceContaining("DB_URL");
        }

        /** Prod must never fall back to a throwaway key, which would sign the Owner out on every restart. */
        @Test
        void refusesToStartWithoutJwtSigningKey() {
            assertThatThrownBy(() -> startProd(
                    "DB_URL=" + postgres.getJdbcUrl(),
                    "DB_USERNAME=" + postgres.getUsername(),
                    "DB_PASSWORD=" + postgres.getPassword(),
                    "APP_STORAGE_PATH=target/test-uploads"))
                    .hasStackTraceContaining("APP_JWT_PRIVATE_KEY");
        }

        @Test
        void refusesToStartWithAnInvalidJwtSigningKey() {
            assertThatThrownBy(() -> startProd(
                    "DB_URL=" + postgres.getJdbcUrl(),
                    "DB_USERNAME=" + postgres.getUsername(),
                    "DB_PASSWORD=" + postgres.getPassword(),
                    "APP_STORAGE_PATH=target/test-uploads",
                    "APP_JWT_PRIVATE_KEY=not-a-key"))
                    .hasStackTraceContaining("not a valid PKCS#8 RSA private key");
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
