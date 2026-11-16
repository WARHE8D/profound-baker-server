package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Unlike a cancellation, a rejection always says why: the guest is owed an answer. */
public record RejectRequest(
        @NotBlank @Size(max = 300) String reason
) {
}
