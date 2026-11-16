package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.identity.OwnerSettings;
import com.sugarcrumbs.ordering.domain.GuestContact;
import com.sugarcrumbs.ordering.domain.Invoice;
import com.sugarcrumbs.ordering.domain.InvoiceStatus;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.PaymentMethod;
import com.sugarcrumbs.ordering.persistence.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Issuing on confirm and voiding on cancel (#20), without a database. */
class InvoiceServiceTests {

    private static final ZoneId BAKERY = ZoneId.of("Asia/Karachi");

    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final OwnerApi ownerApi = mock(OwnerApi.class);
    private final InvoiceService service = new InvoiceService(invoiceRepository, ownerApi);

    private final Order order = Order.builder().reference("SC-7F3K2").pickupAt(Instant.parse("2026-12-01T10:00:00Z"))
            .guestContact(new GuestContact("Ada", "ada@example.com", "1"))
            .line(UUID.randomUUID(), "Bread", new BigDecimal("6.50"), 2)
            .line(UUID.randomUUID(), "Cake", new BigDecimal("24.99"), 1)
            .build();

    @BeforeEach
    void setUp() {
        when(ownerApi.settings()).thenReturn(new OwnerSettings(BAKERY));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void anIssuedInvoiceBillsTheOrdersSubtotalUnderTheNextNumber() {
        when(invoiceRepository.nextNumber()).thenReturn(7L);

        Invoice invoice = service.issueFor(order);

        assertThat(invoice.getNumber()).isEqualTo("INV-" + Year.now(BAKERY) + "-0007");
        assertThat(invoice.getOrder()).isSameAs(order);
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("37.99");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING);
    }

    @Test
    void theNumberIsNotCutOffPastFourDigits() {
        when(invoiceRepository.nextNumber()).thenReturn(12345L);

        assertThat(service.issueFor(order).getNumber()).endsWith("-12345");
    }

    @Test
    void cancellingVoidsAPendingInvoiceButLeavesAPaidOneAlone() {
        Invoice pending = invoice();
        when(invoiceRepository.findByOrderId(any())).thenReturn(Optional.of(pending));
        service.voidIfPending(order);
        assertThat(pending.getStatus()).isEqualTo(InvoiceStatus.VOID);

        Invoice paid = invoice();
        paid.markPaid(PaymentMethod.CASH, null, Instant.now());
        when(invoiceRepository.findByOrderId(any())).thenReturn(Optional.of(paid));
        service.voidIfPending(order);
        assertThat(paid.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void anOrderWithoutAnInvoiceHasNothingToVoid() {
        when(invoiceRepository.findByOrderId(any())).thenReturn(Optional.empty());

        service.voidIfPending(order);
    }

    private Invoice invoice() {
        return Invoice.builder().order(order).number("INV-2026-0001").subtotal(order.getSubtotal()).build();
    }
}
