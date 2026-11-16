package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.OrderService;
import com.sugarcrumbs.ordering.application.PlacedOrder;
import com.sugarcrumbs.ordering.web.dto.CheckoutRequest;
import com.sugarcrumbs.ordering.web.dto.CheckoutResponse;
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

    /** Places the order and returns it with the guest's magic-link token, the only time it is ever sent. */
    @PostMapping
    public ResponseEntity<CheckoutResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        PlacedOrder placed = orderService.place(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CheckoutResponse(orderMapper.toResponse(placed.order()), placed.accessToken()));
    }
}
