package dev.buhanzaz.rwms.logistics.planning.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.RescheduleCustomerBookingRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.SearchCustomerBookingRescheduleRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCustomerContact;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleOptionsResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentWithdrawalRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentWithdrawalResult;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRescheduleSlot;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerDeliverySlotResponse;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingRescheduleReceipt;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliverySlotService;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.PlanningPublishedRescheduleSagaService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Owns the private dispatcher boundary for route-feasible customer commitment changes. It resolves
 * the real CustomerApp booking and delegates the atomic slot swap to the existing booking
 * lifecycle; the standalone planner never mutates an order or slot directly.
 */
@Service
@RequiredArgsConstructor
public class PlanningCustomerCommitmentService {
  private final RentalOrderRepository orders;
  private final CustomerRentalSessionRepository sessions;
  private final CustomerDeliverySlotRepository slots;
  private final CustomerDeliverySlotService deliverySlots;
  private final CustomerBookingLifecycleService bookingLifecycle;
  private final PlanningPublishedRescheduleSagaService publishedReschedules;

  /**
   * Returns fresh replacement offers under the caller's order fence and exports the current session
   * fence required by the later apply command.
   */
  public PlanningOrderRescheduleOptionsResponse options(UUID orderId, long expectedOrderVersion) {
    Context context = requiredContext(orderId, expectedOrderVersion, null);
    CustomerIdentity identity = identity(context.order(), context.session());
    List<CustomerDeliverySlotResponse> options =
        deliverySlots.searchBooking(
            identity,
            context.session().getBookingId(),
            new SearchCustomerBookingRescheduleRequest(context.session().getVersion()));
    return new PlanningOrderRescheduleOptionsResponse(
        context.order().getId(),
        context.order().getVersion(),
        context.session().getId(),
        context.session().getVersion(),
        context.session().getBookingId(),
        context.session().getWarehouseId(),
        contact(context.order()),
        slot(context.slot()),
        options.stream().map(PlanningCustomerCommitmentService::slot).toList());
  }

  /** Applies one explicitly recorded customer agreement and returns the new authoritative fence. */
  public PlanningOrderRescheduleResponse reschedule(
      UUID orderId, UUID idempotencyKey, PlanningOrderRescheduleRequest request) {
    if (request.publishedPlanWithdrawal() != null) {
      return publishedReschedules.reschedule(orderId, idempotencyKey, request);
    }
    // Resolve only immutable booking identity before replay. Current BOOKED/CONFIRMED state is
    // deliberately not required because an exact earlier receipt remains valid after B/cancel.
    CustomerRentalSession session = requiredSession(orderId);
    CustomerBookingRescheduleReceipt receipt =
        bookingLifecycle.rescheduleForDispatcher(
            identity(session),
            session.getBookingId(),
            orderId,
            idempotencyKey,
            request.expectedOrderVersion(),
            request.decisionCode().name(),
            request.decisionActorSubjectId(),
            request.decisionReason(),
            new RescheduleCustomerBookingRequest(
                request.expectedSessionVersion(), request.slotId(), request.slotVersion()));
    return response(receipt);
  }

  /**
   * Withdraws one already owner-cancelled published assignment through the same durable hold saga
   * used for a customer-agreed reschedule.
   */
  public PlanningPublishedAssignmentWithdrawalResult withdrawCancelled(
      UUID sourcePlanId,
      UUID idempotencyKey,
      PlanningPublishedAssignmentWithdrawalRequest request) {
    return publishedReschedules.withdrawCancelled(sourcePlanId, idempotencyKey, request);
  }

