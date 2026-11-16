package com.sugarcrumbs.ordering.web.mapper;

import com.sugarcrumbs.ordering.domain.Invoice;
import com.sugarcrumbs.ordering.web.dto.InvoiceResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** The order is loaded with the invoice ({@code InvoiceRepository}'s entity graphs), so this triggers no query. */
@Mapper(componentModel = "spring")
public interface InvoiceMapper {

    @Mapping(target = "orderId", source = "order.id")
    @Mapping(target = "orderReference", source = "order.reference")
    @Mapping(target = "issuedAt", source = "createdAt")
    InvoiceResponse toResponse(Invoice invoice);
}
