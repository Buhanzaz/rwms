package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderAuditService;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns completion of an order-backed shipment: independently schedulable rental returns, order
 * fulfillment and equipment-reservation release, then terminal order closure after all returns.
 */
@Service
@RequiredArgsConstructor
class LogisticsRentalOrderCompletionCoordinator {
  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsGuardRepository guardRepository;
  private final RentalOrderRepository rentalOrders;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final RentalOrderEquipmentRequirementRepository equipmentRequirements;
  private final LogisticsDependencyGateway dependencies;
  private final OrderAuditService orderAudit;
  private final LogisticsEventStore eventStore;
  private final LogisticsDocumentAttemptWriter attemptWriter;

  void completeRentalOrderShipment(LogisticsDocument shipment) {
    if (shipment == null
        || shipment.getDocumentType() != LogisticsDocumentType.SHIPMENT
        || shipment.getState() != LogisticsDocumentState.SHIPPED
        || shipment.getRentalOrderId() == null) {
      return;
    }
    RentalOrder order =
        rentalOrders
            .findForUpdate(shipment.getRentalOrderId())
            .orElseThrow(() -> new LogisticsConflictException("Rental order was not found"));
    List<LogisticsDocumentLine> shipmentLines = linesRequired(shipment.getId());
    Set<UUID> returnedAssetIds = new HashSet<>();
    for (LogisticsDocument existingReturn :
        documentRepository.findAllByDocumentTypeAndRentalShipmentIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.RETURN, shipment.getId())) {
      lineRepository
          .findAllByDocument_IdOrderByLineNumber(existingReturn.getId())
          .forEach(line -> returnedAssetIds.add(line.getAssetId()));
    }

    for (LogisticsDocumentLine shipmentLine : shipmentLines) {
      if (!returnedAssetIds.add(shipmentLine.getAssetId())) {
        continue;
      }
      LogisticsGuard guard =
          guardRepository
              .findByLine_Id(shipmentLine.getId())
              .orElseThrow(
                  () ->
                      new LogisticsConflictException("Shipped order line has no asset guard"));
      if (guard.getObservedAssetVersion() == null) {
        throw new LogisticsConflictException("Shipped order line has no observed asset version");
      }
      LogisticsDocument returnDocument =
          documentRepository.saveAndFlush(
              LogisticsDocument.createRentalOrderReturn(
                  order.getWarehouseId(),
                  order.getClient().getId(),
                  order.getId(),
                  shipment.getId(),
                  order.getClient().getDisplayName(),
                  shipment.getRequestedBySubjectId(),
                  shipment.getCorrelationId()));
      LogisticsDocumentLine returnLine =
          lineRepository.saveAndFlush(
              LogisticsDocumentLine.create(
                  returnDocument,
                  1,
                  shipmentLine.getAssetId(),
                  guard.getObservedAssetVersion(),
                  order.getClient().getDisplayName(),
                  order.getId()));
      OffsetDateTime createdAt = now();
      attemptWriter.createLineAttempt(
          returnDocument,
          returnLine,
          LogisticsTargetService.MEDIA,
          LogisticsDocumentEffectOperations.RETURN_MEDIA_OWNER_PROOF_REGISTER,
          attemptWriter.ownerProofDigest(
              LogisticsDocumentEffectOperations.RETURN_MEDIA_OWNER_PROOF_REGISTER,
              returnDocument,
              returnLine,
              returnDocument.getWarehouseId(),
              0,
              0,
              true),
          createdAt);
      eventStore.initialize(
          returnDocument, 1, shipment.getCorrelationId(), shipment.getRequestedBySubjectId());
    }

