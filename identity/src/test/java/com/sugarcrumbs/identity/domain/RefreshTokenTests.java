package com.sugarcrumbs.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenTests {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void aNewFamilyStartsActive() {
        RefreshToken token = RefreshToken.startFamily(UUID.randomUUID(), "hash", NOW.plusSeconds(60));

        assertThat(token.isRevoked()).isFalse();
        assertThat(token.isExpired(NOW)).isFalse();
        assertThat(token.getFamilyId()).isNotNull();
    }

    @Test
    void expiresExactlyAtItsExpiryInstant() {
        RefreshToken token = RefreshToken.startFamily(UUID.randomUUID(), "hash", NOW);

        assertThat(token.isExpired(NOW.minusMillis(1))).isFalse();
        assertThat(token.isExpired(NOW)).isTrue();
    }

    @Test
    void aSuccessorStaysInTheSameFamilyAndOwner() {
        UUID owner = UUID.randomUUID();
        RefreshToken first = RefreshToken.startFamily(owner, "hash-1", NOW.plusSeconds(60));

        RefreshToken second = first.successor("hash-2", NOW.plusSeconds(120));

        assertThat(second.getFamilyId()).isEqualTo(first.getFamilyId());
        assertThat(second.getOwnerId()).isEqualTo(owner);
        assertThat(second.getTokenHash()).isEqualTo("hash-2");
        assertThat(second.isRevoked()).isFalse();
    }

    @Test
    void rotatingRevokesTheTokenAndRecordsItsSuccessor() {
        RefreshToken token = RefreshToken.startFamily(UUID.randomUUID(), "hash", NOW.plusSeconds(60));
        UUID successor = UUID.randomUUID();

        token.rotatedTo(successor, NOW);

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
        assertThat(token.getReplacedById()).isEqualTo(successor);
    }

    @Test
    void aTokenCanBeSpentOnlyOnce() {
        RefreshToken token = RefreshToken.startFamily(UUID.randomUUID(), "hash", NOW.plusSeconds(60));
        token.rotatedTo(UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> token.rotatedTo(UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toStringNeverContainsTheHash() {
        RefreshToken token = RefreshToken.startFamily(UUID.randomUUID(), "secret-hash", NOW.plusSeconds(60));

        assertThat(token.toString()).doesNotContain("secret-hash");
    }
}
