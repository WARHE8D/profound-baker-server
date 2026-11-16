package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /** Slot occupancy for {@code AvailabilityService}: one order takes one place, however many lines it has. */
    long countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusNotIn(
            Instant slotStart, Instant slotEnd, Collection<OrderStatus> excludedStatuses);

    /** Same, leaving one order out of its own count: used when checking its new slot during a reschedule. */
    long countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusNotInAndIdNot(
            Instant slotStart, Instant slotEnd, Collection<OrderStatus> excludedStatuses, UUID excludedId);

    /** For {@code StaleOrderCleanupJob}. */
    List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    boolean existsByReference(String reference);

    /** The order a guest's magic link points at, by the token's hash (unique). */
    Optional<Order> findByAccessTokenHash(String accessTokenHash);

    /**
     * Forgets the magic links that have expired, so a leaked database holds no hash that ever
     * opened a finished order. Bumps {@code version} like any other change, so a stale copy of
     * one of these orders can't overwrite the purge.
     */
    @Modifying
    @Query("""
            update CustomerOrder o
               set o.accessTokenHash = null, o.accessTokenExpiresAt = null, o.version = o.version + 1
             where o.accessTokenExpiresAt <= :now
            """)
    int purgeExpiredAccessTokens(@Param("now") Instant now);

    /** Whether any line of any order (cancelled ones included) points at the item. */
    boolean existsByLinesItemId(UUID itemId);
}
