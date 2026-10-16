package com.sugarcrumbs.server.controller;

import com.sugarcrumbs.server.dto.request.BookingCancelRequest;
import com.sugarcrumbs.server.dto.request.BookingCreateRequest;
import com.sugarcrumbs.server.dto.request.BookingRescheduleRequest;
import com.sugarcrumbs.server.dto.response.BookingResponse;
import com.sugarcrumbs.server.entity.Booking;
import com.sugarcrumbs.server.entity.BookingStatus;
import com.sugarcrumbs.server.mapper.BookingMapper;
import com.sugarcrumbs.server.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/bookings} is intentionally open — this app uses
 * guest checkout (see {@code GuestContact}), so no auth is required to
 * create one. Every other endpoint here is an owner action and will
 * need an auth guard once Sprint 1 exists; none of that is wired yet,
 * so treat this controller as fully public for now.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final BookingMapper bookingMapper;

    public BookingController(BookingService bookingService, BookingMapper bookingMapper) {
        this.bookingService = bookingService;
        this.bookingMapper = bookingMapper;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody BookingCreateRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        Booking created = bookingService.create(request);
        URI location = uriBuilder.path("/api/bookings/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(bookingMapper.toResponse(created));
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable UUID id) {
        return bookingMapper.toResponse(bookingService.getOrThrow(id));
    }

    /** The owner's calendar view: any combination of date range + status, both optional. */
    @GetMapping
    public Page<BookingResponse> calendar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) BookingStatus status,
            @PageableDefault(size = 50, sort = "scheduledFor", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return bookingService.calendar(from, to, status, pageable).map(bookingMapper::toResponse);
    }

    @PatchMapping("/{id}/confirm")
    public BookingResponse confirm(@PathVariable UUID id) {
        return bookingMapper.toResponse(bookingService.confirm(id));
    }

    @PatchMapping("/{id}/start-preparation")
    public BookingResponse startPreparation(@PathVariable UUID id) {
        return bookingMapper.toResponse(bookingService.startPreparation(id));
    }

    @PatchMapping("/{id}/fulfill")
    public BookingResponse fulfill(@PathVariable UUID id) {
        return bookingMapper.toResponse(bookingService.fulfill(id));
    }

    /** Used both to reject a still-pending request and to cancel an already-confirmed one. */
    @PatchMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id, @Valid @RequestBody BookingCancelRequest request) {
        return bookingMapper.toResponse(bookingService.cancel(id, request.reason()));
    }

    @PutMapping("/{id}/schedule")
    public BookingResponse reschedule(@PathVariable UUID id, @Valid @RequestBody BookingRescheduleRequest request) {
        return bookingMapper.toResponse(bookingService.reschedule(id, request.scheduledFor()));
    }
}

