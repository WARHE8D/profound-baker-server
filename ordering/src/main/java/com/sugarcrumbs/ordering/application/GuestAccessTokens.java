package com.sugarcrumbs.ordering.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The secret in a guest's magic link (#18): 32 random bytes, handed out base64url-encoded once
 * at checkout and stored only as a hex SHA-256 hash, so a database leak yields no working link.
 * The same scheme as the Owner's refresh tokens in {@code identity}, kept separately because
 * modules share no internals.
 *
 * <p>A plain hash (no salt, no BCrypt) is enough: the token is 256 bits of randomness, not a
 * password, so there is nothing to guess and the hash can be looked up directly.
 */
@Component
public class GuestAccessTokens {

    static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** A new token as it goes in the link: 43 URL-safe characters. */
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The 64-character hex SHA-256 of {@code token}: what the database stores and looks up. */
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
