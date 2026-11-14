package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * The Owner's credentials for tests that call owner routes. The app creates the Owner on
 * first start from {@link AbstractIntegrationTest#OWNER_EMAIL}/{@code OWNER_PASSWORD}
 * (OwnerBootstrap); the database is shared across test classes, so the credentials are
 * reset in case an earlier test changed them.
 */
final class OwnerFixture {

    static final String EMAIL = AbstractIntegrationTest.OWNER_EMAIL;
    static final String PASSWORD = AbstractIntegrationTest.OWNER_PASSWORD;

    private OwnerFixture() {
    }

    /** @return the Owner's id */
    static UUID ensureOwner(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        UUID id = jdbc.queryForObject("SELECT id FROM identity.owners", UUID.class);
        jdbc.update("UPDATE identity.owners SET email = ?, password_hash = ? WHERE id = ?",
                EMAIL, passwordEncoder.encode(PASSWORD), id);
        return id;
    }

    /** Logs in through the API and returns the response body (accessToken, refreshToken, …). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> login(RestClient http) {
        return http.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", EMAIL, "password", PASSWORD))
                .retrieve().body(Map.class);
    }

    static String accessToken(RestClient http) {
        return (String) login(http).get("accessToken");
    }
}
