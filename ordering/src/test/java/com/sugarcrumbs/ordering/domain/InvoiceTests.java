package com.sugarcrumbs.ordering.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The invoice's invariants: a well-formed number, a total that is subtotal minus discount, and
 * PENDING → PAID → REFUNDED or PENDING → VOID, nothing else.
 */
class InvoiceTests {

    private static Invoice.Builder valid() {
        Order order = Order.builder().reference("SC-7F3K2").pickupAt(Instant.parse("2026-12-01T10:00:00Z"))
                .guestContact(new GuestContact("Ada", "ada@example.com", "1"))
                .line(UUID.randomUUID(), "Cake", new BigDecimal("40.00"), 1)
                .build();
        return Invoice.builder().order(order).number("INV-2026-0042").subtotal(new BigDecimal("40"));
    }

    @Test
    void anInvoiceStartsPendingWithItsNumberAndFullTotal() {
        Invoice invoice = valid().build();

        assertThat(invoice.getNumber()).isEqualTo("INV-2026-0042");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(invoice.getTotal()).isEqualByComparingTo("40.00");
    }

    @Test
    void numbersMayGrowPastFourDigits() {
        assertThat(valid().number("INV-2027-12345").build().getNumber()).isEqualTo("INV-2027-12345");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "INV-26-0001", "INV-2026-001", "inv-2026-0001", "SC-7F3K2"})
    void aMalformedNumberIsRefused(String number) {
        assertThatIllegalArgumentException().isThrownBy(() -> valid().number(number).build());
        assertThatIllegalArgumentException().isThrownBy(() -> valid().number(null).build());
    }

    @Test
    void aDiscountComesOffTheTotalButNeverBelowZeroOrPastTheSubtotal() {
        Invoice invoice = valid().build();

        invoice.applyDiscount(new BigDecimal("5.5"));

        assertThat(invoice.getTotal()).isEqualByComparingTo("34.50");
        assertThatIllegalArgumentException().isThrownBy(() -> invoice.applyDiscount(new BigDecimal("-1")));
        assertThatIllegalArgumentException().isThrownBy(() -> invoice.applyDiscount(new BigDecimal("40.01")));
    }

    @Test
    void paidThenRefunded() {
        Invoice invoice = valid().build();
        Instant paidAt = Instant.parse("2026-12-01T10:05:00Z");

        invoice.markPaid(PaymentMethod.CASH, null, paidAt);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getPaidAt()).isEqualTo(paidAt);
        assertThatIllegalStateException().isThrownBy(() -> invoice.applyDiscount(BigDecimal.ONE));
        assertThatIllegalStateException().isThrownBy(invoice::voidInvoice);

        invoice.refund();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REFUNDED);
    }

    @Test
    void onlyAPendingInvoiceIsVoidedAndAVoidOneTakesNoPayment() {
        Invoice invoice = valid().build();

        invoice.voidInvoice();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VOID);
        assertThatIllegalStateException().isThrownBy(() -> invoice.markPaid(PaymentMethod.CASH, null, Instant.now()));
        assertThatIllegalStateException().isThrownBy(invoice::refund);
    }
}
