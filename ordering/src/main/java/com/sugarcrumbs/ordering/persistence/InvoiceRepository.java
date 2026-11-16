package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    /** An order has at most one invoice (unique {@code order_id}). */
    Optional<Invoice> findByOrderId(UUID orderId);

    /** Next value of the invoice number sequence (ordering V5). Not rolled back with the caller's transaction. */
    @Query(value = "SELECT nextval('ordering.invoice_number_seq')", nativeQuery = true)
    long nextNumber();
}
