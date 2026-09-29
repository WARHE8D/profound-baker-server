package com.sugarcrumbs.server.controller;

import com.sugarcrumbs.server.dto.response.DayAvailabilityResponse;
import com.sugarcrumbs.server.service.AvailabilityService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only, and deliberately the only way callers learn what's
 * bookable — see {@link AvailabilityService} for why this is
 * centralized rather than left to each caller to work out from
 * {@code WorkingHours}/{@code BlockedDate} directly.
 *
 * <p>Always returns a {@code List<DayAvailabilityResponse>} — a
 * single-date query just returns a one-element list — so API consumers
 * handle one response shape regardless of which query form they used.
 */
@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    /**
     * Either {@code date} alone, or {@code from}+{@code to} together
     * (inclusive range, capped server-side) — never mix them; if
     * {@code date} is supplied it takes priority.
     */
    @GetMapping
    public List<DayAvailabilityResponse> getAvailability(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        if (date != null) {
            return List.of(availabilityService.computeForDate(date));
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("provide either 'date', or both 'from' and 'to'");
        }
        return List.copyOf(availabilityService.computeForRange(from, to).values());
    }
}

