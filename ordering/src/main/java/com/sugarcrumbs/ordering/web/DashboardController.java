package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.DashboardService;
import com.sugarcrumbs.ordering.web.dto.DashboardSummaryResponse;
import com.sugarcrumbs.ordering.web.dto.ProductionListResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The Owner's dashboard. Owner only. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * Today's pickups, how many orders wait for a decision, and revenue from paid invoices for the
     * last {@code months} months (default 12, at most 24), oldest first.
     */
    @GetMapping("/summary")
    public DashboardSummaryResponse summary(@RequestParam(defaultValue = "12") int months) {
        return dashboardService.summary(months);
    }

    /**
     * What to bake for one day's pickups: units per item across its confirmed and in-progress
     * orders. {@code date} is a day in the bakery's time zone ({@code 2026-12-01}), today if left out.
     * Not paginated: one day's list is bounded by the size of the catalog.
     */
    @GetMapping("/production")
    public ProductionListResponse production(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboardService.production(date);
    }
}
