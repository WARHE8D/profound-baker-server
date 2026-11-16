package com.sugarcrumbs.ordering.application;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GuestAccessTokensTests {

    private final GuestAccessTokens tokens = new GuestAccessTokens();

    @Test
    void aTokenIs32RandomBytesInUrlSafeBase64() {
        String token = tokens.newToken();

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(GuestAccessTokens.TOKEN_BYTES);
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(tokens.newToken())).isTrue();
        }
    }

    /** Known SHA-256 vector, so the stored format can't drift (case, encoding) without a test failing. */
    @Test
    void theHashIsLowerCaseHexSha256() {
        assertThat(tokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
