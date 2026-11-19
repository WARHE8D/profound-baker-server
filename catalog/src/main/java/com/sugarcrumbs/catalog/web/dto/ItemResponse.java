package com.sugarcrumbs.catalog.web.dto;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An item as the Owner sees it.
 *
 * @param thumbnailUrls one per {@code imageUrls}, in the same order: the image's thumbnail, or the image's
 *                      own URL when it has none (hosted elsewhere, or uploaded before thumbnails existed)
 */
public record ItemResponse(
        UUID id,
        String name,
        @Nullable String description,
        BigDecimal price,
        CategoryResponse category,
        boolean available,
        int leadTimeHours,
        @Nullable Integer stockQuantity,
        boolean orderable,
        List<String> imageUrls,
        List<String> thumbnailUrls,
        Instant createdAt,
        Instant updatedAt
) {
}