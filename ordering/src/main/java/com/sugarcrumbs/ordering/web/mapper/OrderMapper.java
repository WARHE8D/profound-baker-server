package com.sugarcrumbs.ordering.web.mapper;

import com.sugarcrumbs.ordering.domain.GuestContact;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.OrderLine;
import com.sugarcrumbs.ordering.web.dto.GuestContactResponse;
import com.sugarcrumbs.ordering.web.dto.OrderLineResponse;
import com.sugarcrumbs.ordering.web.dto.OrderResponse;
import org.mapstruct.Mapper;

/** Orders carry their own line snapshots, so a response needs no catalog lookup. */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    OrderResponse toResponse(Order order);

    OrderLineResponse toResponse(OrderLine line);

    GuestContactResponse toResponse(GuestContact guestContact);
}
