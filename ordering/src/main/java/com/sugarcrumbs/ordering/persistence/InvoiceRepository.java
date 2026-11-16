package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Invoices are always shown with their order's reference, so every read loads the order with the
 * invoice ({@code @EntityGraph}): one query for a page, not one more per invoice.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    /** An order has at most one invoice (unique {@code order_id}). */
    @EntityGraph(attributePaths = "order")
    Optional<Invoice> findByOrderId(UUID orderId);

    @Override
    @EntityGraph(attributePaths = "order")
    Optional<Invoice> findById(UUID id);

    @Override
    @EntityGraph(attributePaths = "order")
    Page<Invoice> findAll(Specification<Invoice> spec, Pageable pageable);

    /** Next value of the invoice number sequence (ordering V5). Not rolled back with the caller's transaction. */
    @Query(value = "SELECT nextval('ordering.invoice_number_seq')", nativeQuery = true)
    long nextNumber();
}
