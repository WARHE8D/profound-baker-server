package com.sugarcrumbs.server.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ItemResponse(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        CategoryResponse category,
        boolean available,
        int leadTimeHours,
        Integer stockQuantity,
        boolean orderable,
        List<String> imageUrls,
        Instant createdAt,
        Instant updatedAt
) {
}