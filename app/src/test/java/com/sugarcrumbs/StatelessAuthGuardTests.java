package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the assumption that makes {@code csrf.disable()} in {@link SecurityConfig}
 * safe (and the matching CodeQL alert a false positive): the browser never sends a
 * credential on its own. Credentials travel only in the {@code Authorization: Bearer}
 * header, which a cross-site page cannot set; no cookie, session, Basic or form login
 * ever authenticates a request.
 *
 * <p>If one of these tests fails, someone is introducing ambient credentials (e.g. a
 * refresh token in an HttpOnly cookie). That is allowed only together with CSRF
 * protection for the routes the cookie reaches (SameSite=Strict plus a CSRF token,
 * {@code CookieCsrfTokenRepository}): re-enable CSRF in {@link SecurityConfig} first,
 * then update these tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatelessAuthGuardTests extends AbstractIntegrationTest {

    /** Source constructs that create cookies or server-side sessions. */
    private static final Pattern AMBIENT_CREDENTIALS = Pattern.compile(
            "\\bResponseCookie\\b|jakarta\\.servlet\\.http\\.Cookie\\b|\\bnew Cookie\\(|\\bHttpSession\\b"
                    + "|getSession\\(|SessionCreationPolicy\\.(ALWAYS|IF_REQUIRED|NEVER)"
                    + "|\\.httpBasic\\(Customizer|\\.formLogin\\(Customizer|\\.rememberMe\\(");

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

    @Test
    void noAuthResponseEverSetsACookie() {
        ResponseEntity<Map> login = http.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", OwnerFixture.EMAIL, "password", OwnerFixture.PASSWORD))
                .retrieve().toEntity(Map.class);
        String accessToken = (String) login.getBody().get("accessToken");
        String refreshToken = (String) login.getBody().get("refreshToken");

        ResponseEntity<Map> refresh = http.post().uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .retrieve().toEntity(Map.class);
        ResponseEntity<String> me = http.get().uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve().toEntity(String.class);
        ResponseEntity<String> ownerRoute = http.get().uri("/api/bookings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve().toEntity(String.class);
        ResponseEntity<String> failedLogin = http.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", OwnerFixture.EMAIL, "password", "wrong password"))
                .retrieve().toEntity(String.class);

        for (ResponseEntity<?> response : List.of(login, refresh, me, ownerRoute, failedLogin)) {
            assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).as("Set-Cookie").isNullOrEmpty();
        }
    }

    @Test
    void cookiesNeverAuthenticateARequest() {
        ResponseEntity<String> response = http.get().uri("/api/bookings")
                .header(HttpHeaders.COOKIE, "JSESSIONID=forged; SESSION=forged; access_token="
                        + OwnerFixture.accessToken(http))
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    /** Browsers replay Basic credentials automatically, exactly like a cookie. */
    @Test
    void basicAuthNeverAuthenticatesARequest() {
        String basic = Base64.getEncoder().encodeToString(
                (OwnerFixture.EMAIL + ":" + OwnerFixture.PASSWORD).getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = http.get().uri("/api/bookings")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).doesNotContain("Basic");
    }

    @Test
    void noFormLoginEndpointExists() {
        ResponseEntity<String> response = http.post().uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=" + OwnerFixture.EMAIL + "&password=" + OwnerFixture.PASSWORD)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNullOrEmpty();
    }

    /** Catches cookie/session code at review time, before any request ever exercises it. */
    @Test
    void productionCodeCreatesNoCookiesOrSessions() throws IOException {
        Path repoRoot = repoRoot();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(repoRoot)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> AMBIENT_CREDENTIALS.matcher(read(p)).find())
                    .map(p -> repoRoot.relativize(p).toString())
                    .toList();
        }

        assertThat(offenders)
                .as("cookie/session-based auth makes csrf.disable() unsafe; see this test's javadoc")
                .isEmpty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".env.example"))) {
                return dir;
            }
        }
        throw new IllegalStateException(".env.example not found above " + Path.of("").toAbsolutePath());
    }
}
