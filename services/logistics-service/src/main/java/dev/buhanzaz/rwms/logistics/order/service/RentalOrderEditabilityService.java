package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.util.List;
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

  /**
   * Performs the actor and document-lock checks before a command mutates a draft or saved order.
   */
  void requireEditable(OrderActor actor, RentalOrder order) {
    access.requireEditable(actor, order);
    order.requireEditable();
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
    return order.getStatus() == RentalOrderStatus.DRAFT
        || documents.isRentalOrderShipmentDraftEditable(order.getId());
  }

  /**
   * Keeps the existing shipment-assignment guard at the order-document boundary before a cabin
   * reservation is removed.
   */
  void requireUnitNotAssignedToShipment(UUID orderId, UUID unitId) {
    if (documents.isRentalOrderUnitAssignedToShipment(orderId, unitId)) {
      throw RentalOrderProblems.conflict(
          "ORDER_UNIT_SHIPMENT_ASSIGNED",
          "Бытовку нельзя удалить после назначения в отгрузку");
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
