package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Route security matrix (CLAUDE.md §8): every owner route rejects anonymous
 * callers with a 401 {@code ApiError}, and lets the Owner through; the
 * shopper-facing routes stay public. Paths are the current ones; #13 moves the
 * public surface under {@code /api/public/**} and extends this matrix.
 *
 * <p>"Let through" means the response is neither 401 nor 403: the request
 * bodies here are deliberately empty, so a 400/404 just proves the request got
 * past security to the controller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouteSecurityTests extends AbstractIntegrationTest {

    private static final String ID = "00000000-0000-0000-0000-000000000001";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private RestClient http;

    @BeforeEach
    void setUp() {
        OwnerFixture.ensureOwner(jdbc, passwordEncoder);
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,    /api/bookings",
            "GET,    /api/bookings/" + ID,
            "PATCH,  /api/bookings/" + ID + "/confirm",
            "PATCH,  /api/bookings/" + ID + "/start-preparation",
            "PATCH,  /api/bookings/" + ID + "/fulfill",
            "PATCH,  /api/bookings/" + ID + "/cancel",
            "PUT,    /api/bookings/" + ID + "/schedule",
            "POST,   /api/items",
            "PUT,    /api/items/" + ID,
            "PATCH,  /api/items/" + ID + "/availability",
            "PATCH,  /api/items/" + ID + "/stock",
            "PATCH,  /api/items/" + ID + "/price",
            "POST,   /api/items/" + ID + "/images",
            "POST,   /api/items/" + ID + "/images/upload",
            "DELETE, /api/items/" + ID + "/images",
            "DELETE, /api/items/" + ID,
            "POST,   /api/categories",
            "PUT,    /api/categories/" + ID,
            "DELETE, /api/categories/" + ID,
            "GET,    /api/availability/working-hours",
            "PUT,    /api/availability/working-hours/MONDAY",
            "DELETE, /api/availability/working-hours/MONDAY",
            "GET,    /api/availability/blocked-dates",
            "POST,   /api/availability/blocked-dates",
            "PUT,    /api/availability/blocked-dates/" + ID,
            "DELETE, /api/availability/blocked-dates/" + ID,
            "GET,    /api/auth/me",
            "POST,   /api/auth/logout",
            // Default deny: a path nobody mapped is still behind authentication.
            "GET,    /api/not-a-route",
    })
    void ownerRoutesNeedTheOwner(String method, String path) {
        ResponseEntity<String> anonymous = call(method, path, null);
        assertThat(anonymous.getStatusCode().value()).as("anonymous %s %s", method, path).isEqualTo(401);
        assertThat(anonymous.getBody()).contains("\"status\":401", "\"path\":\"" + path + "\"");

        ResponseEntity<String> badToken = call(method, path, "not.a.jwt");
        assertThat(badToken.getStatusCode().value()).as("bad token %s %s", method, path).isEqualTo(401);

        ResponseEntity<String> owner = call(method, path, OwnerFixture.accessToken(http));
        assertThat(owner.getStatusCode().value()).as("owner %s %s", method, path).isNotIn(401, 403);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,  /api/items",
            "GET,  /api/items/" + ID,
            "GET,  /api/categories",
            "GET,  /api/availability?from=2026-12-01&to=2026-12-02",
            "POST, /api/bookings",
            "POST, /api/auth/login",
            "POST, /api/auth/refresh",
            "GET,  /.well-known/jwks.json",
            "GET,  /v3/api-docs",
    })
    void publicRoutesAreOpenToAnonymousCallers(String method, String path) {
        ResponseEntity<String> anonymous = call(method, path, null);

        assertThat(anonymous.getStatusCode().value()).as("%s %s", method, path).isNotIn(401, 403);
    }

    private ResponseEntity<String> call(String method, String path, String bearerToken) {
        RestClient.RequestBodySpec request = http.method(HttpMethod.valueOf(method)).uri(path);
        if (bearerToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        if (!method.equals("GET") && !method.equals("DELETE")) {
            request.contentType(MediaType.APPLICATION_JSON).body("{}");
        }
        return request.retrieve().toEntity(String.class);
    }
}
