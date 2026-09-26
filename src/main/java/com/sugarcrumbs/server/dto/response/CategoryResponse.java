package com.sugarcrumbs.server.dto.response;

import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name
) {
}
