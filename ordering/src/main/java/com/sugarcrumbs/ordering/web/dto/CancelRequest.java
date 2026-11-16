package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.constraints.Size;

public record CancelRequest(
        @Size(max = 300) String reason
) {
}
