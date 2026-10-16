package com.sugarcrumbs.server.mapper;

import com.sugarcrumbs.server.dto.response.BookingResponse;
import com.sugarcrumbs.server.dto.response.GuestContactResponse;
import com.sugarcrumbs.server.dto.response.ItemSummaryResponse;
import com.sugarcrumbs.server.entity.Booking;
import com.sugarcrumbs.server.entity.GuestContact;
import com.sugarcrumbs.server.entity.Item;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface BookingMapper {

    BookingResponse toResponse(Booking booking);

    ItemSummaryResponse toItemSummary(Item item);

    GuestContactResponse toGuestContactResponse(GuestContact guestContact);
}