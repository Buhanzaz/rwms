package dev.buhanzaz.rwms.logistics.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerDeliverySlotResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.SearchCustomerBookingRescheduleRequest;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingRescheduleReceipt;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliverySlotService;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.PlanningPublishedRescheduleSagaService;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentWithdrawalRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentWithdrawalResult;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRescheduleDecisionCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the owner-side read and atomic dispatcher reschedule boundary. */
class PlanningCustomerCommitmentServiceTest {
  @Test
  void optionsUseTheCurrentSessionFenceAndNeverReleaseTheConfirmedSlot() {
    Fixture fixture = fixture();
    CustomerDeliverySlotResponse offered =
        new CustomerDeliverySlotResponse(
            UUID.randomUUID(),
            2,
            LocalDate.of(2026, 9, 6),
            CustomerDeliverySlotKind.FIXED_WINDOW,
            LocalTime.of(12, 0),
            LocalTime.of(15, 0),
            2,
            1,
            2,
            28_500L,
            null,
            true,
            true,
            true,
            null,
            OffsetDateTime.parse("2026-09-01T09:10:00Z"),
            "OFFERED");
    when(fixture.deliverySlots.searchBooking(
            any(CustomerIdentity.class),
            eq(fixture.bookingId),
            eq(new SearchCustomerBookingRescheduleRequest(4L))))
        .thenReturn(List.of(offered));

    var response = fixture.service.options(fixture.orderId, 7);

    assertThat(response.sessionVersion()).isEqualTo(4);
    assertThat(response.currentSlot().slotId()).isEqualTo(fixture.currentSlotId);
    assertThat(response.options())
        .extracting(value -> value.slotId())
        .containsExactly(offered.slotId());
    verify(fixture.bookingLifecycle, never())
        .rescheduleForDispatcher(any(), any(), any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void staleOrderFenceRejectsTheReadBeforeSearchingForOffers() {
    Fixture fixture = fixture();

    assertThatThrownBy(() -> fixture.service.options(fixture.orderId, 6))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception -> assertThat(exception.code()).isEqualTo("ORDER_VERSION_CONFLICT"));
    verify(fixture.deliverySlots, never()).searchBooking(any(), any(), any());
  }

  @Test
  void applyDelegatesBothFencesAndDecisionAuditThenReturnsNewFacts() {
    Fixture fixture = fixture();
    UUID commandKey = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    PlanningOrderRescheduleRequest request =
        new PlanningOrderRescheduleRequest(
            7L,
            4L,
            fixture.replacementSlotId,
            2L,
            PlanningRescheduleDecisionCode.CUSTOMER_AGREED_ALTERNATIVE,
            actor,
            "Клиент согласовал 6 сентября");
    CustomerBookingRescheduleReceipt receipt =
        receipt(fixture, LocalDate.of(2026, 9, 6), 8, 5, 3);
    when(fixture.bookingLifecycle.rescheduleForDispatcher(
            any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(receipt);

    var response = fixture.service.reschedule(fixture.orderId, commandKey, request);

    verify(fixture.bookingLifecycle)
        .rescheduleForDispatcher(
            any(CustomerIdentity.class),
            eq(fixture.bookingId),
            eq(fixture.orderId),
            eq(commandKey),
            eq(7L),
            eq("CUSTOMER_AGREED_ALTERNATIVE"),
            eq(actor),
            eq("Клиент согласовал 6 сентября"),
            any());
    assertThat(response.orderVersion()).isEqualTo(8);
    assertThat(response.sessionVersion()).isEqualTo(5);
    assertThat(response.confirmedSlot().slotId()).isEqualTo(fixture.replacementSlotId);
    verify(fixture.orders, never()).findWithClientById(fixture.orderId);
    verify(fixture.slots, never())
        .findByOrderIdAndState(fixture.orderId, CustomerDeliverySlotState.CONFIRMED);
  }

  @Test
  void cancelledPublishedAssignmentDelegatesToTheDurableSaga() {
    Fixture fixture = fixture();
    UUID sourcePlanId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID removedExternalTaskId = UUID.randomUUID();
    PlanningPublishedAssignmentWithdrawalRequest request =
        mock(PlanningPublishedAssignmentWithdrawalRequest.class);
    PlanningPublishedAssignmentWithdrawalResult expected =
        new PlanningPublishedAssignmentWithdrawalResult(
            sourcePlanId, 3, removedExternalTaskId, 8, "COMPLETE");
    when(fixture.publishedReschedules.withdrawCancelled(sourcePlanId, idempotencyKey, request))
        .thenReturn(expected);

    var result = fixture.service.withdrawCancelled(sourcePlanId, idempotencyKey, request);

    assertThat(result).isEqualTo(expected);
    verify(fixture.publishedReschedules)
        .withdrawCancelled(sourcePlanId, idempotencyKey, request);
  }

  @Test
  void replayAAfterCancellationReturnsFrozenAWithoutCurrentOrderOrSlotRead() {
    Fixture fixture = fixture();
    when(fixture.session.getState()).thenReturn(CustomerSessionState.CANCELLED);
    CustomerBookingRescheduleReceipt frozenA =
        receipt(fixture, LocalDate.of(2026, 9, 5), 8, 5, 3);
    when(fixture.bookingLifecycle.rescheduleForDispatcher(
            any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(frozenA);

    var response =
        fixture.service.reschedule(
            fixture.orderId, UUID.randomUUID(), rescheduleRequest(fixture, 7, 4));

    assertThat(response.orderVersion()).isEqualTo(8);
    assertThat(response.sessionVersion()).isEqualTo(5);
    assertThat(response.confirmedSlot().slotId()).isEqualTo(fixture.replacementSlotId);
    verify(fixture.orders, never()).findWithClientById(any());
    verify(fixture.slots, never()).findByOrderIdAndState(any(), any());
  }

  @Test
  void replayAAfterBReturnsFrozenAInsteadOfTheCurrentBProjection() {
    Fixture fixture = fixture();
    when(fixture.session.getVersion()).thenReturn(6L);
    when(fixture.session.getDeliverySlotId()).thenReturn(UUID.randomUUID());
    CustomerBookingRescheduleReceipt frozenA =
        receipt(fixture, LocalDate.of(2026, 9, 5), 8, 5, 3);
    when(fixture.bookingLifecycle.rescheduleForDispatcher(
            any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(frozenA);

    var response =
        fixture.service.reschedule(
            fixture.orderId, UUID.randomUUID(), rescheduleRequest(fixture, 7, 4));

    assertThat(response.sessionVersion()).isEqualTo(5);
    assertThat(response.confirmedSlot().date()).isEqualTo(LocalDate.of(2026, 9, 5));
    verify(fixture.orders, never()).findWithClientById(any());
    verify(fixture.slots, never()).findByOrderIdAndState(any(), any());
  }

  private static PlanningOrderRescheduleRequest rescheduleRequest(
      Fixture fixture, long orderVersion, long sessionVersion) {
    return new PlanningOrderRescheduleRequest(
        orderVersion,
        sessionVersion,
        fixture.replacementSlotId,
        2L,
        PlanningRescheduleDecisionCode.CUSTOMER_AGREED_ALTERNATIVE,
        UUID.randomUUID(),
        "Клиент согласовал 5 сентября");
  }

  private static Fixture fixture() {
    UUID orderId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID customerSubjectId = UUID.randomUUID();
    UUID currentSlotId = UUID.randomUUID();
    UUID replacementSlotId = UUID.randomUUID();
    RentalOrder order = order(orderId, 7);
    CustomerRentalSession session =
        session(orderId, bookingId, warehouseId, customerSubjectId, currentSlotId, 4);
    CustomerDeliverySlot slot =
        slot(orderId, bookingId, warehouseId, currentSlotId, 1, LocalDate.of(2026, 9, 3));
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    CustomerRentalSessionRepository sessions = mock(CustomerRentalSessionRepository.class);
    CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
    CustomerDeliverySlotService deliverySlots = mock(CustomerDeliverySlotService.class);
    CustomerBookingLifecycleService lifecycle = mock(CustomerBookingLifecycleService.class);
    PlanningPublishedRescheduleSagaService publishedReschedules =
        mock(PlanningPublishedRescheduleSagaService.class);
    when(orders.findWithClientById(orderId)).thenReturn(Optional.of(order));
    when(sessions.findFirstByOrderIdOrderByCreatedAtAscIdAsc(orderId))
        .thenReturn(Optional.of(session));
    when(slots.findByOrderIdAndState(orderId, CustomerDeliverySlotState.CONFIRMED))
        .thenReturn(Optional.of(slot));
    return new Fixture(
        orderId,
        bookingId,
        warehouseId,
        customerSubjectId,
        currentSlotId,
        replacementSlotId,
        order,
        session,
        slot,
        orders,
        sessions,
        slots,
            deliverySlots,
            lifecycle,
            publishedReschedules,
            new PlanningCustomerCommitmentService(
            orders, sessions, slots, deliverySlots, lifecycle, publishedReschedules));
  }

  private static RentalOrder order(UUID orderId, long version) {
    RentalOrder order = mock(RentalOrder.class);
    OrderClient client = mock(OrderClient.class);
    when(order.getId()).thenReturn(orderId);
    when(order.getVersion()).thenReturn(version);
    when(order.getClient()).thenReturn(client);
    when(client.getClientType()).thenReturn(ClientType.SOLE_PROPRIETOR);
    when(client.getDisplayName()).thenReturn("ИП Петров");
    when(client.getContactPerson()).thenReturn("Пётр Петров");
    when(client.getPhone()).thenReturn("+79990000001");
    return order;
  }

  private static CustomerRentalSession session(
      UUID orderId,
      UUID bookingId,
      UUID warehouseId,
      UUID customerSubjectId,
      UUID slotId,
      long version) {
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    when(session.getState()).thenReturn(CustomerSessionState.BOOKED);
    when(session.getId()).thenReturn(UUID.randomUUID());
    when(session.getVersion()).thenReturn(version);
    when(session.getOrderId()).thenReturn(orderId);
    when(session.getBookingId()).thenReturn(bookingId);
    when(session.getWarehouseId()).thenReturn(warehouseId);
    when(session.getCustomerSubjectId()).thenReturn(customerSubjectId);
    when(session.getDeliverySlotId()).thenReturn(slotId);
    return session;
  }

  private static CustomerBookingRescheduleReceipt receipt(
      Fixture fixture,
      LocalDate date,
      long orderVersion,
      long sessionVersion,
      long slotVersion) {
    return new CustomerBookingRescheduleReceipt(
        fixture.orderId,
        orderVersion,
        fixture.session.getId(),
        sessionVersion,
        fixture.bookingId,
        fixture.warehouseId,
        UUID.randomUUID(),
        "Великий Новгород, тестовый адрес",
        List.of(),
        new CustomerBookingRescheduleReceipt.Slot(
            fixture.replacementSlotId,
            slotVersion,
            date,
            "FIXED_WINDOW",
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            28_500L,
            OffsetDateTime.parse("2026-09-01T09:10:00Z")));
  }

  private static CustomerDeliverySlot slot(
      UUID orderId, UUID bookingId, UUID warehouseId, UUID slotId, long version, LocalDate date) {
    CustomerDeliverySlot slot = mock(CustomerDeliverySlot.class);
    when(slot.getId()).thenReturn(slotId);
    when(slot.getVersion()).thenReturn(version);
    when(slot.getOrderId()).thenReturn(orderId);
    when(slot.getBookingId()).thenReturn(bookingId);
    when(slot.getWarehouseId()).thenReturn(warehouseId);
    when(slot.getState()).thenReturn(CustomerDeliverySlotState.CONFIRMED);
    when(slot.getKind()).thenReturn(CustomerDeliverySlotKind.FIXED_WINDOW);
    when(slot.getDeliveryDate()).thenReturn(date);
    when(slot.getWindowStart()).thenReturn(LocalTime.of(9, 0));
    when(slot.getWindowEnd()).thenReturn(LocalTime.of(12, 0));
    when(slot.getExpiresAt()).thenReturn(OffsetDateTime.parse("2026-09-01T09:10:00Z"));
    return slot;
  }

  private record Fixture(
      UUID orderId,
      UUID bookingId,
      UUID warehouseId,
      UUID customerSubjectId,
      UUID currentSlotId,
      UUID replacementSlotId,
      RentalOrder order,
      CustomerRentalSession session,
      CustomerDeliverySlot slot,
      RentalOrderRepository orders,
      CustomerRentalSessionRepository sessions,
      CustomerDeliverySlotRepository slots,
      CustomerDeliverySlotService deliverySlots,
      CustomerBookingLifecycleService bookingLifecycle,
      PlanningPublishedRescheduleSagaService publishedReschedules,
      PlanningCustomerCommitmentService service) {}
}
