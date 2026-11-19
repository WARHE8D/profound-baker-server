package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import com.sugarcrumbs.identity.OwnerApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Owner's profile and password (#12): read and replace the profile, set the
 * bakery's time zone (which the rest of the app then uses), and change the
 * password, which signs out every session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerProfileApiTests extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "an even longer new password";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    OwnerApi ownerApi;

    private RestClient http;
    private UUID ownerId;
    private Map<String, Object> session;

    @BeforeEach
    void setUp() {
        ownerId = OwnerFixture.ensureOwner(jdbc, passwordEncoder);
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        session = OwnerFixture.login(http);
    }

    // ---- profile ----

    @Test
    void getReturnsTheProfileWithoutThePasswordHash() {
        ResponseEntity<String> response = http.get().uri("/api/owner/profile")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("\"id\":\"" + ownerId + "\"", "\"email\":\"" + OwnerFixture.EMAIL + "\"",
                        "\"timeZone\":\"UTC\"")
                .doesNotContainIgnoringCase("password")
                .doesNotContain("$2a$");
    }

    @Test
    @SuppressWarnings("unchecked")
    void putReplacesTheProfileAndTheTimeZoneReachesOtherModules() {
        Map<String, Object> request = profile("Europe/London");
        request.put("phone", "+44 20 7946 0000");
        request.put("address", Map.of("street", "1 Bakery Lane", "city", "London", "postalCode", "E1 6AN",
                "country", "UK"));

        ResponseEntity<Map> response = put(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("businessName", "Crumbs & Co")
                .containsEntry("ownerName", "Ada Baker")
                .containsEntry("phone", "+44 20 7946 0000")
                .containsEntry("timeZone", "Europe/London");
        assertThat((Map<String, Object>) response.getBody().get("address"))
                .containsEntry("city", "London").containsEntry("region", null);

        Map<?, ?> reread = http.get().uri("/api/owner/profile").header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve().body(Map.class);
        assertThat(reread).isEqualTo(response.getBody());
        assertThat(ownerApi.settings().timeZone()).isEqualTo(ZoneId.of("Europe/London"));
    }

    /** Guests read the storefront's details and time zone without a token, and never the Owner's own. */
    @Test
    @SuppressWarnings("unchecked")
    void thePublicBakeryShowsTheProfileWithoutPersonalDetails() {
        Map<String, Object> request = profile("America/New_York");
        request.put("logoUrl", "/uploads/logo.png");
        request.put("address", Map.of("city", "Brooklyn"));
        put(request);

        ResponseEntity<Map> response = http.get().uri("/api/public/bakery").retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsOnlyKeys("businessName", "timeZone", "logoUrl", "phone", "address")
                .containsEntry("businessName", "Crumbs & Co")
                .containsEntry("timeZone", "America/New_York")
                .containsEntry("logoUrl", "/uploads/logo.png")
                .containsEntry("phone", null);
        assertThat((Map<String, Object>) response.getBody().get("address")).containsEntry("city", "Brooklyn");
    }

    @Test
    void anOmittedAddressIsCleared() {
        Map<String, Object> withAddress = profile("UTC");
        withAddress.put("address", Map.of("city", "London"));
        put(withAddress);

        ResponseEntity<Map> response = put(profile("UTC"));

        assertThat(response.getBody().get("address")).isNull();
    }

    @Test
    void unknownAndFixedOffsetTimeZonesAre400() {
        for (String zone : List.of("Mars/Olympus_Mons", "+01:00", "GMT+1")) {
            ResponseEntity<Map> response = put(profile(zone));

            assertThat(response.getStatusCode().value()).as(zone).isEqualTo(400);
            assertThat((String) response.getBody().get("message")).as(zone).contains("IANA");
        }
        assertThat(ownerApi.settings().timeZone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void invalidProfileFieldsAre400WithEveryRejectedField() {
        Map<String, Object> request = profile("UTC");
        request.put("businessName", "");
        request.put("phone", "x".repeat(31));
        request.put("address", Map.of("postalCode", "x".repeat(21)));

        ResponseEntity<Map> response = put(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((List<?>) response.getBody().get("fieldErrors")).hasSize(3);
    }

    @Test
    void theProfileCannotChangeTheLoginEmail() {
        Map<String, Object> request = profile("UTC");
        request.put("email", "attacker@example.com");

        put(request);

        assertThat(jdbc.queryForObject("SELECT email FROM identity.owners WHERE id = ?", String.class, ownerId))
                .isEqualTo(OwnerFixture.EMAIL);
    }

    // ---- password ----

    @Test
    void changingThePasswordSignsOutEverySession() {
        String otherSessionRefresh = (String) OwnerFixture.login(http).get("refreshToken");

        ResponseEntity<Void> response = changePassword(OwnerFixture.PASSWORD, NEW_PASSWORD);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(login(OwnerFixture.PASSWORD)).isEqualTo(401);
        assertThat(login(NEW_PASSWORD)).isEqualTo(200);
        assertThat(refresh((String) session.get("refreshToken"))).isEqualTo(401);
        assertThat(refresh(otherSessionRefresh)).isEqualTo(401);
        String hash = jdbc.queryForObject("SELECT password_hash FROM identity.owners WHERE id = ?", String.class, ownerId);
        assertThat(hash).startsWith("$2a$12$").doesNotContain(NEW_PASSWORD);
    }

    /** 400, not 401: a 401 would make the SPA think its access token expired. */
    @Test
    void aWrongCurrentPasswordIs400AndChangesNothing() {
        ResponseEntity<Void> response = changePassword("not the password", NEW_PASSWORD);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(login(OwnerFixture.PASSWORD)).isEqualTo(200);
        assertThat(refresh((String) session.get("refreshToken"))).isEqualTo(200);
    }

    @Test
    void aShortOrUnchangedNewPasswordIs400() {
        assertThat(changePassword(OwnerFixture.PASSWORD, "too-short").getStatusCode().value()).isEqualTo(400);
        assertThat(changePassword(OwnerFixture.PASSWORD, OwnerFixture.PASSWORD).getStatusCode().value())
                .isEqualTo(400);
        assertThat(login(OwnerFixture.PASSWORD)).isEqualTo(200);
    }

    // ---- helpers ----

    private String bearer() {
        return "Bearer " + session.get("accessToken");
    }

    private static Map<String, Object> profile(String timeZone) {
        Map<String, Object> request = new HashMap<>();
        request.put("businessName", "Crumbs & Co");
        request.put("ownerName", "Ada Baker");
        request.put("timeZone", timeZone);
        return request;
    }

    private ResponseEntity<Map> put(Map<String, Object> request) {
        return http.put().uri("/api/owner/profile")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Void> changePassword(String current, String next) {
        return http.post().uri("/api/owner/password")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currentPassword", current, "newPassword", next))
                .retrieve().toBodilessEntity();
    }

    private int login(String password) {
        return http.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", OwnerFixture.EMAIL, "password", password))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private int refresh(String refreshToken) {
        return http.post().uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }
}
