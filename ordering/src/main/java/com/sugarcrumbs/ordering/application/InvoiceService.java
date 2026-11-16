package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.Invoice;
import com.sugarcrumbs.ordering.domain.InvoiceStatus;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.persistence.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Year;

/**
 * The invoice side of an order's lifecycle (#20): one is issued when the Owner confirms the
 * order, and voided if the order is cancelled before it was paid. Called by {@link OrderService}
 * inside the transaction that changes the order, so the two never disagree.
 */
@Service
@Transactional
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final OwnerApi ownerApi;

    public InvoiceService(InvoiceRepository invoiceRepository, OwnerApi ownerApi) {
        this.invoiceRepository = invoiceRepository;
        this.ownerApi = ownerApi;
    }

    /**
     * Issues the order's invoice, for its subtotal as the line snapshots now stand (lines can't
     * change once confirmed). The number's year is the year of issue in the bakery's time zone,
     * so an order confirmed on New Year's Eve evening isn't billed in the next year.
     */
    public Invoice issueFor(Order order) {
        int year = Year.from(Instant.now().atZone(ownerApi.settings().timeZone())).getValue();
        String number = "INV-%d-%04d".formatted(year, invoiceRepository.nextNumber());
        return invoiceRepository.save(Invoice.builder()
                .order(order)
                .number(number)
                .subtotal(order.getSubtotal())
                .build());
    }

    /**
     * Voids the order's invoice if nothing was paid on it. A paid invoice is left as it is: money
     * changed hands, and giving it back is the Owner's call (a refund).
     */
    public void voidIfPending(Order order) {
        invoiceRepository.findByOrderId(order.getId())
                .filter(invoice -> invoice.getStatus() == InvoiceStatus.PENDING)
                .ifPresent(Invoice::voidInvoice);
    }
}
