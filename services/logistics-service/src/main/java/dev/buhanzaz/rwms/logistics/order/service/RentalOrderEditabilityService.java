package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns the rental-order editability boundary shared by reads and commands: actor authorization,
 * saved-draft document fencing, and synchronization of a still-editable shipment draft.
 */
@Service
@RequiredArgsConstructor
class RentalOrderEditabilityService {
  private final OrderAuthorizer access;
  private final LogisticsDocumentService documents;
  private final ShipmentFurnitureMovementTaskRepository furnitureTaskLinks;

  /**
   * Performs the actor and document-lock checks before a command mutates a draft or saved order.
   */
  void requireEditable(OrderActor actor, RentalOrder order) {
    access.requireEditable(actor, order);
    order.requireEditable();
    requireNoPendingReplacement(order.getId());
    if (order.getStatus() == RentalOrderStatus.SAVED
        && !documents.lockRentalOrderShipmentDraftForOrderEditing(order.getId())) {
      throw RentalOrderProblems.conflict(
          "ORDER_NOT_EDITABLE",
          "Бронирование нельзя изменить после начала отгрузки или создания задания на мебель");
    }
  }

  boolean canEdit(OrderActor actor, RentalOrder order) {
    if (!access.canEdit(actor, order)) {
      return false;
    }
    if (hasPendingReplacement(order.getId())) return false;
    return order.getStatus() == RentalOrderStatus.DRAFT
        || documents.isRentalOrderShipmentDraftEditable(order.getId());
  }

  /**
   * Derives the warehouse-manager replacement affordance independently from ordinary booking
   * editability. A scheduled but not-started trip remains replaceable, while departure makes this
   * false before the browser can offer the command.
   */
  boolean canReplaceUnits(
      OrderActor actor,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> activeUnits) {
    if (actor == null
        || order == null
        || activeUnits == null
        || activeUnits.isEmpty()
        || (!actor.globalAdministrator() && !actor.localAdministrator())
        || !actor.writeScope()
        || !access.isVisible(actor, order)
        || order.getWarehouseId() == null
        || !access.canEditWarehouse(actor, order.getWarehouseId())
        || (order.getStatus() != RentalOrderStatus.DRAFT
            && order.getStatus() != RentalOrderStatus.SAVED)
        || hasPendingReplacement(order.getId())) {
      return false;
    }
    Set<UUID> activeUnitIds =
        activeUnits.stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return documents.hasRentalOrderReplaceableUnit(order.getId(), activeUnitIds);
  }

  void requireNoPendingReplacement(UUID orderId) {
    if (hasPendingReplacement(orderId)) {
      throw RentalOrderProblems.conflict(
          "ORDER_REPLACEMENT_PENDING", "Сначала завершите начатую замену бытовки");
    }
  }

  private boolean hasPendingReplacement(UUID orderId) {
    return furnitureTaskLinks
        .existsByOrder_IdAndReplacementIdempotencyKeyIsNotNullAndReplacementCompletedAtIsNullAndReplacementRejectedAtIsNull(
            orderId);
  }

  /**
   * Keeps the existing shipment-assignment guard at the order-document boundary before a cabin
   * reservation is removed.
   */
  void requireUnitNotAssignedToShipment(UUID orderId, UUID unitId) {
    if (documents.isRentalOrderUnitAssignedToShipment(orderId, unitId)) {
      throw RentalOrderProblems.conflict(
          "ORDER_UNIT_SHIPMENT_ASSIGNED", "Бытовку нельзя удалить после назначения в отгрузку");
    }
  }

  /**
   * Synchronizes only a SAVED order's editable shipment draft using the caller's current remote
   * reservation snapshot; this method intentionally performs no additional read.
   */
  void synchronizeSavedShipmentDraft(
      RentalOrder order,
      OrderActor actor,
      List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    if (order.getStatus() == RentalOrderStatus.SAVED) {
      documents.synchronizeRentalOrderShipmentDraft(actor.subjectId(), order, units);
    }
  }
}
