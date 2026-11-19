package com.sugarcrumbs.ordering.web.dto;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.UUID;

public record BlockedDateResponse(
        UUID id,
        LocalDate startDate,
        LocalDate endDate,
        @Nullable String reason,
        boolean recurringAnnually
) {
}
