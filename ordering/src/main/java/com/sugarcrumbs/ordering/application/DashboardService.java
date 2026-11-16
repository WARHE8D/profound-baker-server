package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.catalog.CatalogApi;
import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import com.sugarcrumbs.ordering.persistence.ProductionRow;
import com.sugarcrumbs.ordering.web.dto.ProductionListResponse;
import com.sugarcrumbs.ordering.web.dto.ProductionListResponse.ProductionItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Read-only views for the Owner's dashboard (#22). */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** Orders the bakery has committed to making: accepted, and not yet collected or called off. */
    private static final Set<OrderStatus> TO_PRODUCE = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS);

    private final OrderRepository orderRepository;
    private final CatalogApi catalogApi;
    private final OwnerApi ownerApi;

    public DashboardService(OrderRepository orderRepository, CatalogApi catalogApi, OwnerApi ownerApi) {
        this.orderRepository = orderRepository;
        this.catalogApi = catalogApi;
        this.ownerApi = ownerApi;
    }

    /**
     * Units per item for one day's pickups. The day runs midnight to midnight in the bakery's time
     * zone, and defaults to today there.
     *
     * <p>The database sums per item and per snapshotted name; an item renamed between orders comes
     * back once per name, so rows are merged here by item and shown under the catalog's current
     * name (one batch lookup). A deleted item keeps the name its orders recorded.
     */
    public ProductionListResponse production(LocalDate date) {
        ZoneId zone = ownerApi.settings().timeZone();
        LocalDate day = date != null ? date : LocalDate.now(zone);
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();

        Map<Object, ProductionItemResponse> byItem = new LinkedHashMap<>();
        for (ProductionRow row : orderRepository.production(from, to, TO_PRODUCE)) {
            // Lines of deleted items have no id left; their snapshotted name is all that tells them apart.
            Object key = row.itemId() != null ? row.itemId() : "name:" + row.itemName();
            byItem.merge(key, new ProductionItemResponse(row.itemId(), row.itemName(), row.quantity(), row.orders()),
                    (a, b) -> new ProductionItemResponse(a.itemId(), a.itemName(), a.quantity() + b.quantity(),
                            a.orderCount() + b.orderCount()));
        }

        List<UUID> itemIds = byItem.values().stream().map(ProductionItemResponse::itemId)
                .filter(Objects::nonNull).toList();
        Map<UUID, ItemSnapshot> current = itemIds.isEmpty() ? Map.of() : catalogApi.getItems(itemIds);
        List<ProductionItemResponse> items = byItem.values().stream()
                .map(item -> {
                    ItemSnapshot now = item.itemId() != null ? current.get(item.itemId()) : null;
                    return now == null ? item
                            : new ProductionItemResponse(item.itemId(), now.name(), item.quantity(), item.orderCount());
                })
                .sorted(Comparator.comparing(ProductionItemResponse::itemName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        long orderCount = orderRepository.countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusIn(
                from, to, TO_PRODUCE);
        return new ProductionListResponse(day, orderCount, items);
    }
}
