package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.InvoiceService;
import com.sugarcrumbs.ordering.domain.InvoiceStatus;
import com.sugarcrumbs.ordering.web.dto.DiscountRequest;
import com.sugarcrumbs.ordering.web.dto.InvoiceResponse;
import com.sugarcrumbs.ordering.web.dto.PaymentRequest;
import com.sugarcrumbs.ordering.web.mapper.InvoiceMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The Owner's invoices (#21). Owner only. Invoices are issued by confirming an order and voided
 * by cancelling it ({@code OrderController}); here the Owner reads them, applies a discount,
 * records an offline payment and refunds it.
 *
 * <pre>
 * PENDING → PAID → REFUNDED
 *    └→ VOID (order cancelled before payment)
 * </pre>
 */
@RestController
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceMapper invoiceMapper;

    public InvoiceController(InvoiceService invoiceService, InvoiceMapper invoiceMapper) {
        this.invoiceService = invoiceService;
        this.invoiceMapper = invoiceMapper;
    }

    /**
     * Every filter is optional: an issue range ({@code from} inclusive, {@code to} exclusive,
     * either end open), one or more {@code status} values, and {@code q}, a case-insensitive
     * search on the invoice number and the order's reference. Newest first by default.
     */
    @GetMapping("/api/invoices")
    public Page<InvoiceResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) List<InvoiceStatus> status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return invoiceService.search(from, to, status, q, pageable).map(invoiceMapper::toResponse);
    }

    @GetMapping("/api/invoices/{id}")
    public InvoiceResponse get(@PathVariable UUID id) {
        return invoiceMapper.toResponse(invoiceService.getOrThrow(id));
    }

    /** The order's invoice; 404 until the order is confirmed. */
    @GetMapping("/api/orders/{orderId}/invoice")
    public InvoiceResponse getForOrder(@PathVariable UUID orderId) {
        return invoiceMapper.toResponse(invoiceService.getForOrder(orderId));
    }

    /** Pending invoices only. */
    @PatchMapping("/api/invoices/{id}/discount")
    public InvoiceResponse applyDiscount(@PathVariable UUID id, @Valid @RequestBody DiscountRequest request) {
        return invoiceMapper.toResponse(invoiceService.applyDiscount(id, request.amount()));
    }

    /** Records a cash or bank-transfer payment on a pending invoice. */
    @PatchMapping("/api/invoices/{id}/pay")
    public InvoiceResponse markPaid(@PathVariable UUID id, @Valid @RequestBody PaymentRequest request) {
        return invoiceMapper.toResponse(
                invoiceService.markPaid(id, request.method(), request.reference(), request.paidAt()));
    }

    /** The Owner gave a paid invoice's money back. */
    @PatchMapping("/api/invoices/{id}/refund")
    public InvoiceResponse refund(@PathVariable UUID id) {
        return invoiceMapper.toResponse(invoiceService.refund(id));
    }
}
