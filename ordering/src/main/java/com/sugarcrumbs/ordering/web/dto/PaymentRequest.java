package com.sugarcrumbs.ordering.web.dto;

import com.sugarcrumbs.ordering.domain.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * An offline payment the Owner received.
 *
 * @param reference required for {@code BANK_TRANSFER} (to match the bank statement), optional for cash
 * @param paidAt    when the money came in; defaults to now, may be earlier, never later
 */
public record PaymentRequest(
        @NotNull PaymentMethod method,
        @Size(max = 200) String reference,
        @PastOrPresent Instant paidAt
) {
}
