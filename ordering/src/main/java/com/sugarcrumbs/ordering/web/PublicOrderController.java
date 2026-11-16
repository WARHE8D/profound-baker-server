package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.OrderService;
import com.sugarcrumbs.ordering.application.PlacedOrder;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.web.dto.CheckoutRequest;
import com.sugarcrumbs.ordering.web.dto.CheckoutResponse;
import com.sugarcrumbs.ordering.web.dto.GuestCancelRequest;
import com.sugarcrumbs.ordering.web.dto.GuestOrderResponse;
import com.sugarcrumbs.ordering.web.mapper.OrderMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The guest's side of an order: checkout, then viewing or cancelling it through the magic link
 * returned at checkout. No account: the link's token is the credential. Validated like every
 * request and rate-limited per IP (the app's RateLimitFilter).
 *
 * <p>No {@code Location} header on checkout: the order's own URL is owner-only, and the guest's
 * is built from the token.
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

    /** The guest's order. 404 for any token that doesn't open one right now, expired or never valid. */
    @GetMapping("/by-token/{token}")
    public GuestOrderResponse view(@PathVariable String token) {
        return toGuestResponse(orderService.findByAccessToken(token));
    }

    /**
     * The guest cancels while the order is pending or confirmed, up to
     * {@code app.order.guest-cancel-cutoff-hours} before pickup (409 after that). The body is optional.
     */
    @PostMapping("/by-token/{token}/cancel")
    public GuestOrderResponse cancel(@PathVariable String token,
                                     @Valid @RequestBody(required = false) GuestCancelRequest request) {
        return toGuestResponse(orderService.cancelByGuest(token, request != null ? request.reason() : null));
    }

    private GuestOrderResponse toGuestResponse(Order order) {
        return orderMapper.toGuestResponse(order, orderService.guestCancellableUntil(order).orElse(null));
    }
}
