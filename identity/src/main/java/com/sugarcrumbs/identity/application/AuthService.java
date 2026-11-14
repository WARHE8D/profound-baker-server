package com.sugarcrumbs.identity.application;

import com.sugarcrumbs.identity.config.JwtProperties;
import com.sugarcrumbs.identity.domain.Owner;
import com.sugarcrumbs.identity.domain.RefreshToken;
import com.sugarcrumbs.identity.persistence.OwnerRepository;
import com.sugarcrumbs.identity.persistence.RefreshTokenRepository;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Owner login, refresh-token rotation and logout (CLAUDE.md §5).
 *
 * <p>Access tokens are RS256 JWTs ({@code sub} = owner id, {@code roles = [OWNER]}),
 * valid for {@code app.security.jwt.access-token-ttl}; they are never stored, so
 * they cannot be revoked and stay short. Refresh tokens are 32 random bytes,
 * stored only as a SHA-256 hash, and rotate on every use (see {@link RefreshToken}).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    static final String ROLE_OWNER = "OWNER";
    private static final String INVALID_LOGIN = "invalid email or password";
    private static final String INVALID_REFRESH = "invalid or expired refresh token";
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final OwnerRepository ownerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final RSAKey signingKey;
    private final JwtProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    /** Compared against when the email is unknown, so both failures take as long as a real BCrypt check. */
    private volatile String dummyHash;

    public AuthService(OwnerRepository ownerRepository, RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, RSAKey jwtSigningKey,
                       JwtProperties properties) {
        this.ownerRepository = ownerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.signingKey = jwtSigningKey;
        this.properties = properties;
    }

    /**
     * Checks the Owner's credentials and starts a new refresh-token family.
     *
     * @throws BadCredentialsException with the same message whether the email or the password was wrong
     */
    @Transactional
    public IssuedTokens login(String email, String password) {
        Owner owner = ownerRepository.findByEmail(email.strip().toLowerCase(Locale.ROOT)).orElse(null);
        if (owner == null) {
            passwordEncoder.matches(password, dummyHash());
            log.info("Owner login failed");
            throw new BadCredentialsException(INVALID_LOGIN);
        }
        if (!passwordEncoder.matches(password, owner.getPasswordHash())) {
            log.info("Owner login failed");
            throw new BadCredentialsException(INVALID_LOGIN);
        }
        Instant now = clock.instant();
        String refreshToken = newRefreshToken();
        refreshTokenRepository.save(RefreshToken.startFamily(owner.getId(), hash(refreshToken),
                now.plus(properties.refreshTokenTtl())));
        return issue(owner.getId(), refreshToken, now);
    }

    /**
     * Spends a refresh token on a new access token and a new refresh token.
     *
     * <p>Presenting a token that was already spent or revoked revokes its whole family.
     * That revocation must survive the 401 that follows, hence {@code noRollbackFor}.
     *
     * @throws BadCredentialsException if the token is unknown, expired, revoked or reused
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public IssuedTokens refresh(String refreshToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .orElseThrow(() -> new BadCredentialsException(INVALID_REFRESH));
        if (current.isRevoked()) {
            int revoked = refreshTokenRepository.revokeFamily(current.getFamilyId(), now);
            log.warn("Revoked refresh token reused; revoked its family {} ({} active tokens)",
                    current.getFamilyId(), revoked);
            throw new BadCredentialsException(INVALID_REFRESH);
        }
        if (current.isExpired(now)) {
            throw new BadCredentialsException(INVALID_REFRESH);
        }
        if (!ownerRepository.existsById(current.getOwnerId())) {
            throw new BadCredentialsException(INVALID_REFRESH);
        }

        String next = newRefreshToken();
        RefreshToken successor = refreshTokenRepository.save(
                current.successor(hash(next), now.plus(properties.refreshTokenTtl())));
        current.rotatedTo(successor.getId(), now);
        return issue(current.getOwnerId(), next, now);
    }

    /**
     * Ends the session the refresh token belongs to by revoking its family. Access tokens
     * already issued stay valid until they expire (at most the access-token lifetime).
     * Unknown tokens and other owners' tokens are ignored, so logout is idempotent.
     */
    @Transactional
    public void logout(UUID ownerId, String refreshToken) {
        refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .filter(token -> token.getOwnerId().equals(ownerId))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    private IssuedTokens issue(UUID ownerId, String refreshToken, Instant now) {
        Instant accessExpiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(ownerId.toString())
                .issuedAt(now)
                .expiresAt(accessExpiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", List.of(ROLE_OWNER))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedTokens(accessToken, accessExpiresAt, refreshToken, now.plus(properties.refreshTokenTtl()));
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 is enough here (unlike for passwords): the token is 256 bits of randomness, so
     * there is nothing to brute-force, and a fast hash keeps the lookup an indexed equality.
     */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            dummyHash = hash;
        }
        return hash;
    }

    /** Tokens handed to the client after login or refresh. */
    public record IssuedTokens(String accessToken, Instant accessTokenExpiresAt,
                               String refreshToken, Instant refreshTokenExpiresAt) {
        @Override
        public String toString() {
            return "IssuedTokens[redacted]";
        }
    }
}
