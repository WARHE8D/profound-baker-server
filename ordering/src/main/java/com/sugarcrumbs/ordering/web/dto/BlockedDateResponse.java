package com.sugarcrumbs.ordering.web.dto;

import java.time.LocalDate;
import java.util.UUID;

public record BlockedDateResponse(
        UUID id,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        boolean recurringAnnually
) {
}
