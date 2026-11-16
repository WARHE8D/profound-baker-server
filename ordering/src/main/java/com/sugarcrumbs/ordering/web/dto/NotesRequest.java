package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.constraints.Size;

/** Replaces the order's notes; blank or absent clears them. */
public record NotesRequest(
        @Size(max = 1000) String notes
) {
}
