package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.WorkingHours;
import com.sugarcrumbs.ordering.persistence.WorkingHoursRepository;
import com.sugarcrumbs.ordering.web.dto.WorkingHoursRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "Set my Tuesday hours" is naturally idempotent — the owner doesn't
 * think in terms of a {@code WorkingHours} row's UUID, they think in
 * terms of the day itself. So {@link #setHoursFor} upserts by
 * {@link DayOfWeek} (backed by the entity's unique
 * {@code (owner_id, day_of_week)} constraint) instead of requiring a
 * separate "look up the id, then PATCH it" round trip.
 */
@Service
@Transactional
public class WorkingHoursService {

    private final WorkingHoursRepository workingHoursRepository;
    private final OwnerApi ownerApi;

    public WorkingHoursService(WorkingHoursRepository workingHoursRepository, OwnerApi ownerApi) {
        this.workingHoursRepository = workingHoursRepository;
        this.ownerApi = ownerApi;
    }

    public WorkingHours setHoursFor(DayOfWeek dayOfWeek, WorkingHoursRequest request) {
        UUID ownerId = ownerApi.currentOwnerId();
        WorkingHours existing = workingHoursRepository.findByOwnerIdAndDayOfWeek(ownerId, dayOfWeek).orElse(null);

        if (existing != null) {
            existing.reschedule(request.startTime(), request.endTime());
            existing.changeSlotDuration(request.slotDurationMinutes());
            existing.changeCapacity(request.maxConcurrentBookings());
            return existing;
        }

        WorkingHours created = WorkingHours.builder()
                .ownerId(ownerId)
                .dayOfWeek(dayOfWeek)
                .startTime(request.startTime())
                .endTime(request.endTime())
                .slotDurationMinutes(request.slotDurationMinutes())
                .maxConcurrentBookings(request.maxConcurrentBookings())
                .build();
        return workingHoursRepository.save(created);
    }

    /** Sorted Monday-first for a natural weekly display, regardless of how the rows come back from the DB. */
    @Transactional(readOnly = true)
    public List<WorkingHours> listAll() {
        UUID ownerId = ownerApi.currentOwnerId();
        return workingHoursRepository.findAllByOwnerId(ownerId).stream()
                .sorted(Comparator.comparingInt(wh -> wh.getDayOfWeek().getValue()))
                .toList();
    }

    /** Removing the entry for a day means the business is closed that day — no separate "isOpen" flag needed. */
    public void removeHoursFor(DayOfWeek dayOfWeek) {
        UUID ownerId = ownerApi.currentOwnerId();
        workingHoursRepository.findByOwnerIdAndDayOfWeek(ownerId, dayOfWeek)
                .ifPresent(workingHoursRepository::delete);
    }
}
