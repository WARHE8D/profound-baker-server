package com.sugarcrumbs.identity.config;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsaKeysTests {

    private static final KeyPair PAIR = generate(2048);

    @Test
    void parsesOneLineBase64Der() throws Exception {
        RSAKey key = RsaKeys.parse(Base64.getEncoder().encodeToString(PAIR.getPrivate().getEncoded()));

        assertThat(key.toRSAPublicKey()).isEqualTo(PAIR.getPublic());
        assertThat(key.isPrivate()).isTrue();
    }

    @Test
    void parsesPem() throws Exception {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(PAIR.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        assertThat(RsaKeys.parse(pem).toRSAPublicKey()).isEqualTo(PAIR.getPublic());
    }

    @Test
    void keyIdIsStableForTheSameKeyAndDiffersBetweenKeys() {
        String encoded = Base64.getEncoder().encodeToString(PAIR.getPrivate().getEncoded());

        assertThat(RsaKeys.parse(encoded).getKeyID())
                .isNotBlank()
                .isEqualTo(RsaKeys.parse(encoded).getKeyID())
                .isNotEqualTo(RsaKeys.generate().getKeyID());
    }

    @Test
    void rejectsPkcs1WithAHintToConvert() {
        assertThatThrownBy(() -> RsaKeys.parse("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pkcs8");
    }

    @Test
    void rejectsGarbageWithoutEchoingIt() {
        assertThatThrownBy(() -> RsaKeys.parse("not-a-key-SECRET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SECRET")
                .hasNoCause();
    }

    @Test
    void rejectsKeysShorterThan2048Bits() {
        String weak = Base64.getEncoder().encodeToString(generate(1024).getPrivate().getEncoded());

        assertThatThrownBy(() -> RsaKeys.parse(weak))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void generatedKeysAreRs256SigningKeys() {
        RSAKey key = RsaKeys.generate();

        assertThat(key.size()).isGreaterThanOrEqualTo(2048);
        assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(key.toPublicJWK().isPrivate()).isFalse();
    }

    private static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
