package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.ordering.domain.Order;

/**
 * A new order and the guest's magic-link token. The token exists only here, on its way into the
 * checkout response: the order keeps just its hash.
 */
public record PlacedOrder(Order order, String accessToken) {

    /** Never print the token, e.g. in a log line or an assertion message. */
    @Override
    public String toString() {
        return "PlacedOrder{order=" + order + ", accessToken=***}";
    }
}
