package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.OrderService;
import com.sugarcrumbs.ordering.web.dto.CheckoutRequest;
import com.sugarcrumbs.ordering.web.dto.OrderResponse;
import com.sugarcrumbs.ordering.web.mapper.OrderMapper;
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
 * holds only what the guest just sent plus the order's reference, snapshot prices
 * and state.
 *
 * <p>No {@code Location} header: the order's own URL is owner-only. Guests get a
 * magic link to view or cancel it with #18/#19.
 */
@RestController
@RequestMapping("/api/public/orders")
public class PublicOrderController {

    private final OrderService orderService;
    private final OrderMapper orderMapper;

    public PublicOrderController(OrderService orderService, OrderMapper orderMapper) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderMapper.toResponse(orderService.place(request)));
    }
}
