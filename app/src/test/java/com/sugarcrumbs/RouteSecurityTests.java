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
 * shopper surface under {@code /api/public/**} stays public, read-only apart
 * from guest checkout.
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
            "GET,    /api/orders",
            "GET,    /api/orders/" + ID,
            "PATCH,  /api/orders/" + ID + "/confirm",
            "PATCH,  /api/orders/" + ID + "/start-preparation",
            "PATCH,  /api/orders/" + ID + "/fulfill",
            "PATCH,  /api/orders/" + ID + "/reject",
            "PATCH,  /api/orders/" + ID + "/cancel",
            "PUT,    /api/orders/" + ID + "/schedule",
            "PUT,    /api/orders/" + ID + "/lines",
            "PUT,    /api/orders/" + ID + "/notes",
            "GET,    /api/orders/" + ID + "/invoice",
            "GET,    /api/invoices",
            "GET,    /api/invoices/" + ID,
            "PATCH,  /api/invoices/" + ID + "/discount",
            "PATCH,  /api/invoices/" + ID + "/pay",
            "PATCH,  /api/invoices/" + ID + "/refund",
            "GET,    /api/dashboard/production",
            "GET,    /api/dashboard/summary",
            "GET,    /api/catalogue/items",
            "GET,    /api/catalogue/items/" + ID,
            "POST,   /api/catalogue/items",
            "PUT,    /api/catalogue/items/" + ID,
            "PATCH,  /api/catalogue/items/" + ID + "/availability",
            "PATCH,  /api/catalogue/items/" + ID + "/stock",
            "PATCH,  /api/catalogue/items/" + ID + "/price",
            "POST,   /api/catalogue/items/" + ID + "/images",
            "POST,   /api/catalogue/items/" + ID + "/images/upload",
            "DELETE, /api/catalogue/items/" + ID + "/images",
            "DELETE, /api/catalogue/items/" + ID,
            "GET,    /api/catalogue/categories",
            "POST,   /api/catalogue/categories",
            "PUT,    /api/catalogue/categories/" + ID,
            "DELETE, /api/catalogue/categories/" + ID,
            "GET,    /api/availability/working-hours",
            "PUT,    /api/availability/working-hours/MONDAY",
            "DELETE, /api/availability/working-hours/MONDAY",
            "GET,    /api/availability/blocked-dates",
            "POST,   /api/availability/blocked-dates",
            "PUT,    /api/availability/blocked-dates/" + ID,
            "DELETE, /api/availability/blocked-dates/" + ID,
            "GET,    /api/auth/me",
            "POST,   /api/auth/logout",
            "GET,    /api/owner/profile",
            "PUT,    /api/owner/profile",
            "POST,   /api/owner/password",
            // The old public paths are gone, and the public prefix allows no other writes.
            "GET,    /api/items",
            "GET,    /api/bookings",
            "POST,   /api/bookings",
            "POST,   /api/orders",
            "PUT,    /api/public/catalogue/items/" + ID,
            "DELETE, /api/public/catalogue/items/" + ID,
            "POST,   /api/public/availability",
            "PUT,    /api/public/bakery",
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
            "GET,  /api/public/catalogue/items",
            "GET,  /api/public/catalogue/items/" + ID,
            "GET,  /api/public/catalogue/categories",
            "GET,  /api/public/availability?from=2026-12-01&to=2026-12-02",
            "GET,  /api/public/bakery",
            "POST, /api/public/orders",
            "GET,  /api/public/orders/by-token/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "POST, /api/public/orders/by-token/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/cancel",
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
