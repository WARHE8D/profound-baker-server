package com.sugarcrumbs.ordering.web.mapper;

import com.sugarcrumbs.ordering.domain.GuestContact;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.OrderLine;
import com.sugarcrumbs.ordering.web.dto.GuestContactResponse;
import com.sugarcrumbs.ordering.web.dto.GuestOrderResponse;
import com.sugarcrumbs.ordering.web.dto.OrderLineResponse;
import com.sugarcrumbs.ordering.web.dto.OrderResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

/** Orders carry their own line snapshots, so a response needs no catalog lookup. */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    OrderResponse toResponse(Order order);

    @Mapping(target = "placedAt", source = "order.createdAt")
    GuestOrderResponse toGuestResponse(Order order, Instant cancellableUntil);

    OrderLineResponse toResponse(OrderLine line);

    GuestContactResponse toResponse(GuestContact guestContact);
}
