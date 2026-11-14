package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owner bootstrap on a real first start (#11): each test boots the app by hand
 * against its own empty database in the shared container, so "no Owner yet" is
 * real rather than mocked.
 */
class OwnerBootstrapStartupTests {

    private static final PostgreSQLContainer postgres = AbstractIntegrationTest.POSTGRES;

    private String jdbcUrl;

    @BeforeEach
    void freshDatabase() {
        String name = "bootstrap_" + System.nanoTime();
        jdbc(postgres.getJdbcUrl()).execute("CREATE DATABASE " + name);
        jdbcUrl = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    @Test
    void firstStartCreatesTheOwnerFromTheEnvironment() {
        start("APP_OWNER_EMAIL=Baker@Example.com", "APP_OWNER_INITIAL_PASSWORD=first-login-password").close();

        Map<String, Object> owner = jdbc(jdbcUrl).queryForMap("SELECT email, password_hash FROM identity.owners");
        assertThat(owner.get("email")).isEqualTo("baker@example.com");
        String hash = (String) owner.get("password_hash");
        assertThat(hash).startsWith("$2a$12$");
        assertThat(new BCryptPasswordEncoder().matches("first-login-password", hash)).isTrue();
    }

    @Test
    void laterStartsNeedNoOwnerVariablesAndCreateNoSecondOwner() {
        start("APP_OWNER_EMAIL=baker@example.com", "APP_OWNER_INITIAL_PASSWORD=first-login-password").close();

        start().close();
        start("APP_OWNER_EMAIL=someone-else@example.com", "APP_OWNER_INITIAL_PASSWORD=another-password").close();

        assertThat(jdbc(jdbcUrl).queryForList("SELECT email FROM identity.owners", String.class))
                .containsExactly("baker@example.com");
    }

    @Test
    void refusesToStartWithNoOwnerAndNoVariables() {
        assertThatThrownBy(this::start)
                .hasStackTraceContaining("no Owner exists: set APP_OWNER_EMAIL and APP_OWNER_INITIAL_PASSWORD");
    }

    @Test
    void refusesToStartWithATooShortInitialPassword() {
        assertThatThrownBy(() -> start("APP_OWNER_EMAIL=baker@example.com", "APP_OWNER_INITIAL_PASSWORD=short"))
                .hasStackTraceContaining("at least 12 characters");
        assertThat(jdbc(jdbcUrl).queryForObject("SELECT count(*) FROM identity.owners", Integer.class)).isZero();
    }

    private ConfigurableApplicationContext start(String... ownerVariables) {
        List<String> properties = new ArrayList<>(List.of(
                "DB_URL=" + jdbcUrl,
                "DB_USERNAME=" + postgres.getUsername(),
                "DB_PASSWORD=" + postgres.getPassword(),
                "spring.devtools.restart.enabled=false"));
        properties.addAll(List.of(ownerVariables));
        return new SpringApplicationBuilder(BakerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties.toArray(String[]::new))
                .run();
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
    }
}
