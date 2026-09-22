package com.sugarcrumbs.server.entity.common;


import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for every JPA entity in the domain.
 *
 * <p>Centralizes the primary key strategy (random UUID — safe to expose
 * in public APIs, unlike sequential IDs) and audit timestamps, and
 * implements equals/hashCode the way JPA entities actually need them:
 *
 * <ul>
 *   <li>equals() is identity-based, by id, and only once both sides are
 *       persisted. Two transient entities are never equal to each
 *       other even with identical fields.</li>
 *   <li>hashCode() is constant per class, not derived from mutable
 *       fields or the (initially null) id — required so an entity's
 *       hash code never changes after being placed in a {@code Set}.</li>
 * </ul>
 *
 * Requires {@code @EnableJpaAuditing} on a configuration class for the
 * {@code @CreatedDate}/{@code @LastModifiedDate} fields to populate.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity implements Serializable {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic locking token — prevents silently lost concurrent updates. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BaseEntity other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
