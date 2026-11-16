package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.OrderService;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.web.dto.CancelRequest;
import com.sugarcrumbs.ordering.web.dto.OrderResponse;
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
import java.util.UUID;

/**
 * The Owner's orders: list, view and move them through their lifecycle. Owner
 * only. Guests place orders through {@link PublicOrderController}. #16 adds
 * search, rejection and line edits.
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

    /** The owner's calendar: any combination of pickup range and status, all optional. */
    @GetMapping
    public Page<OrderResponse> calendar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 50, sort = "pickupAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return orderService.calendar(from, to, status, pageable).map(orderMapper::toResponse);
    }

    @PatchMapping("/{id}/confirm")
    public OrderResponse confirm(@PathVariable UUID id) {
        return orderMapper.toResponse(orderService.confirm(id));
    }

    @PatchMapping("/{id}/start-preparation")
    public OrderResponse startPreparation(@PathVariable UUID id) {
        return orderMapper.toResponse(orderService.startPreparation(id));
    }

    @PatchMapping("/{id}/fulfill")
    public OrderResponse fulfill(@PathVariable UUID id) {
        return orderMapper.toResponse(orderService.fulfill(id));
    }

    /** Rejects a pending order or cancels a confirmed one; #16 splits out REJECTED. */
    @PatchMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return orderMapper.toResponse(orderService.cancel(id, request.reason()));
    }

    @PutMapping("/{id}/schedule")
    public OrderResponse reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request) {
        return orderMapper.toResponse(orderService.reschedule(id, request.pickupAt()));
    }
}
