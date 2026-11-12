package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.WorkingHoursService;
import com.sugarcrumbs.ordering.domain.WorkingHours;
import com.sugarcrumbs.ordering.web.dto.WorkingHoursRequest;
import com.sugarcrumbs.ordering.web.dto.WorkingHoursResponse;
import com.sugarcrumbs.ordering.web.mapper.WorkingHoursMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Path uses {@link DayOfWeek} directly (e.g. {@code MONDAY}, upper case,
 * matching the enum's own name) rather than a numeric index — self-
 * documenting in the URL and Spring's default enum converter handles it
 * for free, no custom converter needed.
 */
@RestController
@RequestMapping("/api/availability/working-hours")
public class WorkingHoursController {

    private final WorkingHoursService workingHoursService;
    private final WorkingHoursMapper workingHoursMapper;

    public WorkingHoursController(WorkingHoursService workingHoursService, WorkingHoursMapper workingHoursMapper) {
        this.workingHoursService = workingHoursService;
        this.workingHoursMapper = workingHoursMapper;
    }

    @GetMapping
    public List<WorkingHoursResponse> list() {
        return workingHoursService.listAll().stream()
                .map(workingHoursMapper::toResponse)
                .collect(Collectors.toList());
    }

    /** Upsert: sets (or replaces) the hours for this day of the week. */
    @PutMapping("/{dayOfWeek}")
    public WorkingHoursResponse setHoursFor(@PathVariable DayOfWeek dayOfWeek,
                                            @Valid @RequestBody WorkingHoursRequest request) {
        WorkingHours result = workingHoursService.setHoursFor(dayOfWeek, request);
        return workingHoursMapper.toResponse(result);
    }

    /** Removes the entry for this day — equivalent to declaring the business closed on it. */
    @DeleteMapping("/{dayOfWeek}")
    public ResponseEntity<Void> removeHoursFor(@PathVariable DayOfWeek dayOfWeek) {
        workingHoursService.removeHoursFor(dayOfWeek);
        return ResponseEntity.noContent().build();
    }
}
