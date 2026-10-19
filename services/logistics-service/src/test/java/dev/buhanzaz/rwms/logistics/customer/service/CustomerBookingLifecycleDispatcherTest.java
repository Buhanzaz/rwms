package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.RescheduleCustomerBookingRequest;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.RescheduleAudit;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.RescheduleDecision;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies dispatcher replay and structured audit through the existing atomic lifecycle store. */
class CustomerBookingLifecycleDispatcherTest {
  @Test
  void exactReplayDoesNotRecalculateOrSwapSlotsAgain() {
    Fixture fixture = fixture();
    when(fixture.store.rescheduleReplay(
            eq(fixture.identity), eq(fixture.bookingId), eq(fixture.commandKey), any()))
        .thenReturn(Optional.of(fixture.session));

    assertThat(fixture.call()).isSameAs(fixture.session);

    verify(fixture.deliverySlots, never()).prepareReschedule(any(), any(), any());
    verify(fixture.store, never()).reschedule(any(), any(), any(), any(), any(), any());
  }

  @Test
  void freshCommandAddsTheOrderFenceAndDecisionAuditToTheAtomicStoreCall() {
    Fixture fixture = fixture();
    RescheduleDecision base =
        new RescheduleDecision(
            4,
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 9, 3),
            LocalDate.of(2026, 9, 5),
            fixture.slotId,
            2,
            "a".repeat(64),
            1);
    when(fixture.store.rescheduleReplay(
            eq(fixture.identity), eq(fixture.bookingId), eq(fixture.commandKey), any()))
        .thenReturn(Optional.empty());
    when(fixture.deliverySlots.prepareReschedule(
            fixture.identity, fixture.bookingId, fixture.request()))
        .thenReturn(base);
    when(fixture.store.reschedule(any(), any(), any(), any(), any(), any()))
        .thenReturn(fixture.session);

    assertThat(fixture.call()).isSameAs(fixture.session);

    ArgumentCaptor<RescheduleDecision> decision = ArgumentCaptor.forClass(RescheduleDecision.class);
    ArgumentCaptor<RescheduleAudit> audit = ArgumentCaptor.forClass(RescheduleAudit.class);
    verify(fixture.store)
        .reschedule(
            eq(fixture.identity),
            eq(fixture.bookingId),
            eq(fixture.commandKey),
            any(),
            decision.capture(),
            audit.capture());
    assertThat(decision.getValue().expectedOrderVersion()).isEqualTo(7);
    assertThat(audit.getValue().decisionCode()).isEqualTo("CUSTOMER_AGREED_ALTERNATIVE");
    assertThat(audit.getValue().actorSubjectId()).isEqualTo(fixture.actor);
    assertThat(audit.getValue().reason()).isEqualTo("Клиент согласовал 5 сентября");
  }

  @Test
  void publishedRoutingPreparationIsConsumedByASeparateAtomicCommitBoundary() {
    Fixture fixture = fixture();
    RescheduleDecision base =
        new RescheduleDecision(
            4,
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 9, 3),
            LocalDate.of(2026, 9, 5),
            fixture.slotId,
            2,
            "a".repeat(64),
            1);
    when(fixture.store.rescheduleReplay(
            eq(fixture.identity), eq(fixture.bookingId), eq(fixture.commandKey), any()))
        .thenReturn(Optional.empty());
    when(fixture.deliverySlots.prepareReschedule(
            fixture.identity, fixture.bookingId, fixture.request()))
        .thenReturn(base);

    var preparation =
        fixture.service.preparePublishedForDispatcher(
            fixture.identity,
            fixture.bookingId,
            fixture.orderId,
            fixture.commandKey,
            7,
            "CUSTOMER_AGREED_ALTERNATIVE",
            fixture.actor,
            "Клиент согласовал 5 сентября",
            fixture.request());

    verify(fixture.store, never())
        .reschedulePublishedPreStart(any(), any(), any(), any(), any(), any());
    when(fixture.store.reschedulePublishedPreStart(any(), any(), any(), any(), any(), any()))
        .thenReturn(fixture.session);
    assertThat(fixture.service.commitPreparedPublishedForDispatcher(preparation))
        .isSameAs(fixture.session);

    ArgumentCaptor<RescheduleDecision> decision = ArgumentCaptor.forClass(RescheduleDecision.class);
    verify(fixture.store)
        .reschedulePublishedPreStart(
            eq(fixture.identity),
            eq(fixture.bookingId),
            eq(fixture.commandKey),
            any(),
            decision.capture(),
            any());
    assertThat(decision.getValue().expectedOrderVersion()).isEqualTo(7);
  }

  private static Fixture fixture() {
    CustomerBookingLifecycleStore store = mock(CustomerBookingLifecycleStore.class);
    CustomerDeliverySlotService deliverySlots = mock(CustomerDeliverySlotService.class);
    CustomerBookingService bookings = mock(CustomerBookingService.class);
    CustomerAuthorizer access = mock(CustomerAuthorizer.class);
    RentalOrderService rentalOrders = mock(RentalOrderService.class);
    return new Fixture(
        store,
        deliverySlots,
        bookings,
        new CustomerBookingLifecycleService(store, deliverySlots, bookings, access, rentalOrders),
        new CustomerIdentity(UUID.randomUUID(), "dispatcher-test"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        receipt());
  }

  private static CustomerBookingRescheduleReceipt receipt() {
    return new CustomerBookingRescheduleReceipt(
        UUID.randomUUID(),
        8,
        UUID.randomUUID(),
        5,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Великий Новгород, тестовый адрес",
        List.of(),
        new CustomerBookingRescheduleReceipt.Slot(
            UUID.randomUUID(),
            3,
            LocalDate.of(2026, 9, 5),
            "FIXED_WINDOW",
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            28_500L,
            OffsetDateTime.parse("2026-09-01T09:10:00Z")));
  }

  private record Fixture(
      CustomerBookingLifecycleStore store,
      CustomerDeliverySlotService deliverySlots,
      CustomerBookingService bookings,
      CustomerBookingLifecycleService service,
      CustomerIdentity identity,
      UUID bookingId,
      UUID orderId,
      UUID commandKey,
      UUID slotId,
      UUID actor,
      CustomerBookingRescheduleReceipt session) {
    private RescheduleCustomerBookingRequest request() {
      return new RescheduleCustomerBookingRequest(4L, slotId, 2L);
    }

    private CustomerBookingRescheduleReceipt call() {
      return service.rescheduleForDispatcher(
          identity,
          bookingId,
          orderId,
          commandKey,
          7,
          "CUSTOMER_AGREED_ALTERNATIVE",
          actor,
          "Клиент согласовал 5 сентября",
          request());
    }
  }
}
