package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.catalog.CatalogApi;
import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.ordering.domain.Booking;
import com.sugarcrumbs.ordering.web.dto.BookingResponse;
import com.sugarcrumbs.ordering.web.mapper.BookingMapper;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds booking responses, adding item details from the catalog. A page of
 * bookings costs one batch lookup, not one per row, which matters once the
 * catalog is a remote service.
 */
@Component
class BookingResponses {

    private final CatalogApi catalogApi;
    private final BookingMapper bookingMapper;

    BookingResponses(CatalogApi catalogApi, BookingMapper bookingMapper) {
        this.catalogApi = catalogApi;
        this.bookingMapper = bookingMapper;
    }

    BookingResponse of(Booking booking) {
        Map<UUID, ItemSnapshot> items = catalogApi.getItems(List.of(booking.getItemId()));
        return bookingMapper.toResponse(booking, items.get(booking.getItemId()));
    }

    Page<BookingResponse> of(Page<Booking> bookings) {
        Map<UUID, ItemSnapshot> items = catalogApi.getItems(
                bookings.stream().map(Booking::getItemId).collect(Collectors.toSet()));
        return bookings.map(booking -> bookingMapper.toResponse(booking, items.get(booking.getItemId())));
    }
}
