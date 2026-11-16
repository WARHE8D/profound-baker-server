package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.catalog.CatalogApi;
import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.identity.OwnerSettings;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import com.sugarcrumbs.ordering.persistence.ProductionRow;
import com.sugarcrumbs.ordering.web.dto.ProductionListResponse;
import com.sugarcrumbs.ordering.web.dto.ProductionListResponse.ProductionItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The production list (#22) without a database: day boundaries, merging and naming. */
class DashboardServiceTests {

    private static final ZoneId BAKERY = ZoneId.of("Asia/Karachi");
    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final CatalogApi catalogApi = mock(CatalogApi.class);
    private final OwnerApi ownerApi = mock(OwnerApi.class);
    private final DashboardService service = new DashboardService(orderRepository, catalogApi, ownerApi);

    @BeforeEach
    void setUp() {
        when(ownerApi.settings()).thenReturn(new OwnerSettings(BAKERY));
    }

    /** Midnight to midnight in Karachi (UTC+5), not in UTC; only accepted, unfinished orders count. */
    @Test
    void theDayRunsMidnightToMidnightInTheBakerysTimeZone() {
        Instant from = Instant.parse("2026-11-30T19:00:00Z");
        Instant to = Instant.parse("2026-12-01T19:00:00Z");
        EnumSet<OrderStatus> toProduce = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS);
        when(orderRepository.countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusIn(from, to, toProduce))
                .thenReturn(4L);

        ProductionListResponse list = service.production(DAY);

        verify(orderRepository).production(from, to, toProduce);
        assertThat(list.date()).isEqualTo(DAY);
        assertThat(list.orderCount()).isEqualTo(4);
        assertThat(list.items()).isEmpty();
        verifyNoInteractions(catalogApi);
    }

    @Test
    void withoutADateItIsTodayInTheBakery() {
        assertThat(service.production(null).date()).isEqualTo(LocalDate.now(BAKERY));
    }

    @Test
    void anItemRenamedBetweenOrdersIsOneRowUnderItsCurrentNameAndADeletedOneKeepsItsOldName() {
        UUID cake = UUID.randomUUID();
        UUID bread = UUID.randomUUID();
        when(orderRepository.production(any(), any(), anyCollection())).thenReturn(List.of(
                new ProductionRow(cake, "Lemon cake", 2, 2),
                new ProductionRow(cake, "Lemon drizzle", 3, 1),
                new ProductionRow(bread, "Sourdough", 5, 3),
                new ProductionRow(null, "Discontinued tart", 1, 1)));
        when(catalogApi.getItems(anyCollection())).thenReturn(Map.of(
                cake, new ItemSnapshot(cake, "Lemon drizzle cake", BigDecimal.TEN, 0, true, false),
                bread, new ItemSnapshot(bread, "sourdough loaf", BigDecimal.ONE, 0, true, false)));

        List<ProductionItemResponse> items = service.production(DAY).items();

        assertThat(items).containsExactly(
                new ProductionItemResponse(null, "Discontinued tart", 1, 1),
                new ProductionItemResponse(cake, "Lemon drizzle cake", 5, 3),
                new ProductionItemResponse(bread, "sourdough loaf", 5, 3));
        verify(catalogApi).getItems(eq(List.of(cake, bread)));
    }
}
