package com.sugarcrumbs.identity.domain;

import com.sugarcrumbs.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One refresh token issued to the Owner. Only the SHA-256 hash of the token is
 * stored, so a database leak yields nothing usable.
 *
 * <p>Tokens rotate: each refresh revokes the presented token and issues a new
 * one in the same <em>family</em> (everything descended from one login). A
 * revoked token being presented again means it was copied, so the whole family
 * is revoked and the attacker and the Owner both have to log in again.
 */
@Entity
@Table(schema = "identity", name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    /** Same module as {@link Owner}, so a real FK exists in the schema; no JPA relation is needed. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /** Hex SHA-256 of the token handed to the client. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** The token this one was rotated into, for tracing a family. */
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    protected RefreshToken() {
        // required by JPA
    }

    private RefreshToken(UUID ownerId, UUID familyId, String tokenHash, Instant expiresAt) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** First token of a new family, issued at login. */
    public static RefreshToken startFamily(UUID ownerId, String tokenHash, Instant expiresAt) {
        return new RefreshToken(ownerId, UUID.randomUUID(), tokenHash, expiresAt);
    }

    /** The successor of this token in its family. Call {@link #rotatedTo} once it has an id. */
    public RefreshToken successor(String tokenHash, Instant expiresAt) {
        return new RefreshToken(ownerId, familyId, tokenHash, expiresAt);
    }

    // ---- Business behavior ----

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Spends this token on its successor. A token can be spent only once. */
    public void rotatedTo(UUID successorId, Instant now) {
        if (isRevoked()) {
            throw new IllegalStateException("a revoked refresh token cannot be rotated");
        }
        this.revokedAt = now;
        this.replacedById = Objects.requireNonNull(successorId, "successorId");
    }

    // ---- Getters only ----

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedById() {
        return replacedById;
    }

    @Override
    public String toString() {
        // tokenHash intentionally excluded
        return "RefreshToken{id=%s, familyId=%s, revoked=%s}".formatted(getId(), familyId, isRevoked());
    }
}
