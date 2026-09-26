package com.sugarcrumbs.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImageUrlRequest(
        @NotBlank @Size(max = 500) String url
) {
}
