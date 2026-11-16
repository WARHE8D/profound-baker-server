package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.catalog.ItemReferenceGuard;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Stops the catalog from deleting an item that any order line (cancelled orders
 * included) still points at. Lines keep their own name/price snapshot, so this
 * is no longer about losing history: cancelling such an order puts stock back
 * on the item, which must still exist. Goes away once items are soft-deleted.
 */
@Component
class OrderItemReferenceGuard implements ItemReferenceGuard {

    private final OrderRepository orderRepository;

    OrderItemReferenceGuard(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReferenced(UUID itemId) {
        return orderRepository.existsByLinesItemId(itemId);
    }
}
