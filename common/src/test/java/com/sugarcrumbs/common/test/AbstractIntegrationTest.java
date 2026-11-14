package com.sugarcrumbs.common.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for Spring context tests against a real Postgres (never H2):
 * same major version as docker-compose, so tests need Docker but no local
 * database.
 *
 * <p>One container is started per test JVM and shared by every Spring
 * context, instead of one per context: a container takes seconds to start,
 * and tests that need different settings ({@code RANDOM_PORT}, profiles,
 * properties) each get their own context. Testcontainers removes it when the
 * JVM exits. The database is therefore shared across test classes, so tests
 * create their own data and never assume empty tables.
 *
 * <p>Subclasses may redeclare {@link SpringBootTest} to change its
 * attributes; the datasource wiring below is inherited either way. Published
 * to the other modules as {@code baker-common:test-jar}.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    /** Public for tests that boot the app by hand ({@code SpringApplicationBuilder}) rather than through a test context. */
    /** The Owner the app creates on first start (OwnerBootstrap) in the shared test database. */
    public static final String OWNER_EMAIL = "owner@sugarcrumbs.test";
    public static final String OWNER_PASSWORD = "correct horse battery staple";

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.owner.email", () -> OWNER_EMAIL);
        registry.add("app.owner.initial-password", () -> OWNER_PASSWORD);
    }
}
