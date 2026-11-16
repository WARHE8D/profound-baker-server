package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /** Slot occupancy for {@code AvailabilityService}: one order takes one place, however many lines it has. */
    long countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusNot(
            Instant slotStart, Instant slotEnd, OrderStatus excludedStatus);

    /** Same, leaving one order out of its own count: used when checking its new slot during a reschedule. */
    long countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusNotAndIdNot(
            Instant slotStart, Instant slotEnd, OrderStatus excludedStatus, UUID excludedId);

    /** For {@code StaleOrderCleanupJob}. */
    List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    boolean existsByReference(String reference);

    /** Whether any line of any order (cancelled ones included) points at the item. */
    boolean existsByLinesItemId(UUID itemId);
}
