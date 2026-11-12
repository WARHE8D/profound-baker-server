package com.sugarcrumbs.ordering.web.mapper;

import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.ordering.domain.Booking;
import com.sugarcrumbs.ordering.domain.GuestContact;
import com.sugarcrumbs.ordering.web.dto.BookingResponse;
import com.sugarcrumbs.ordering.web.dto.GuestContactResponse;
import com.sugarcrumbs.ordering.web.dto.ItemSummaryResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface BookingMapper {

    GuestContactResponse toGuestContactResponse(GuestContact guestContact);

    /**
     * A booking only stores its item's id, so the caller passes in the item
     * looked up from the catalog.
     *
     * @param item null if the catalog no longer has the item; the summary then carries only the id
     */
    default BookingResponse toResponse(Booking booking, ItemSnapshot item) {
        ItemSummaryResponse itemSummary = item != null
                ? new ItemSummaryResponse(item.id(), item.name(), item.price())
                : new ItemSummaryResponse(booking.getItemId(), null, null);
        return new BookingResponse(
                booking.getId(),
                itemSummary,
                booking.getQuantity(),
                booking.getScheduledFor(),
                booking.getStatus(),
                toGuestContactResponse(booking.getGuestContact()),
                booking.getNotes(),
                booking.getCancellationReason(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }
}
