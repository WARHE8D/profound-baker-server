package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.WorkingHours;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkingHoursRepository extends JpaRepository<WorkingHours, UUID> {

    /** How long {@link #findForUpdate} waits for another checkout's lock before giving up (409, not a hang). */
    String LOCK_TIMEOUT_MILLIS = "5000";

    Optional<WorkingHours> findByOwnerIdAndDayOfWeek(UUID ownerId, DayOfWeek dayOfWeek);

    /**
     * The same row, locked {@code PESSIMISTIC_WRITE} ({@code SELECT ... FOR UPDATE}) until the
     * caller's transaction ends. Checkout and reschedule take it before counting a slot's
     * occupancy, so two of them can never both see the last free place: the second waits until
     * the first has committed its order, then counts it.
     *
     * <p>A lock that isn't granted within {@link #LOCK_TIMEOUT_MILLIS} fails with a
     * {@code PessimisticLockingFailureException}, which the API turns into a 409.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    @Query("select w from WorkingHours w where w.ownerId = :ownerId and w.dayOfWeek = :dayOfWeek")
    Optional<WorkingHours> findForUpdate(@Param("ownerId") UUID ownerId, @Param("dayOfWeek") DayOfWeek dayOfWeek);

    /**
     * Deliberately unordered here: {@code @Enumerated(EnumType.STRING)}
     * stores the day name as text, so an SQL {@code ORDER BY} on this
     * column would sort alphabetically (FRIDAY, MONDAY, ...) rather than
     * Monday-to-Sunday. The service layer sorts by
     * {@link DayOfWeek#getValue()} instead.
     */
    List<WorkingHours> findAllByOwnerId(UUID ownerId);
}
