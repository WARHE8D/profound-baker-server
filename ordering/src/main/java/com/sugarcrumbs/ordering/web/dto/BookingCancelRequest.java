package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.constraints.Size;

public record BookingCancelRequest(
        @Size(max = 300) String reason
) {
}