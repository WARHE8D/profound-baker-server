package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.catalog.CatalogApi;
import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.persistence.InvoiceRepository;
import com.sugarcrumbs.ordering.persistence.MonthlyRevenueRow;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import com.sugarcrumbs.ordering.persistence.ProductionRow;
import com.sugarcrumbs.ordering.web.dto.DashboardSummaryResponse;
import com.sugarcrumbs.ordering.web.dto.DashboardSummaryResponse.MonthlyRevenue;
import com.sugarcrumbs.ordering.web.dto.DashboardSummaryResponse.TodaysPickups;
import com.sugarcrumbs.ordering.web.dto.ProductionListResponse;
import com.sugarcrumbs.ordering.web.dto.ProductionListResponse.ProductionItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Read-only views for the Owner's dashboard: the production list (#22) and the summary (#23). */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** Orders the bakery has committed to making: accepted, and not yet collected or called off. */
    private static final Set<OrderStatus> TO_PRODUCE = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS);

    /** Accepted and not called off: what the day's pickups are, collected or not. */
    private static final Set<OrderStatus> PICKUPS =
            EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS, OrderStatus.FULFILLED);

    /** How far back the revenue chart may reach in one request. */
    public static final int MAX_REVENUE_MONTHS = 24;

    private final OrderRepository orderRepository;
    private final CatalogApi catalogApi;
    private final OwnerApi ownerApi;

    private final InvoiceRepository invoiceRepository;

    public DashboardService(OrderRepository orderRepository, InvoiceRepository invoiceRepository,
                            CatalogApi catalogApi, OwnerApi ownerApi) {
        this.orderRepository = orderRepository;
        this.invoiceRepository = invoiceRepository;
        this.catalogApi = catalogApi;
        this.ownerApi = ownerApi;
    }

    /**
     * Today's pickups, the pending backlog and revenue for the last {@code months} calendar months
     * (this one included), all in the bakery's time zone.
     *
     * <p>Revenue counts invoices that are {@code PAID} now, in the month they were paid: a refund
     * takes the payment out of its month again, since the money went back.
     *
     * @throws IllegalArgumentException (400) unless {@code 1 <= months <= MAX_REVENUE_MONTHS}
     */
    public DashboardSummaryResponse summary(int months) {
        if (months < 1 || months > MAX_REVENUE_MONTHS) {
            throw new IllegalArgumentException("months must be between 1 and " + MAX_REVENUE_MONTHS);
        }
        ZoneId zone = ownerApi.settings().timeZone();
        LocalDate today = LocalDate.now(zone);
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        long pickups = orderRepository.countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusIn(
                dayStart, dayEnd, PICKUPS);
        long toCollect = orderRepository.countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusIn(
                dayStart, dayEnd, TO_PRODUCE);

        YearMonth first = YearMonth.from(today).minusMonths(months - 1L);
        YearMonth last = YearMonth.from(today);
        Map<YearMonth, MonthlyRevenueRow> paid = new HashMap<>();
        for (MonthlyRevenueRow row : invoiceRepository.revenueByMonth(zone.getId(),
                first.atDay(1).atStartOfDay(zone).toInstant(),
                last.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())) {
            paid.put(YearMonth.parse(row.getMonth()), row);
        }
        List<MonthlyRevenue> revenue = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            MonthlyRevenueRow row = paid.get(month);
            revenue.add(row == null
                    ? new MonthlyRevenue(month, BigDecimal.ZERO.setScale(2), 0)
                    : new MonthlyRevenue(month, row.getRevenue(), row.getInvoices()));
        }

        return new DashboardSummaryResponse(today, new TodaysPickups(pickups, toCollect),
                orderRepository.countByStatus(OrderStatus.PENDING), revenue);
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
