package com.sugarcrumbs;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Owner's token lifecycle end to end over HTTP (#10, CLAUDE.md §5): login,
 * access-token contents and validation, refresh rotation with reuse detection,
 * logout, and the hashed storage of refresh tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    JwtEncoder jwtEncoder;

    private RestClient http;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        ownerId = OwnerFixture.ensureOwner(jdbc, passwordEncoder);
        // Never throw on 4xx/5xx: the tests assert on the status themselves.
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    // ---- login ----

    @Test
    void loginReturnsAShortLivedOwnerAccessTokenAndARefreshToken() {
        ResponseEntity<Map> response = login(OwnerFixture.EMAIL, OwnerFixture.PASSWORD);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        Map<?, ?> body = response.getBody();
        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        assertThat(((Number) body.get("expiresIn")).longValue()).isBetween(890L, 900L);
        assertThat((String) body.get("refreshToken")).hasSizeGreaterThanOrEqualTo(43);

        Jwt jwt = jwtDecoder.decode((String) body.get("accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(ownerId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("OWNER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("sugarcrumbs");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsKey("kid");
    }

    @Test
    void loginIgnoresEmailCase() {
        assertThat(login(OwnerFixture.EMAIL.toUpperCase(), OwnerFixture.PASSWORD).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSame401() {
        ResponseEntity<Map> wrongPassword = login(OwnerFixture.EMAIL, "wrong password");
        ResponseEntity<Map> unknownEmail = login("nobody@sugarcrumbs.test", OwnerFixture.PASSWORD);

        for (ResponseEntity<Map> response : List.of(wrongPassword, unknownEmail)) {
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody())
                    .containsEntry("status", 401)
                    .containsEntry("message", "invalid email or password")
                    .doesNotContainKey("accessToken");
        }
    }

    @Test
    void malformedLoginIs400() {
        ResponseEntity<Map> response = http.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "not-an-email", "password", ""))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((List<?>) response.getBody().get("fieldErrors")).hasSize(2);
    }

    // ---- access tokens ----

    @Test
    void meReturnsTheOwnerWithoutThePasswordHash() {
        ResponseEntity<String> response = http.get().uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + OwnerFixture.accessToken(http))
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("\"id\":\"" + ownerId + "\"")
                .contains("\"email\":\"" + OwnerFixture.EMAIL + "\"")
                .doesNotContainIgnoringCase("password")
                .doesNotContain("$2a$");
    }

    @Test
    void meWithoutATokenIs401WithABearerChallenge() {
        ResponseEntity<Map> response = http.get().uri("/api/auth/me").retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.getBody()).containsEntry("status", 401).containsEntry("path", "/api/auth/me");
    }

    @Test
    void anExpiredAccessTokenIs401WithAGenericMessage() {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        String expired = sign(jwtEncoder, claims(past, past.plus(Duration.ofMinutes(15)), "sugarcrumbs", "OWNER"));

        ResponseEntity<Map> response = me(expired);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().get("message")).isEqualTo("a valid access token is required");
    }

    @Test
    void aTokenSignedWithAnotherKeyIs401() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(otherKey)));
        Instant now = Instant.now();

        ResponseEntity<Map> response = me(sign(forger, claims(now, now.plusSeconds(900), "sugarcrumbs", "OWNER")));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aTokenFromAnotherIssuerIs401() {
        Instant now = Instant.now();

        ResponseEntity<Map> response = me(sign(jwtEncoder, claims(now, now.plusSeconds(900), "someone-else", "OWNER")));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aValidTokenWithoutTheOwnerRoleIs403() {
        Instant now = Instant.now();

        ResponseEntity<Map> response = me(sign(jwtEncoder, claims(now, now.plusSeconds(900), "sugarcrumbs", "GUEST")));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).containsEntry("status", 403).containsEntry("error", "Forbidden");
    }

    @Test
    void jwksPublishesOnlyThePublicKey() {
        ResponseEntity<String> response = http.get().uri("/.well-known/jwks.json").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String kid = (String) jwtDecoder.decode(OwnerFixture.accessToken(http)).getHeaders().get("kid");
        assertThat(response.getBody())
                .contains("\"kid\":\"" + kid + "\"", "\"kty\":\"RSA\"", "\"n\":", "\"e\":")
                .doesNotContain("\"d\":", "\"p\":", "\"q\":");
    }

    // ---- refresh ----

    @Test
    void refreshRotatesTheRefreshTokenAndIssuesANewAccessToken() {
        String first = (String) OwnerFixture.login(http).get("refreshToken");

        ResponseEntity<Map> response = refresh(first);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        String second = (String) response.getBody().get("refreshToken");
        assertThat(second).isNotEqualTo(first);
        assertThat(jwtDecoder.decode((String) response.getBody().get("accessToken")).getSubject())
                .isEqualTo(ownerId.toString());
        assertThat(refresh(second).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void reusingASpentRefreshTokenRevokesTheWholeChain() {
        String first = (String) OwnerFixture.login(http).get("refreshToken");
        String second = (String) refresh(first).getBody().get("refreshToken");

        ResponseEntity<Map> reuse = refresh(first);

        assertThat(reuse.getStatusCode().value()).isEqualTo(401);
        assertThat(reuse.getBody()).containsEntry("message", "invalid or expired refresh token");
        // The legitimate holder's current token died with the chain, so a thief who replayed
        // `first` cannot keep a session going either.
        assertThat(refresh(second).getStatusCode().value()).isEqualTo(401);
        assertThat(activeTokensInFamilyOf(second)).isZero();
    }

    @Test
    void reuseDetectionLeavesOtherSessionsAlone() {
        String otherSession = (String) OwnerFixture.login(http).get("refreshToken");
        String first = (String) OwnerFixture.login(http).get("refreshToken");
        refresh(first);

        refresh(first);

        assertThat(refresh(otherSession).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void unknownAndExpiredRefreshTokensAre401() {
        assertThat(refresh("not-a-real-token").getStatusCode().value()).isEqualTo(401);

        String expired = "expired-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO identity.refresh_tokens
                    (id, created_at, updated_at, version, owner_id, family_id, token_hash, expires_at)
                VALUES (gen_random_uuid(), now(), now(), 0, ?, gen_random_uuid(), ?, now() - interval '1 second')
                """, ownerId, sha256(expired));

        assertThat(refresh(expired).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refreshTokensAreStoredOnlyAsTheirSha256Hash() {
        String refreshToken = (String) OwnerFixture.login(http).get("refreshToken");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.refresh_tokens WHERE token_hash = ?",
                Integer.class, sha256(refreshToken))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.refresh_tokens WHERE token_hash = ?",
                Integer.class, refreshToken)).isZero();
    }

    /** Two tabs refreshing with the same token at once: one wins; the other looks like reuse. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void concurrentRefreshesWithOneTokenSucceedAtMostOnce() throws Exception {
        String token = (String) OwnerFixture.login(http).get("refreshToken");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return refresh(token).getStatusCode().value();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(20, TimeUnit.SECONDS));
            }

            assertThat(statuses).containsOnly(200, 401);
            assertThat(statuses).filteredOn(s -> s == 200).hasSizeLessThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- logout ----

    @Test
    void logoutRevokesTheSessionsRefreshTokens() {
        Map<String, Object> tokens = OwnerFixture.login(http);
        String refreshToken = (String) tokens.get("refreshToken");

        ResponseEntity<Void> response = http.post().uri("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("accessToken"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(refresh(refreshToken).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void logoutNeedsAnAccessToken() {
        String refreshToken = (String) OwnerFixture.login(http).get("refreshToken");

        ResponseEntity<Void> response = http.post().uri("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(refresh(refreshToken).getStatusCode().value()).isEqualTo(200);
    }

    // ---- helpers ----

    private ResponseEntity<Map> login(String email, String password) {
        return http.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> refresh(String refreshToken) {
        return http.post().uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> me(String accessToken) {
        return http.get().uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve().toEntity(Map.class);
    }

    private JwtClaimsSet claims(Instant issuedAt, Instant expiresAt, String issuer, String role) {
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(ownerId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of(role))
                .build();
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private int activeTokensInFamilyOf(String refreshToken) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM identity.refresh_tokens
                WHERE revoked_at IS NULL
                  AND family_id = (SELECT family_id FROM identity.refresh_tokens WHERE token_hash = ?)
                """, Integer.class, sha256(refreshToken));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
