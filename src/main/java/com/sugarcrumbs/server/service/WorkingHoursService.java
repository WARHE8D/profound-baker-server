package com.sugarcrumbs.server.service;


import com.sugarcrumbs.server.dto.request.WorkingHoursRequest;
import com.sugarcrumbs.server.entity.Owner;
import com.sugarcrumbs.server.entity.WorkingHours;
import com.sugarcrumbs.server.repository.owner.WorkingHoursRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;

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
    private final OwnerService ownerService;

    public WorkingHoursService(WorkingHoursRepository workingHoursRepository, OwnerService ownerService) {
        this.workingHoursRepository = workingHoursRepository;
        this.ownerService = ownerService;
    }

    public WorkingHours setHoursFor(DayOfWeek dayOfWeek, WorkingHoursRequest request) {
        Owner owner = ownerService.getTheOwner();
        WorkingHours existing = workingHoursRepository.findByOwnerAndDayOfWeek(owner, dayOfWeek).orElse(null);

        if (existing != null) {
            existing.reschedule(request.startTime(), request.endTime());
            existing.changeSlotDuration(request.slotDurationMinutes());
            existing.changeCapacity(request.maxConcurrentBookings());
            return existing;
        }

        WorkingHours created = WorkingHours.builder()
                .owner(owner)
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
        Owner owner = ownerService.getTheOwner();
        return workingHoursRepository.findAllByOwner(owner).stream()
                .sorted(Comparator.comparingInt(wh -> wh.getDayOfWeek().getValue()))
                .toList();
    }

    /** Removing the entry for a day means the business is closed that day — no separate "isOpen" flag needed. */
    public void removeHoursFor(DayOfWeek dayOfWeek) {
        Owner owner = ownerService.getTheOwner();
        workingHoursRepository.findByOwnerAndDayOfWeek(owner, dayOfWeek)
                .ifPresent(workingHoursRepository::delete);
    }
}
