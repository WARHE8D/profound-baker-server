package com.sugarcrumbs.catalog.web.dto;

import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name
) {
}
