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
  private final RentalOrderInventorySourcePolicy inventorySources;

  LogisticsDocumentService.CreateResult createRentalShipment(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateOrderRentalShipmentRequest request) {
    return createRentalShipment(actor, orderId, idempotencyKey, correlationId, request, null);
  }

  /**
   * Returns the warehouse needed for admission without applying the mutable SAVED-state gate. That
   * keeps a retry replayable after later order changes while still rejecting callers that lack
   * write access to the warehouse before any admission intent is reserved.
   */
  UUID rentalShipmentAdmissionWarehouse(
      OrderActor actor,
      UUID orderId,
      LocalDate scheduledDate,
      UUID inventorySourceWarehouseId) {
    if (actor == null || orderId == null || scheduledDate == null) {
      throw new IllegalArgumentException("Rental shipment admission identity is invalid");
    }
    RentalOrder order = store.requiredOrder(orderId);
    access.requireVisible(actor, order);
    return inventorySources.requireWritableShipmentSource(
        actor, order, inventorySourceWarehouseId, scheduledDate);
  }

  UUID rentalShipmentAdmissionWarehouse(OrderActor actor, UUID orderId, LocalDate scheduledDate) {
    return rentalShipmentAdmissionWarehouse(actor, orderId, scheduledDate, null);
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
    LogisticsDocumentService.CreateResult replay =
        replayRentalShipment(actor, orderId, idempotencyKey, request);
    if (replay != null) {
      return replay;
    }

    String checksum = rentalShipmentChecksum(orderId, request);
    RentalOrder order = store.lockedOrder(actor, orderId);
    access.requireRentalShipmentCreation(actor, order);
    try {
      order.requireFulfillmentDetails();
      order.requireDesiredDeliveryWindows();
    } catch (IllegalStateException exception) {
      throw RentalOrderProblems.conflict(
          "ORDER_DELIVERY_PREFERENCES_REQUIRED",
          "Перед назначением отгрузки получите от клиента адрес и желаемую дату");
    }
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    inventorySources.requireWritableShipmentSource(
        actor, order, request.inventorySourceWarehouseId(), request.scheduledDate());
    List<LogisticsDependencyGateway.OrderUnitReservation> units = reads.readUnitsForShipment(order);
    return admission == null
        ? documents.createRentalOrderShipment(
            actor.subjectId(), idempotencyKey, correlationId, order, units, request, checksum)
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

  /**
   * Reads the exact idempotent shipment result before mutable order and planning checks run.
   * Visibility is re-authorized against the order, and a key reused across orders fails closed.
   */
  LogisticsDocumentService.CreateResult replayRentalShipment(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      CreateOrderRentalShipmentRequest request) {
    if (actor == null || orderId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Rental shipment replay identity is invalid");
    }
    String checksum = rentalShipmentChecksum(orderId, request);
    LogisticsDocumentService.CreateResult replay =
        documents.replayRentalOrderShipment(actor.subjectId(), idempotencyKey, checksum);
    if (replay == null) return null;
    requireReceiptVisible(actor, orderId, replay.response().rentalOrderId());
    return replay;
  }

  /** Reads only exact receipt ownership; it neither replays nor applies mutable admission gates. */
  boolean hasRentalShipmentReceipt(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      CreateOrderRentalShipmentRequest request) {
    if (actor == null || orderId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Rental shipment receipt identity is invalid");
    }
    UUID receiptOrderId =
        documents.rentalOrderShipmentReceiptOrderId(
            actor.subjectId(), idempotencyKey, rentalShipmentChecksum(orderId, request));
    if (receiptOrderId == null) return false;
    requireReceiptVisible(actor, orderId, receiptOrderId);
    return true;
  }

  private void requireReceiptVisible(OrderActor actor, UUID orderId, UUID receiptOrderId) {
    if (!orderId.equals(receiptOrderId)) {
      throw RentalOrderProblems.conflict(
          "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другого заказа");
    }
    RentalOrder replayedOrder = store.requiredOrder(receiptOrderId);
    access.requireVisible(actor, replayedOrder);
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
    values.add(Boolean.toString(request.warehouseDriverPool()));
    values.add(
        request.inventorySourceWarehouseId() == null
            ? "SERVICE_WAREHOUSE_SOURCE"
            : request.inventorySourceWarehouseId().toString());
    values.add(request.scheduledDate().toString());
    request.unitIds().stream().sorted().map(UUID::toString).forEach(values::add);
    return OrderCommandChecksum.sha256("CREATE_RENTAL_ORDER_SHIPMENT", values);
  }
}
