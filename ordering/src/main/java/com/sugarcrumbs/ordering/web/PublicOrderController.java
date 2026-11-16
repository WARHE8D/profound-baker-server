package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.BookingService;
import com.sugarcrumbs.ordering.web.dto.BookingCreateRequest;
import com.sugarcrumbs.ordering.web.dto.BookingResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guest checkout: the one public write. No account and no token; validated like
 * every request and rate-limited per IP (the app's RateLimitFilter). The response
 * holds only what the guest just sent plus the booking's state.
 *
 * <p>No {@code Location} header: the booking's own URL is owner-only. Guests get a
 * magic link to view or cancel it with #18/#19. Until #14 the body is still a
 * single-item booking.
 */
@RestController
@RequestMapping("/api/public/orders")
public class PublicOrderController {

    private final BookingService bookingService;
    private final BookingResponses bookingResponses;

    public PublicOrderController(BookingService bookingService, BookingResponses bookingResponses) {
        this.bookingService = bookingService;
        this.bookingResponses = bookingResponses;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> checkout(@Valid @RequestBody BookingCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingResponses.of(bookingService.create(request)));
    }
}
