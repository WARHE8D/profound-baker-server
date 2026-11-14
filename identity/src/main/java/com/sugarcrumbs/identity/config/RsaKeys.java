package com.sugarcrumbs.identity.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Turns the configured signing key into a JWK. Only the private key is configured:
 * an RSA CRT private key carries the modulus and public exponent, so the public
 * half is derived from it and can never drift out of sync.
 */
final class RsaKeys {

    static final int MIN_KEY_BITS = 2048;

    private RsaKeys() {
    }

    /**
     * Parses a PKCS#8 RSA private key given as PEM ({@code -----BEGIN PRIVATE KEY-----}) or as the
     * base64 of its DER bytes (one line, convenient in an env var). The key id is the JWK thumbprint,
     * so it changes exactly when the key does.
     */
    static RSAKey parse(String encoded) {
        if (encoded.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException("the JWT private key is PKCS#1; convert it to PKCS#8 with "
                    + "'openssl pkcs8 -topk8 -nocrypt -in key.pem'");
        }
        String base64 = encoded.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        PrivateKey decoded;
        RSAPublicKey publicKey;
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            decoded = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            if (!(decoded instanceof RSAPrivateCrtKey crt)) {
                throw new InvalidKeySpecException("not an RSA CRT key");
            }
            publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            // No cause and no input in the message: either could echo the secret into a log.
            throw new IllegalArgumentException("app.security.jwt.private-key is not a valid PKCS#8 RSA private key");
        }
        return toJwk(publicKey, (RSAPrivateCrtKey) decoded);
    }

    /** A fresh key pair that lives only as long as this process. */
    static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_BITS);
            KeyPair pair = generator.generateKeyPair();
            return toJwk((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate an RSA key pair", e);
        }
    }

    private static RSAKey toJwk(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey) {
        BigInteger modulus = publicKey.getModulus();
        if (modulus.bitLength() < MIN_KEY_BITS) {
            throw new IllegalArgumentException(
                    "the JWT private key must be at least " + MIN_KEY_BITS + " bits, got " + modulus.bitLength());
        }
        try {
            RSAKey key = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("cannot compute the JWK thumbprint", e);
        }
    }
}