  private Context requiredContext(
      UUID orderId, Long expectedOrderVersion, Long expectedSessionVersion) {
    if (orderId == null) throw invalidIdentity();
    RentalOrder order =
        orders.findWithClientById(orderId).orElseThrow(PlanningCustomerCommitmentService::notFound);
    CustomerRentalSession session =
        sessions
            .findFirstByOrderIdOrderByCreatedAtAscIdAsc(orderId)
            .orElseThrow(PlanningCustomerCommitmentService::notFound);
    if (session.getState() != CustomerSessionState.BOOKED
        || session.getBookingId() == null
        || !orderId.equals(session.getOrderId())) {
      throw notEditable();
    }
    if (expectedOrderVersion != null && order.getVersion() != expectedOrderVersion) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_VERSION_CONFLICT",
          "Заказ изменился после расчёта вариантов переноса");
    }
    if (expectedSessionVersion != null && session.getVersion() != expectedSessionVersion) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_BOOKING_VERSION_CONFLICT",
          "Бронирование изменилось; запросите варианты переноса заново");
    }
    CustomerDeliverySlot slot =
        slots
            .findByOrderIdAndState(orderId, CustomerDeliverySlotState.CONFIRMED)
            .orElseThrow(PlanningCustomerCommitmentService::notFound);
    if (!Objects.equals(session.getDeliverySlotId(), slot.getId())
        || !Objects.equals(session.getBookingId(), slot.getBookingId())
        || !Objects.equals(session.getWarehouseId(), slot.getWarehouseId())) {
      throw notEditable();
    }
    return new Context(order, session, slot);
  }

  private CustomerRentalSession requiredSession(UUID orderId) {
    if (orderId == null) throw invalidIdentity();
    CustomerRentalSession session =
        sessions
            .findFirstByOrderIdOrderByCreatedAtAscIdAsc(orderId)
            .orElseThrow(PlanningCustomerCommitmentService::notFound);
    if (session.getBookingId() == null || !orderId.equals(session.getOrderId())) throw notEditable();
    return session;
  }

  private static PlanningOrderRescheduleResponse response(
      CustomerBookingRescheduleReceipt receipt) {
    CustomerBookingRescheduleReceipt.Slot slot = receipt.confirmedSlot();
    return new PlanningOrderRescheduleResponse(
        receipt.orderId(),
        receipt.orderVersion(),
        receipt.sessionId(),
        receipt.sessionVersion(),
        receipt.bookingId(),
        receipt.warehouseId(),
        new PlanningRescheduleSlot(
            slot.slotId(),
            slot.version(),
            slot.date(),
            slot.kind(),
            slot.windowStart(),
            slot.windowEnd(),
            slot.deliveryPriceRubles(),
            slot.expiresAt()));
  }

  private static PlanningCustomerContact contact(RentalOrder order) {
    String contactName =
        order.getClient().getContactPerson() == null
            ? order.getClient().getDisplayName()
            : order.getClient().getContactPerson();
    String contactPhone =
        order.getContactPhone() == null ? order.getClient().getPhone() : order.getContactPhone();
    return new PlanningCustomerContact(
        order.getClient().getClientType(),
        order.getClient().getDisplayName(),
        contactName,
        contactPhone);
  }

  private static PlanningRescheduleSlot slot(CustomerDeliverySlot value) {
    return new PlanningRescheduleSlot(
        value.getId(),
        value.getVersion(),
        value.getDeliveryDate(),
        value.getKind().name(),
        value.getWindowStart(),
        value.getWindowEnd(),
        value.getDeliveryPriceRubles(),
        value.getExpiresAt());
  }

  private static PlanningRescheduleSlot slot(CustomerDeliverySlotResponse value) {
    return new PlanningRescheduleSlot(
        value.slotId(),
        value.version(),
        value.date(),
        value.kind().name(),
        value.start(),
        value.end(),
        value.deliveryPriceRubles(),
        value.expiresAt());
  }

  private static CustomerIdentity identity(
      RentalOrder order, CustomerRentalSession session) {
    return identity(session);
  }

  private static CustomerIdentity identity(CustomerRentalSession session) {
    return new CustomerIdentity(
        session.getCustomerSubjectId(), "planning-customer-commitment");
  }

  private static OrderProblemException invalidIdentity() {
    return new OrderProblemException(
        HttpStatus.BAD_REQUEST, "PLANNING_ORDER_REQUIRED", "Не указан заказ для переноса");
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_BOOKING_NOT_FOUND", "Бронирование не найдено");
  }

  private static OrderProblemException notEditable() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_BOOKING_NOT_EDITABLE",
        "Бронирование нельзя перенести в его текущем состоянии");
  }

  /** Coherent order, session and confirmed-slot snapshot used by one command. */
  private record Context(
      RentalOrder order, CustomerRentalSession session, CustomerDeliverySlot slot) {}
}
