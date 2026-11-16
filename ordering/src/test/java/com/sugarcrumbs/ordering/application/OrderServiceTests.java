package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.catalog.CatalogApi;
import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.identity.OwnerSettings;
import com.sugarcrumbs.ordering.config.OrderProperties;
import com.sugarcrumbs.ordering.domain.GuestContact;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import com.sugarcrumbs.ordering.persistence.WorkingHoursRepository;
import com.sugarcrumbs.ordering.web.dto.CheckoutRequest;
import com.sugarcrumbs.ordering.web.dto.DayAvailabilityResponse;
import com.sugarcrumbs.ordering.web.dto.GuestContactRequest;
import com.sugarcrumbs.ordering.web.dto.OrderLineRequest;
import com.sugarcrumbs.ordering.web.dto.TimeSlotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Checkout rules (#15) that don't need a database: the configurable size limits, pickup times
 * that aren't in the future, and the slot lock being taken before capacity is counted. The race
 * itself is proven against Postgres by {@code CheckoutConcurrencyTests} in {@code app}.
 */
class OrderServiceTests {

    private static final OrderProperties LIMITS = new OrderProperties(24, 3, 5);

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final WorkingHoursRepository workingHoursRepository = mock(WorkingHoursRepository.class);
    private final CatalogApi catalogApi = mock(CatalogApi.class);
    private final AvailabilityService availabilityService = mock(AvailabilityService.class);
    private final OwnerApi ownerApi = mock(OwnerApi.class);
    private final OrderService service = new OrderService(orderRepository, workingHoursRepository, catalogApi,
            availabilityService, ownerApi, LIMITS);

    private final UUID ownerId = UUID.randomUUID();
    /** Tomorrow 09:00 UTC: comfortably in the future and the start of the stubbed slot. */
    private final Instant pickupAt = LocalDate.now(ZoneOffset.UTC).plusDays(1).atTime(9, 0).toInstant(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        when(ownerApi.currentOwnerId()).thenReturn(ownerId);
        when(ownerApi.settings()).thenReturn(new OwnerSettings(ZoneOffset.UTC));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));
        when(catalogApi.getItems(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return ids.stream().collect(Collectors.toMap(Function.identity(), OrderServiceTests::quickItem));
        });
    }

    @Test
    void moreLinesThanTheLimitIs400BeforeAnyLookup() {
        CheckoutRequest request = checkout(pickupAt,
                IntStream.range(0, 4).mapToObj(i -> new OrderLineRequest(UUID.randomUUID(), 1)).toList());

        assertThatIllegalArgumentException().isThrownBy(() -> service.place(request))
                .withMessage("an order may contain at most 3 different items");
        verifyNoInteractions(catalogApi, workingHoursRepository);
    }

    @Test
    void moreUnitsOfOneItemThanTheLimitIs400AndNamesTheLine() {
        CheckoutRequest request = checkout(pickupAt, List.of(
                new OrderLineRequest(UUID.randomUUID(), 5),
                new OrderLineRequest(UUID.randomUUID(), 6)));

        assertThatIllegalArgumentException().isThrownBy(() -> service.place(request))
                .withMessage("line 2: at most 5 of one item per order");
        verifyNoInteractions(catalogApi, workingHoursRepository);
    }

    @Test
    void exactlyAtTheLimitsIsAccepted() {
        stubOpenSlot();
        CheckoutRequest request = checkout(pickupAt,
                IntStream.range(0, 3).mapToObj(i -> new OrderLineRequest(UUID.randomUUID(), 5)).toList());

        assertThat(service.place(request).getLines()).hasSize(3);
    }

    /** With no lead time the notice check alone would accept a slot that is starting right now. */
    @Test
    void aPickupTimeThatIsNotInTheFutureIs400EvenWithNoLeadTime() {
        for (Instant notFuture : List.of(Instant.now().minus(1, ChronoUnit.HOURS), Instant.now())) {
            CheckoutRequest request = checkout(notFuture, List.of(new OrderLineRequest(UUID.randomUUID(), 1)));

            assertThatIllegalArgumentException().isThrownBy(() -> service.place(request))
                    .withMessage("pickupAt must be in the future");
        }
        verifyNoInteractions(workingHoursRepository, availabilityService);
    }

    @Test
    void reschedulingIntoThePastIs400() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.reschedule(UUID.randomUUID(), Instant.now().minusSeconds(60)))
                .withMessage("pickupAt must be in the future");
        verifyNoInteractions(orderRepository, workingHoursRepository, availabilityService);
    }

    @Test
    void checkoutLocksTheDayBeforeCountingCapacityAndSaving() {
        stubOpenSlot();

        service.place(checkout(pickupAt, List.of(new OrderLineRequest(UUID.randomUUID(), 1))));

        InOrder inOrder = inOrder(workingHoursRepository, availabilityService, orderRepository);
        inOrder.verify(workingHoursRepository).findForUpdate(ownerId, weekday());
        inOrder.verify(availabilityService).computeForDate(day(), null);
        inOrder.verify(orderRepository).save(any(Order.class));
    }

    @Test
    void rescheduleLocksTheNewDayBeforeCountingCapacity() {
        stubOpenSlot();
        Order order = Order.builder()
                .reference("SC-7F3K2")
                .pickupAt(pickupAt.plus(1, ChronoUnit.DAYS))
                .guestContact(new GuestContact("Ada", "ada@example.com", "+1 555 0100"))
                .line(UUID.randomUUID(), "Bread", new BigDecimal("5.00"), 1)
                .build();
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.reschedule(orderId, pickupAt);

        InOrder inOrder = inOrder(workingHoursRepository, availabilityService);
        inOrder.verify(workingHoursRepository).findForUpdate(ownerId, weekday());
        inOrder.verify(availabilityService).computeForDate(eq(day()), any());
        assertThat(order.getPickupAt()).isEqualTo(pickupAt);
    }

    // ---- helpers ----

    private void stubOpenSlot() {
        when(workingHoursRepository.findForUpdate(ownerId, weekday())).thenReturn(Optional.empty());
        when(availabilityService.computeForDate(eq(day()), any())).thenReturn(DayAvailabilityResponse.open(day(),
                List.of(new TimeSlotResponse(LocalTime.of(9, 0), LocalTime.of(10, 0), 1, 1, true))));
    }

    private LocalDate day() {
        return pickupAt.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private DayOfWeek weekday() {
        return day().getDayOfWeek();
    }

    private static CheckoutRequest checkout(Instant pickupAt, List<OrderLineRequest> lines) {
        return new CheckoutRequest(pickupAt, new GuestContactRequest("Ada", "ada@example.com", "+1 555 0100"),
                null, lines);
    }

    private static ItemSnapshot quickItem(UUID id) {
        return new ItemSnapshot(id, "Item " + id, new BigDecimal("2.50"), 0, true, false);
    }
}
