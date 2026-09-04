package dev.buhanzaz.rwms.logistics.customer.service;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CancelCustomerBookingRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.RescheduleCustomerBookingRequest;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.CancellationClaim;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.CancellationFailure;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.CancellationStart;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.RescheduleDecision;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers cancellation recovery and lost-response replay at the CustomerApp orchestration edge. */
class CustomerBookingLifecycleServiceTest {
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000701");
  private static final UUID BOOKING = UUID.fromString("00000000-0000-0000-0000-000000000702");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000703");
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000704");
  private static final UUID MUTATION = UUID.fromString("00000000-0000-0000-0000-000000000705");
  private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000706");
  private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-000000000707");

  @Test
  void cancellationCompletesOnlyAfterTheDurableOrderCancellation() {
    Fixture fixture = fixture();
    CustomerBookingMutation mutation = mutation(CustomerBookingMutationState.PENDING);
    CancellationClaim claim = claim();
    when(fixture.store().prepareCancellation(any(), eq(BOOKING), eq(KEY), any(), eq(4L)))
        .thenReturn(new CancellationStart(mutation, fixture.session(), false));
    when(fixture.store().claim(MUTATION)).thenReturn(Optional.of(claim));
    OrderDetailResponse cancelledOrder = cancelledOrder();
    when(fixture.rentalOrders().cancel(any(), eq(ORDER), eq(8L), eq(KEY)))
        .thenReturn(new RentalOrderService.MutationResult(cancelledOrder, false));
    when(fixture.store().completeCancellation(MUTATION, LEASE))
        .thenReturn(fixture.session());
    when(fixture.bookings().response(any(), eq(fixture.session()), eq("CANCELLED"), eq(null)))
        .thenReturn(fixture.response());

    CustomerBookingResponse response =
        fixture.service().cancel(identity(), BOOKING, KEY, new CancelCustomerBookingRequest(4L));

    assertThat(response).isSameAs(fixture.response());
    verify(fixture.rentalOrders()).cancel(any(), eq(ORDER), eq(8L), eq(KEY));
    verify(fixture.store()).completeCancellation(MUTATION, LEASE);
  }

  @Test
  void completedCancellationReplayDoesNotRepeatOrderOrAssetEffects() {
    Fixture fixture = fixture();
    CustomerBookingMutation mutation = mutation(CustomerBookingMutationState.COMPLETED);
    when(fixture.store().prepareCancellation(any(), eq(BOOKING), eq(KEY), any(), eq(5L)))
        .thenReturn(new CancellationStart(mutation, fixture.session(), true));
    when(fixture.bookings().response(any(), eq(fixture.session()), eq("CANCELLED"), eq(null)))
        .thenReturn(fixture.response());

    assertThat(
            fixture
                .service()
                .cancel(identity(), BOOKING, KEY, new CancelCustomerBookingRequest(5L)))
        .isSameAs(fixture.response());
    verify(fixture.store(), never()).claim(any());
    verify(fixture.rentalOrders(), never()).cancel(any(), any(), any(Long.class), any());
  }

  @Test
  void failedRemoteReleaseStaysPendingAndNeverReleasesTheConfirmedSlotLocally() {
    Fixture fixture = fixture();
    CustomerBookingMutation mutation = mutation(CustomerBookingMutationState.PENDING);
    when(fixture.store().prepareCancellation(any(), eq(BOOKING), eq(KEY), any(), eq(4L)))
        .thenReturn(new CancellationStart(mutation, fixture.session(), false));
    when(fixture.store().claim(MUTATION))
        .thenReturn(Optional.of(claim()));
    when(fixture.rentalOrders().cancel(any(), eq(ORDER), eq(8L), eq(KEY)))
        .thenThrow(new IllegalStateException("asset service timeout"));
    when(
            fixture
                .store()
                .fail(
                    MUTATION,
                    LEASE,
                    "CUSTOMER_BOOKING_CANCELLATION_FAILED",
                    false))
        .thenReturn(new CancellationFailure(1, false, "CUSTOMER_BOOKING_CANCELLATION_FAILED"));
    when(fixture.store().requiredOwnedSession(SUBJECT, BOOKING))
        .thenReturn(fixture.session());
    when(fixture
            .bookings()
            .response(
                any(),
                eq(fixture.session()),
                eq("CANCELLATION_PENDING"),
                eq("CUSTOMER_BOOKING_CANCELLATION_FAILED")))
        .thenReturn(fixture.response());

    assertThat(
            fixture
                .service()
                .cancel(identity(), BOOKING, KEY, new CancelCustomerBookingRequest(4L)))
        .isSameAs(fixture.response());
    verify(fixture.store(), never()).completeCancellation(any(), any());
  }

