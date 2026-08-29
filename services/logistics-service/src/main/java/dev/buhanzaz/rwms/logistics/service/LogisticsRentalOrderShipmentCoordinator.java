package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.driver.settings.service.ShipmentTaskSettingsService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns a saved rental order's partial-shipment draft lifecycle: durable replay, selected-cabin
 * creation, and the guarded synchronization used while the order is still editable.
 */
@Service
@RequiredArgsConstructor
class LogisticsRentalOrderShipmentCoordinator {
  private static final String CREATE_RENTAL_ORDER_SHIPMENT = "CREATE_RENTAL_ORDER_SHIPMENT";

  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final ShipmentFurnitureMovementTaskRepository shipmentFurnitureTaskRepository;
  private final ShipmentFurnitureTaskService shipmentFurnitureTasks;
  private final LogisticsEventStore eventStore;
  private final LogisticsDocumentWarehouseAdmission warehouseAdmission;
  private final LogisticsDocumentIdempotency idempotency;
  private final LogisticsDocumentReadProjection readProjection;
  private final DocumentDriverTaskPlanner driverTaskPlanner;
  private final ShipmentTaskSettingsService shipmentTaskSettings;
  private final CustomerRentalSessionRepository customerSessions;
  private final LogisticsDocumentAttemptWriter attemptWriter;

  LogisticsDocumentCommandResult replayRentalOrderShipment(
      UUID subjectId, UUID idempotencyKey, String checksum) {
    idempotency.acquireLock(subjectId, CREATE_RENTAL_ORDER_SHIPMENT, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, CREATE_RENTAL_ORDER_SHIPMENT, checksum);
    return replay == null ? null : result(replay, true);
  }

