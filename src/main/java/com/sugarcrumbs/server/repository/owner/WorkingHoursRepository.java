package com.sugarcrumbs.server.repository.owner;

import com.sugarcrumbs.server.entity.Owner;
import com.sugarcrumbs.server.entity.WorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkingHoursRepository extends JpaRepository<WorkingHours, UUID> {

    Optional<WorkingHours> findByOwnerAndDayOfWeek(Owner owner, DayOfWeek dayOfWeek);

    /**
     * Deliberately unordered here: {@code @Enumerated(EnumType.STRING)}
     * stores the day name as text, so an SQL {@code ORDER BY} on this
     * column would sort alphabetically (FRIDAY, MONDAY, ...) rather than
     * Monday-to-Sunday. The service layer sorts by
     * {@link DayOfWeek#getValue()} instead.
     */
    List<WorkingHours> findAllByOwner(Owner owner);
}
