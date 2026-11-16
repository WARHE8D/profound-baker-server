package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.OrderService;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.web.dto.CancelRequest;
import com.sugarcrumbs.ordering.web.dto.EditLinesRequest;
import com.sugarcrumbs.ordering.web.dto.NotesRequest;
import com.sugarcrumbs.ordering.web.dto.OrderResponse;
import com.sugarcrumbs.ordering.web.dto.RejectRequest;
import com.sugarcrumbs.ordering.web.dto.RescheduleRequest;
import com.sugarcrumbs.ordering.web.mapper.OrderMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The Owner's orders: list, search, view, edit while pending and move them through their
 * lifecycle. Owner only. Guests place orders through {@link PublicOrderController}.
 *
 * <pre>
 * PENDING → CONFIRMED → IN_PROGRESS → FULFILLED
 *    │          └──────────────────────↗
 *    └→ REJECTED          CONFIRMED / IN_PROGRESS → CANCELLED
 * </pre>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderMapper orderMapper;

    public OrderController(OrderService orderService, OrderMapper orderMapper) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return orderMapper.toResponse(orderService.getOrThrow(id));
    }

    /**
     * The Owner's calendar and order list. Every filter is optional: a pickup range
     * ({@code from} inclusive, {@code to} exclusive, either end open), one or more {@code status}
     * values ({@code ?status=PENDING&status=CONFIRMED}) and {@code q}, a case-insensitive search
     * on the reference and the guest's name, email and phone.
     */
    @GetMapping
    public Page<OrderResponse> calendar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 50, sort = "pickupAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return orderService.calendar(from, to, status, q, pageable).map(orderMapper::toResponse);
    }

    @PatchMapping("/{id}/confirm")
    public OrderResponse confirm(@PathVariable UUID id) {
        return orderMapper.toResponse(orderService.confirm(id));
    }

    /** Turns down a pending order, with a reason for the guest. */
    @PatchMapping("/{id}/reject")
    public OrderResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest request) {
        return orderMapper.toResponse(orderService.reject(id, request.reason()));
    }

    @PatchMapping("/{id}/start-preparation")
    public OrderResponse startPreparation(@PathVariable UUID id) {
        return orderMapper.toResponse(orderService.startPreparation(id));
    }

    @PatchMapping("/{id}/fulfill")
    public OrderResponse fulfill(@PathVariable UUID id) {
        return orderMapper.toResponse(orderService.fulfill(id));
    }

    /** Calls off a confirmed or in-progress order. A pending one is rejected instead (400). */
    @PatchMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return orderMapper.toResponse(orderService.cancel(id, request.reason()));
    }

    @PutMapping("/{id}/schedule")
    public OrderResponse reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request) {
        return orderMapper.toResponse(orderService.reschedule(id, request.pickupAt()));
    }

    /** Replaces a pending order's lines; items left out are removed. */
    @PutMapping("/{id}/lines")
    public OrderResponse editLines(@PathVariable UUID id, @Valid @RequestBody EditLinesRequest request) {
        return orderMapper.toResponse(orderService.editLines(id, request.lines()));
    }

    /** Replaces a pending order's notes. */
    @PutMapping("/{id}/notes")
    public OrderResponse updateNotes(@PathVariable UUID id, @Valid @RequestBody NotesRequest request) {
        return orderMapper.toResponse(orderService.updateNotes(id, request.notes()));
    }
}
