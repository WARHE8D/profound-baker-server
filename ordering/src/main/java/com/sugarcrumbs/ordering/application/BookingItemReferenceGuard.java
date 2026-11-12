package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.catalog.ItemReferenceGuard;
import com.sugarcrumbs.ordering.persistence.BookingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Stops the catalog from deleting an item that any booking (cancelled ones included) still points at. */
@Component
class BookingItemReferenceGuard implements ItemReferenceGuard {

    private final BookingRepository bookingRepository;

    BookingItemReferenceGuard(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReferenced(UUID itemId) {
        return bookingRepository.existsByItemId(itemId);
    }
}