  @Test
  void completedRescheduleReplayReturnsFrozenAWithoutReadingCurrentBookingState() {
    Fixture fixture = fixture();
    UUID slotId = UUID.randomUUID();
    CustomerBookingRescheduleReceipt frozenA = receipt(slotId);
    when(fixture.store().rescheduleReplay(any(), eq(BOOKING), eq(KEY), any()))
        .thenReturn(Optional.of(frozenA));

    assertThat(
            fixture
                .service()
                .reschedule(
                    identity(), BOOKING, KEY, new RescheduleCustomerBookingRequest(7L, slotId, 3L)))
        .isEqualTo(frozenA.customerResponse());
    verify(fixture.deliverySlots(), never()).prepareReschedule(any(), any(), any());
    verify(fixture.store(), never())
        .reschedule(any(), any(), any(), any(), any(RescheduleDecision.class));
    verify(fixture.store(), never()).requiredOwnedSession(any(), any());
    verify(fixture.bookings(), never()).response(any(), any(), any(), any());
  }

  @Test
  void freshRescheduleFreezesThePreEffectCabinsAndMapsOnlyTheStoredReceipt() {
    Fixture fixture = fixture();
    UUID slotId = UUID.randomUUID();
    CustomerBookingRescheduleReceipt frozenA = receipt(slotId);
    RescheduleDecision decision = mock(RescheduleDecision.class);
    when(fixture.store().rescheduleReplay(any(), eq(BOOKING), eq(KEY), any()))
        .thenReturn(Optional.empty());
    when(fixture.deliverySlots().prepareReschedule(any(), eq(BOOKING), any()))
        .thenReturn(decision);
    when(fixture.bookings().response(any(), eq(fixture.session()), eq("COMPLETED"), eq(null)))
        .thenReturn(fixture.response());
    when(fixture
            .store()
            .rescheduleWithCustomerProjection(
                any(), eq(BOOKING), eq(KEY), any(), eq(decision), eq(java.util.List.of())))
        .thenReturn(frozenA);

    CustomerBookingResponse response =
        fixture
            .service()
            .reschedule(
                identity(), BOOKING, KEY, new RescheduleCustomerBookingRequest(7L, slotId, 3L));

    assertThat(response).isEqualTo(frozenA.customerResponse());
    verify(fixture.store(), times(1))
        .requiredOwnedSession(SUBJECT, BOOKING);
    verify(fixture.bookings(), times(1))
        .response(any(), eq(fixture.session()), eq("COMPLETED"), eq(null));
  }

  private static Fixture fixture() {
    CustomerBookingLifecycleStore store = mock(CustomerBookingLifecycleStore.class);
    CustomerDeliverySlotService slots = mock(CustomerDeliverySlotService.class);
    CustomerBookingService bookings = mock(CustomerBookingService.class);
    CustomerAuthorizer access = mock(CustomerAuthorizer.class);
    RentalOrderService orders = mock(RentalOrderService.class);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    CustomerBookingResponse response = mock(CustomerBookingResponse.class);
    when(session.getWarehouseId()).thenReturn(WAREHOUSE);
    when(session.getState())
        .thenReturn(dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState.BOOKED);
    when(response.cabins()).thenReturn(java.util.List.of());
    when(store.requiredOwnedSession(SUBJECT, BOOKING))
        .thenReturn(session);
    when(access.orderActor(any(), eq(WAREHOUSE)))
        .thenReturn(
            new OrderActor(
                SUBJECT,
                "CUSTOMER",
                "Клиент",
                java.util.Set.of(WAREHOUSE),
                java.util.Set.of(WAREHOUSE),
                false,
                false,
                false,
                false));
    return new Fixture(
        new CustomerBookingLifecycleService(store, slots, bookings, access, orders),
        store,
        slots,
        bookings,
        orders,
        session,
        response);
  }

  private static CustomerBookingMutation mutation(CustomerBookingMutationState state) {
    CustomerBookingMutation mutation = mock(CustomerBookingMutation.class);
    when(mutation.getId()).thenReturn(MUTATION);
    when(mutation.getState()).thenReturn(state);
    return mutation;
  }

  private static CancellationClaim claim() {
    return new CancellationClaim(MUTATION, SUBJECT, BOOKING, ORDER, 8, KEY, LEASE);
  }

  private static OrderDetailResponse cancelledOrder() {
    OrderDetailResponse response = mock(OrderDetailResponse.class);
    when(response.status()).thenReturn(RentalOrderStatus.CANCELLED);
    return response;
  }

  private static CustomerIdentity identity() {
    return new CustomerIdentity(SUBJECT, "customer");
  }

  private static CustomerBookingRescheduleReceipt receipt(UUID slotId) {
    return new CustomerBookingRescheduleReceipt(
        ORDER,
        9,
        UUID.randomUUID(),
        8,
        BOOKING,
        WAREHOUSE,
        UUID.randomUUID(),
        "Великий Новгород, тестовый адрес",
        java.util.List.of(),
        new CustomerBookingRescheduleReceipt.Slot(
            slotId,
            3,
            LocalDate.of(2026, 9, 5),
            "FIXED_WINDOW",
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            28_500L,
            OffsetDateTime.parse("2026-09-01T09:10:00Z")));
  }

  /** Immutable mock graph for one orchestration scenario. */
  private record Fixture(
      CustomerBookingLifecycleService service,
      CustomerBookingLifecycleStore store,
      CustomerDeliverySlotService deliverySlots,
      CustomerBookingService bookings,
      RentalOrderService rentalOrders,
      CustomerRentalSession session,
      CustomerBookingResponse response) {}
}