  LogisticsDocumentCommandResult createRentalOrderShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> reservations,
      CreateOrderRentalShipmentRequest request,
      String checksum) {
    requireRequest(order);
    if (order.getWarehouseId() == null) {
      throw new LogisticsConflictException("Сохранённый заказ требуется для создания отгрузки");
    }
    warehouseAdmission.requireLegacyAdmissionDisabled();
    return createRentalOrderShipment(
        subjectId,
        idempotencyKey,
        correlationId,
        order,
        reservations,
        request,
        checksum,
        warehouseAdmission.testTicket(
            subjectId,
            CREATE_RENTAL_ORDER_SHIPMENT,
            idempotencyKey,
            List.of(
                new AdmissionRequirement(
                    inventorySource(order, request), WarehouseOperationDirection.OUTGOING))));
  }

  LogisticsDocumentCommandResult createRentalOrderShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> reservations,
      CreateOrderRentalShipmentRequest request,
      String checksum,
      AdmissionTicket admission) {
    requireRequest(order);
    requireRequest(request);
    if (subjectId == null || correlationId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Shipment actor and correlation identifiers are required");
    }
    idempotency.acquireLock(subjectId, CREATE_RENTAL_ORDER_SHIPMENT, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, CREATE_RENTAL_ORDER_SHIPMENT, checksum);
    if (replay != null) return result(replay, true);
    if (order.getStatus() != RentalOrderStatus.SAVED
        || order.getWarehouseId() == null
        || order.getClient() == null) {
      throw new LogisticsConflictException("Сохранённый заказ требуется для создания отгрузки");
    }
    UUID inventorySourceWarehouseId = inventorySource(order, request);
    warehouseAdmission.requireAdmission(
        admission,
        List.of(
            new AdmissionRequirement(
                inventorySourceWarehouseId, WarehouseOperationDirection.OUTGOING)));
    if (request.scheduledDate().isBefore(admission.localDate(inventorySourceWarehouseId))) {
      throw new LogisticsConflictException("Дата отгрузки не может быть в прошлом");
    }
    if (request.warehouseDriverPool()
        && !request.scheduledDate().isAfter(admission.localDate(inventorySourceWarehouseId))) {
      throw new LogisticsConflictException(
          "Общую доставку для свободного водителя можно опубликовать только на будущую дату");
    }
    if (request.warehouseDriverPool() && request.driverWorkerId() != null) {
      throw new LogisticsConflictException(
          "Общая доставка не может одновременно иметь назначенного водителя");
    }

    List<LogisticsDependencyGateway.OrderUnitReservation> validReservations =
        validRentalOrderReservations(order, reservations);
    List<UUID> selectedUnitIds = sortedSelectedUnitIds(request.unitIds());
    shipmentTaskSettings.requireWithinLimit(
        inventorySourceWarehouseId, selectedUnitIds.size(), subjectId);
    Map<UUID, LogisticsDependencyGateway.OrderUnitReservation> reservationsByUnit =
        validReservations.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    LogisticsDependencyGateway.OrderUnitReservation::unitId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    if (!reservationsByUnit.keySet().containsAll(selectedUnitIds)) {
      throw new LogisticsConflictException("Отгрузка содержит бытовку не из выбранного заказа");
    }
    if (selectedUnitIds.stream()
        .map(reservationsByUnit::get)
        .anyMatch(
            reservation ->
                reservation == null
                    || !inventorySourceWarehouseId.equals(reservation.warehouseId()))) {
      throw new LogisticsConflictException(
          "Одна отгрузка не может содержать бытовки из разных складов-источников");
    }
    List<UUID> assigned =
        lineRepository.findAssignedRentalShipmentAssetIds(order.getId(), selectedUnitIds);
    if (!assigned.isEmpty()) {
      throw new LogisticsConflictException(
          "Одна или несколько выбранных бытовок уже назначены другой отгрузке");
    }
    List<RentalOrderUnitTerm> terms =
        rentalTerms.findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(
            order.getId(), selectedUnitIds);
    if (terms.size() != selectedUnitIds.size()
        || terms.stream().anyMatch(term -> !selectedUnitIds.contains(term.getRentalItemId()))) {
      throw new LogisticsConflictException(
          "Для каждой выбранной бытовки должен быть задан срок аренды");
    }
    if (terms.stream().anyMatch(term -> term.getRentalShipmentId() != null)) {
      throw new LogisticsConflictException(
          "Одна или несколько выбранных бытовок уже назначены другой отгрузке");
    }

    LogisticsDocument document =
        LogisticsDocument.createRentalOrderShipment(
            order.getWarehouseId(),
            order.getClient().getId(),
            order.getId(),
            order.getClient().getDisplayName(),
            subjectId,
            correlationId);
    document.scheduleShipment(
        request.driverSnapshot(),
        request.driverWorkerId(),
        request.scheduledDate(),
        request.warehouseDriverPool());
    documentRepository.saveAndFlush(document);
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(
            rentalOrderShipmentLines(
                document,
                order,
                reservationsByUnit,
                selectedUnitIds,
                inventorySourceWarehouseId));
    customerSessions
        .findFirstByOrderIdOrderByCreatedAtAscIdAsc(order.getId())
        .ifPresent(
            session -> {
              OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
              for (LogisticsDocumentLine line : lines) {
                attemptWriter.createLineAttempt(
                    document,
                    line,
                    LogisticsTargetService.MEDIA,
                    LogisticsDocumentEffectOperations.SHIPMENT_MEDIA_OWNER_PROOF_REGISTER,
                    attemptWriter.ownerProofDigest(
                        LogisticsDocumentEffectOperations.SHIPMENT_MEDIA_OWNER_PROOF_REGISTER,
                        document,
                        line,
                        document.getWarehouseId(),
                        0,
                        0,
                        true,
                        session.getCustomerSubjectId()),
                    createdAt);
              }
            });
    shipmentFurnitureTasks.attachReplacementMovementsToShipment(document, selectedUnitIds);
    driverTaskPlanner.plan(document, lines);
    for (RentalOrderUnitTerm term : terms) {
      term.assignShipment(document.getId(), request.scheduledDate());
    }
    rentalTerms.saveAllAndFlush(terms);
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    warehouseAdmission.enqueue(document, inventorySourceWarehouseId, admission);
    idempotency.remember(
        subjectId, idempotencyKey, CREATE_RENTAL_ORDER_SHIPMENT, checksum, document);
    return result(document, false);
  }

  boolean isRentalOrderShipmentDraftEditable(UUID orderId) {
    if (orderId == null) return false;
    return documentRepository
        .findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.SHIPMENT, orderId)
        .stream()
        .filter(document -> document.getState() != LogisticsDocumentState.CANCELLED)
        .allMatch(this::isRentalOrderShipmentDraftEditable);
  }

  /**
   * Returns whether at least one active order cabin is unassigned or belongs only to pending lines
   * of non-departed shipments. A started earlier batch does not hide replacement for a later cabin,
   * while the exact command still applies its task-board cancellation fence.
   */
  boolean hasRentalOrderReplaceableUnit(UUID orderId, Set<UUID> activeUnitIds) {
    if (orderId == null || activeUnitIds == null || activeUnitIds.isEmpty()) return false;
    Set<UUID> ineligible = new HashSet<>();
    for (LogisticsDocument document :
        documentRepository.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.SHIPMENT, orderId)) {
      if (document.getState() == LogisticsDocumentState.CANCELLED) continue;
      boolean documentPreStart = replacementPreStartState(document.getState());
      for (LogisticsDocumentLine line :
          lineRepository.findAllByDocument_IdOrderByLineNumber(document.getId())) {
        if (activeUnitIds.contains(line.getAssetId())
            && (!documentPreStart || line.getState() != LogisticsLineState.PENDING)) {
          ineligible.add(line.getAssetId());
        }
      }
    }
    return activeUnitIds.stream().anyMatch(unitId -> !ineligible.contains(unitId));
  }

  boolean isRentalOrderUnitReplacementPreStart(UUID orderId, UUID unitId) {
    return unitId != null && hasRentalOrderReplaceableUnit(orderId, Set.of(unitId));
  }

  private static boolean replacementPreStartState(LogisticsDocumentState state) {
    return Set.of(
            LogisticsDocumentState.DRAFT,
            LogisticsDocumentState.PREPARING,
            LogisticsDocumentState.AWAITING_CONFIRMATION,
            LogisticsDocumentState.CONFIRMING_PREPARATION)
        .contains(state);
  }

  boolean lockRentalOrderShipmentDraftForOrderEditing(UUID orderId) {
    if (orderId == null) return false;
    return documentRepository.findAllByRentalOrderIdForUpdate(orderId).stream()
        .filter(document -> document.getDocumentType() == LogisticsDocumentType.SHIPMENT)
        .filter(document -> document.getState() != LogisticsDocumentState.CANCELLED)
        .allMatch(this::isRentalOrderShipmentDraftEditable);
  }

  void synchronizeRentalOrderShipmentDraft(
      UUID actorSubjectId,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> reservations) {
    if (actorSubjectId == null || order == null) {
      throw new IllegalArgumentException("Rental shipment synchronization is invalid");
    }
    for (LogisticsDocument document :
        documentRepository.findAllByRentalOrderIdForUpdate(order.getId())) {
      if (document.getDocumentType() != LogisticsDocumentType.SHIPMENT
          || !isRentalOrderShipmentDraftEditable(document)) {
        continue;
      }
      boolean changed =
          document.synchronizeRentalOrderParty(
              order.getClient().getId(), order.getClient().getDisplayName());
      if (changed) {
        LogisticsDocument persisted = documentRepository.saveAndFlush(document);
        eventStore.append(
            persisted,
            lineRepository.findAllByDocument_IdOrderByLineNumber(persisted.getId()).size(),
            persisted.getCorrelationId(),
            actorSubjectId,
            LogisticsEventType.SHIPMENT_DRAFT_UPDATED,
            null);
      }
    }
  }

  private LogisticsDocumentCommandResult result(LogisticsDocument document, boolean replayed) {
    return new LogisticsDocumentCommandResult(readProjection.view(document), replayed);
  }

  private boolean isRentalOrderShipmentDraftEditable(LogisticsDocument shipment) {
    return shipment.getDocumentType() == LogisticsDocumentType.SHIPMENT
        && shipment.getRentalOrderId() != null
        && shipment.getState() == LogisticsDocumentState.DRAFT
        && shipment.getScheduledDate() == null
        && !shipmentFurnitureTaskRepository.existsOperationalByDocumentId(shipment.getId());
  }

  private static List<UUID> sortedSelectedUnitIds(List<UUID> unitIds) {
    if (unitIds == null || unitIds.isEmpty() || unitIds.size() > 100) {
      throw new LogisticsConflictException("Отгрузка должна содержать от 1 до 100 бытовок");
    }
    Set<UUID> unique = new HashSet<>();
    for (UUID unitId : unitIds) {
      if (unitId == null || !unique.add(unitId)) {
        throw new LogisticsConflictException("Список бытовок для отгрузки содержит дубли");
      }
    }
    return unique.stream().sorted().toList();
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
          || reservation.warehouseId() == null
          || !reservation.warehouseId().equals(reservation.unit().warehouseId())
          || !"ACTIVE".equals(reservation.state())
          || !unitIds.add(reservation.unitId())) {
        throw new LogisticsConflictException("Order unit reservation is invalid");
      }
    }
    return List.copyOf(reservations);
  }

  private static List<LogisticsDocumentLine> rentalOrderShipmentLines(
      LogisticsDocument document,
      RentalOrder order,
      Map<UUID, LogisticsDependencyGateway.OrderUnitReservation> reservationsByUnit,
      List<UUID> selectedUnitIds,
      UUID inventorySourceWarehouseId) {
    List<LogisticsDocumentLine> lines = new ArrayList<>(selectedUnitIds.size());
    for (int index = 0; index < selectedUnitIds.size(); index++) {
      LogisticsDependencyGateway.OrderUnitReservation reservation =
          reservationsByUnit.get(selectedUnitIds.get(index));
      if (reservation == null) {
        throw new LogisticsConflictException("Order unit reservation is invalid");
      }
      LogisticsDocumentLine line =
          LogisticsDocumentLine.create(
              document,
              index + 1,
              reservation.unitId(),
              reservation.unit().version(),
              order.getClient().getDisplayName(),
              order.getId(),
              inventorySourceWarehouseId);
      line.captureSourceAllocations(emptyAllocationSnapshot());
      lines.add(line);
    }
    return lines;
  }

  private static ObjectNode emptyAllocationSnapshot() {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.putArray("allocations");
    return root;
  }

  private static UUID inventorySource(
      RentalOrder order, CreateOrderRentalShipmentRequest request) {
    if (order == null || order.getWarehouseId() == null || request == null) {
      throw new IllegalArgumentException("Shipment source is invalid");
    }
    return request.inventorySourceWarehouseId() == null
        ? order.getWarehouseId()
        : request.inventorySourceWarehouseId();
  }

  private static void requireRequest(Object request) {
    if (request == null) throw new IllegalArgumentException("Request is required");
  }
}
