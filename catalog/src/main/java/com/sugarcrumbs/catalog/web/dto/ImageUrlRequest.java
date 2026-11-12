package com.sugarcrumbs.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImageUrlRequest(
        @NotBlank @Size(max = 500) String url
) {
}
