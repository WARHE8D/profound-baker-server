package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.common.exception.NotFoundException;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.Invoice;
import com.sugarcrumbs.ordering.domain.InvoiceStatus;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.PaymentMethod;
import com.sugarcrumbs.ordering.persistence.InvoiceRepository;
import com.sugarcrumbs.ordering.persistence.InvoiceSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.util.Collection;
import java.util.UUID;

/**
 * The invoice side of an order's lifecycle (#20): one is issued when the Owner confirms the
 * order, and voided if the order is cancelled before it was paid. Called by {@link OrderService}
 * inside the transaction that changes the order, so the two never disagree.
 *
 * <p>The Owner's invoice API (#21): list and find invoices, apply a discount, record an offline
 * payment and refund it.
 */
@Service
@Transactional
public class InvoiceService {

    /** Longer than any invoice number or order reference it could match. */
    static final int MAX_SEARCH_LENGTH = 50;

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

    @Transactional(readOnly = true)
    public Invoice getOrThrow(UUID id) {
        return invoiceRepository.findById(id).orElseThrow(() -> NotFoundException.forEntity("Invoice", id));
    }

    /** @throws NotFoundException (404) if the order has no invoice: it was never confirmed */
    @Transactional(readOnly = true)
    public Invoice getForOrder(UUID orderId) {
        return invoiceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("no invoice for order " + orderId));
    }

    /**
     * Invoices issued in a range (either end open), in any of several statuses, matching a
     * search on the invoice number or the order's reference; all optional.
     *
     * @throws IllegalArgumentException (400) if {@code from} isn't before {@code to}, or the search is too long
     */
    @Transactional(readOnly = true)
    public Page<Invoice> search(Instant from, Instant to, Collection<InvoiceStatus> statuses, String search,
                                Pageable pageable) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (search != null && search.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search may be at most " + MAX_SEARCH_LENGTH + " characters");
        }
        return invoiceRepository.findAll(InvoiceSpecifications.combine(from, to, statuses, search), pageable);
    }

    /** Replaces the discount (zero removes it); only while unpaid. */
    public Invoice applyDiscount(UUID id, BigDecimal amount) {
        Invoice invoice = getOrThrow(id);
        invoice.applyDiscount(amount);
        return invoice;
    }

    /** Records an offline payment; {@code paidAt} defaults to now. */
    public Invoice markPaid(UUID id, PaymentMethod method, String reference, Instant paidAt) {
        Invoice invoice = getOrThrow(id);
        invoice.markPaid(method, reference, paidAt != null ? paidAt : Instant.now());
        return invoice;
    }

    public Invoice refund(UUID id) {
        Invoice invoice = getOrThrow(id);
        invoice.refund(Instant.now());
        return invoice;
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
