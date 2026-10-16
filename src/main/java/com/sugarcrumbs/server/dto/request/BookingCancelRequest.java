package com.sugarcrumbs.server.dto.request;

import jakarta.validation.constraints.Size;

public record BookingCancelRequest(
        @Size(max = 300) String reason
) {
}