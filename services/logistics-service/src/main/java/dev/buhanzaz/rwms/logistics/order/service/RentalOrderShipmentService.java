package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Owns the rental-order-to-shipment hand-off, including its idempotent document replay and the
 * order lock/version snapshot taken immediately before the frozen document-service call.
 */
@Service
@RequiredArgsConstructor
class RentalOrderShipmentService {
  private final RentalOrderCommandStore store;
  private final OrderAuthorizer access;
  private final RentalOrderReadService reads;
  private final LogisticsDocumentService documents;

  LogisticsDocumentService.CreateResult createRentalShipment(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateOrderRentalShipmentRequest request) {
    return createRentalShipment(actor, orderId, idempotencyKey, correlationId, request, null);
  }

  /**
   * Returns the warehouse needed for admission without applying the mutable SAVED-state gate.
   * That keeps a retry replayable after later order changes while still rejecting callers that
   * lack write access to the warehouse before any admission intent is reserved.
   */
  UUID rentalShipmentAdmissionWarehouse(
      OrderActor actor, UUID orderId, LocalDate scheduledDate) {
    if (actor == null || orderId == null || scheduledDate == null) {
      throw new IllegalArgumentException("Rental shipment admission identity is invalid");
    }
    RentalOrder order = store.requiredOrder(orderId);
    access.requireVisible(actor, order);
    requireAcceptableDate(order, scheduledDate);
    UUID warehouseId = order.getWarehouseId();
    if (!actor.writeScope()
        || warehouseId == null
        || !access.canEditWarehouse(actor, warehouseId)) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
    return warehouseId;
  }

  /**
   * Replays the document result before locking the order; otherwise locks, authorizes, fences, and
   * snapshots units in the same order as the original facade before delegating to document flow.
   */
  LogisticsDocumentService.CreateResult createRentalShipment(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateOrderRentalShipmentRequest request,
      AdmissionTicket admission) {
    if (actor == null
        || orderId == null
        || idempotencyKey == null
        || correlationId == null
        || request == null) {
      throw new IllegalArgumentException("Rental shipment command is invalid");
    }
    String checksum = rentalShipmentChecksum(orderId, request);
    LogisticsDocumentService.CreateResult replay =
        documents.replayRentalOrderShipment(actor.subjectId(), idempotencyKey, checksum);
    if (replay != null) {
      if (!orderId.equals(replay.response().rentalOrderId())) {
        throw RentalOrderProblems.conflict(
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key уже использован для другого заказа");
      }
      RentalOrder replayedOrder = store.requiredOrder(replay.response().rentalOrderId());
      access.requireVisible(actor, replayedOrder);
      return replay;
    }

    RentalOrder order = store.lockedOrder(orderId);
    access.requireRentalShipmentCreation(actor, order);
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    requireAcceptableDate(order, request.scheduledDate());
    List<LogisticsDependencyGateway.OrderUnitReservation> units = reads.readUnits(order);
    return admission == null
        ? documents.createRentalOrderShipment(
            actor.subjectId(),
            idempotencyKey,
            correlationId,
            order,
            units,
            request,
            checksum)
        : documents.createRentalOrderShipment(
            actor.subjectId(),
            idempotencyKey,
            correlationId,
            order,
            units,
            request,
            checksum,
            admission);
  }

  private static String rentalShipmentChecksum(
      UUID orderId, CreateOrderRentalShipmentRequest request) {
    List<String> values = new ArrayList<>();
    values.add(orderId.toString());
    values.add(Long.toString(request.expectedVersion()));
    values.add(request.driverSnapshot().trim());
    if (request.driverWorkerId() != null) {
      values.add(request.driverWorkerId().toString());
    }
    values.add(request.scheduledDate().toString());
    request.unitIds().stream().sorted().map(UUID::toString).forEach(values::add);
    return OrderCommandChecksum.sha256("CREATE_RENTAL_ORDER_SHIPMENT", values);
  }

  private static void requireAcceptableDate(RentalOrder order, LocalDate scheduledDate) {
    try {
      order.requireAcceptableDeliveryDate(scheduledDate);
    } catch (IllegalStateException exception) {
      throw RentalOrderProblems.conflict(
          "ORDER_DELIVERY_DATE_NOT_ACCEPTABLE",
          "Дата отгрузки не входит в дни, когда клиент может принять заказ");
    }
  }
}