    if (order.getStatus() == RentalOrderStatus.SAVED && allRentalOrderUnitsShipped(order)) {
      boolean fulfilled = order.fulfill();
      if (fulfilled) {
        rentalOrders.saveAndFlush(order);
        releaseFulfilledOrderEquipmentReservations(order, shipment);
        orderAudit.appendForActor(
            order.getId(),
            OrderAuditEventType.ORDER_FULFILLED,
            shipment.getRequestedBySubjectId(),
            order.getCreatedByRole(),
            "ORDER",
            order.getId().toString(),
            Map.of("status", RentalOrderStatus.SAVED.name()),
            Map.of("status", RentalOrderStatus.FULFILLED.name()));
      }
    }
  }

  void closeRentalOrderReturn(LogisticsDocument returnDocument) {
    if (returnDocument == null
        || returnDocument.getDocumentType() != LogisticsDocumentType.RETURN
        || returnDocument.getRentalOrderId() == null
        || returnDocument.getRentalShipmentId() == null
        || (returnDocument.getState() != LogisticsDocumentState.ACCEPTED
            && returnDocument.getState() != LogisticsDocumentState.ESTIMATE_REQUESTED)) {
      return;
    }
    RentalOrder order =
        rentalOrders
            .findForUpdate(returnDocument.getRentalOrderId())
            .orElseThrow(() -> new LogisticsConflictException("Rental order was not found"));
    if (order.getStatus() != RentalOrderStatus.FULFILLED) {
      return;
    }
    List<LogisticsDocument> returns =
        documentRepository
            .findAllByDocumentTypeAndRentalOrderIdAndRentalShipmentIdIsNotNullOrderByCreatedAtAscIdAsc(
                LogisticsDocumentType.RETURN, order.getId());
    if (returns.isEmpty()
        || returns.stream()
            .anyMatch(
                document ->
                    document.getState() != LogisticsDocumentState.ACCEPTED
                        && document.getState() != LogisticsDocumentState.ESTIMATE_REQUESTED)) {
      return;
    }
    if (!order.close()) return;
    rentalOrders.saveAndFlush(order);
    orderAudit.appendForActor(
        order.getId(),
        OrderAuditEventType.ORDER_CLOSED,
        returnDocument.getRequestedBySubjectId(),
        order.getCreatedByRole(),
        "ORDER",
        order.getId().toString(),
        Map.of("status", RentalOrderStatus.FULFILLED.name()),
        Map.of("status", RentalOrderStatus.CLOSED.name()));
  }

  private List<LogisticsDocumentLine> linesRequired(UUID documentId) {
    List<LogisticsDocumentLine> lines =
        lineRepository.findAllByDocument_IdOrderByLineNumber(documentId);
    if (lines.isEmpty()) throw new IllegalStateException("Logistics document has no lines");
    return lines;
  }

  private boolean allRentalOrderUnitsShipped(RentalOrder order) {
    Set<UUID> expectedUnitIds =
        rentalTerms.findAllByOrder_IdOrderByRentalItemIdAsc(order.getId()).stream()
            .map(RentalOrderUnitTerm::getRentalItemId)
            .collect(java.util.stream.Collectors.toSet());
    if (expectedUnitIds.isEmpty()) {
      // Pre-terms orders are retained for migration compatibility; their active asset reservations
      // are the only available complete-order source of truth.
      expectedUnitIds =
          validRentalOrderReservations(order, dependencies.readOrderUnits(order.getId())).stream()
              .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
              .collect(java.util.stream.Collectors.toSet());
    }
    if (expectedUnitIds.isEmpty()) return false;
    return new HashSet<>(lineRepository.findShippedRentalOrderAssetIds(order.getId()))
        .containsAll(expectedUnitIds);
  }

  private void releaseFulfilledOrderEquipmentReservations(
      RentalOrder order, LogisticsDocument shipment) {
    boolean hasFurnitureReservation =
        equipmentRequirements
            .findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(order.getId())
            .stream()
            .anyMatch(requirement -> requirement.getQuantity() > 0);
    if (!hasFurnitureReservation) {
      return;
    }
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("rental-order:equipment-release:v1:" + order.getId()).getBytes(StandardCharsets.UTF_8));
    List<LogisticsDependencyGateway.OrderEquipmentReservation> released =
        dependencies.replaceOrderEquipmentReservations(
            idempotencyKey,
            order.getId(),
            order.getWarehouseId(),
            shipment.getRequestedBySubjectId(),
            order.getCreatedByRole(),
            List.of());
    if (released == null || !released.isEmpty()) {
      throw new LogisticsConflictException(
          "Не удалось освободить резерв мебели отгруженного заказа");
    }
  }

  private static List<LogisticsDependencyGateway.OrderUnitReservation> validRentalOrderReservations(
      RentalOrder order, List<LogisticsDependencyGateway.OrderUnitReservation> reservations) {
    if (reservations == null || reservations.isEmpty() || reservations.size() > 100) {
      throw new LogisticsConflictException("Заказ должен содержать от 1 до 100 бытовок");
    }
    HashSet<UUID> unitIds = new HashSet<>();
    for (LogisticsDependencyGateway.OrderUnitReservation reservation : reservations) {
      if (reservation == null
          || reservation.unit() == null
          || reservation.unitId() == null
          || !reservation.unitId().equals(reservation.unit().id())
          || !order.getId().equals(reservation.orderId())
          || !order.getWarehouseId().equals(reservation.warehouseId())
          || !"ACTIVE".equals(reservation.state())
          || !unitIds.add(reservation.unitId())) {
        throw new LogisticsConflictException("Order unit reservation is invalid");
      }
    }
    return List.copyOf(reservations);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
