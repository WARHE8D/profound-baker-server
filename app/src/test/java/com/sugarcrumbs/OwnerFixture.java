package com.sugarcrumbs;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Makes sure the single Owner exists with known credentials, for tests that call owner
 * routes. The test database is shared across classes and the system allows only one
 * Owner, so an existing row is reused (its email and password reset) instead of adding
 * another.
 */
final class OwnerFixture {

    static final String EMAIL = "owner@sugarcrumbs.test";
    static final String PASSWORD = "correct horse battery staple";

    private OwnerFixture() {
    }

    /** @return the Owner's id */
    static UUID ensureOwner(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        String hash = passwordEncoder.encode(PASSWORD);
        UUID existing = jdbc.query("SELECT id FROM identity.owners LIMIT 1",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        if (existing != null) {
            jdbc.update("UPDATE identity.owners SET email = ?, password_hash = ? WHERE id = ?", EMAIL, hash, existing);
            return existing;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO identity.owners
                    (id, created_at, updated_at, version, business_name, owner_name, email, password_hash)
                VALUES (?, now(), now(), 0, 'SugarCrumbs', 'Test Owner', ?, ?)
                """, id, EMAIL, hash);
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
