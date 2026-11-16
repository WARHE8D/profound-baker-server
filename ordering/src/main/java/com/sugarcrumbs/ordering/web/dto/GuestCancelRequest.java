package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.constraints.Size;

/** An optional note for the baker on why the guest cancelled. The body itself may be left out. */
public record GuestCancelRequest(
        @Size(max = 300) String reason
) {
}
