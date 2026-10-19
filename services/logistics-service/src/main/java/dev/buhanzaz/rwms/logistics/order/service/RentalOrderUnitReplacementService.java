package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService.ExistingShipmentFurnitureMovement;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService.ReplacementCheckpoint;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService.ReplacementCheckpointCommand;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService.ReplacementPreparation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the two entry points of the existing same-order cabin replacement: a direct
 * warehouse-manager command and a client presentation selection. The service warehouse remains on
 * the order while an authorized physical source is checkpointed independently for recovery.
 */
@Service
@RequiredArgsConstructor
public class RentalOrderUnitReplacementService {
  private static final String REPLACE_UNIT = "REPLACE_UNIT";

  private final RentalOrderReadService reads;
  private final RentalOrderReservationService reservations;
  private final RentalOrderMutationLocalStore orderMutations;
  private final RentalOrderInventorySourcePolicy inventorySources;
  private final OrderAuthorizer access;
  private final LogisticsDependencyGateway dependencies;
  private final ShipmentFurnitureTaskService furnitureTasks;
  private final EquipmentMovementTaskService movementTasks;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository documentLines;
  private final DocumentDriverTaskPlanner driverTaskPlanner;
  private final LogisticsDocumentService documentService;

  /** Performs one nonblank-reason replacement without sending a client presentation. */
  public OrderDetailResponse replaceDirect(
      OrderActor actor,
      UUID orderId,
      long expectedVersion,
      UUID oldRentalItemId,
      UUID replacementRentalItemId,
      String reason,
      UUID idempotencyKey) {
    return replaceDirect(
        actor,
        orderId,
        expectedVersion,
        oldRentalItemId,
        replacementRentalItemId,
        reason,
        null,
        idempotencyKey);
  }

  /** Performs one source-aware nonblank-reason replacement without a client presentation. */
  public OrderDetailResponse replaceDirect(
      OrderActor actor,
      UUID orderId,
      long expectedVersion,
      UUID oldRentalItemId,
      UUID replacementRentalItemId,
      String reason,
      UUID inventorySourceWarehouseId,
      UUID idempotencyKey) {
    String normalizedReason = requireReason(reason);
    return replaceBatch(
        actor,
        orderId,
        expectedVersion,
        List.of(new ReplacementPair(oldRentalItemId, replacementRentalItemId)),
        normalizedReason,
        inventorySourceWarehouseId,
        null,
        idempotencyKey);
  }

  /** Applies every exact client-selected replacement deterministically inside the target order. */
  public OrderDetailResponse replaceFromPresentation(
      OrderActor actor,
      UUID orderId,
      UUID holdScopeId,
      UUID bookingId,
      List<UUID> oldRentalItemIds,
      List<UUID> replacementRentalItemIds) {
    if (holdScopeId == null
        || bookingId == null
        || oldRentalItemIds == null
        || replacementRentalItemIds == null
        || oldRentalItemIds.size() != replacementRentalItemIds.size()
        || oldRentalItemIds.isEmpty()) {
      throw new IllegalArgumentException("Client replacement mapping is invalid");
    }
    if (Set.copyOf(oldRentalItemIds).size() != oldRentalItemIds.size()
        || Set.copyOf(replacementRentalItemIds).size() != replacementRentalItemIds.size()) {
      throw new IllegalArgumentException("Client replacement mapping is invalid");
    }
    List<ReplacementPair> pairs = new ArrayList<>(oldRentalItemIds.size());
    for (int index = 0; index < oldRentalItemIds.size(); index++) {
      pairs.add(
          new ReplacementPair(oldRentalItemIds.get(index), replacementRentalItemIds.get(index)));
    }
    OrderDetailResponse current = reads.get(actor, orderId);
    return replaceBatch(
        actor, orderId, current.version(), List.copyOf(pairs), null, null, holdScopeId, bookingId);
  }

  /**
   * Validates every client-presentation target against the same per-cabin pre-start predicate used
   * immediately before a swap. This prevents holding alternatives for a cabin that was already
   * dispatched while the client page was being prepared.
   */
  public void requirePresentationTargetsPreStart(UUID orderId, List<UUID> targetUnitIds) {
    if (orderId == null || targetUnitIds == null || targetUnitIds.isEmpty()) {
      throw new IllegalArgumentException("Replacement presentation targets are required");
    }
    targetUnitIds.forEach(unitId -> requireTripNotStarted(orderId, unitId));
  }

  /** Returns true when the task belongs to a pending replacement and was recovered here. */
  public boolean recoverPendingMovement(UUID equipmentMovementTaskId) {
    if (!furnitureTasks.isPendingReplacementMovement(equipmentMovementTaskId)) return false;
    for (UUID checkpointId : furnitureTasks.pendingReplacementCheckpointIds()) {
      ReplacementCheckpoint checkpoint = furnitureTasks.replacementCheckpoint(checkpointId);
      if (equipmentMovementTaskId.equals(checkpoint.equipmentMovementTaskId())) {
        recover(checkpoint.orderId(), checkpoint.batchIdempotencyKey());
        return true;
      }
    }
    return false;
  }

  /** Recovers no-movement checkpoints and crash windows before the ordinary movement relay. */
  public void recoverPending() {
    Set<String> recovered = new java.util.HashSet<>();
    for (UUID checkpointId : furnitureTasks.pendingReplacementCheckpointIds()) {
      ReplacementCheckpoint checkpoint = furnitureTasks.replacementCheckpoint(checkpointId);
      String batch = checkpoint.orderId() + ":" + checkpoint.batchIdempotencyKey();
      if (recovered.add(batch)) {
        recover(checkpoint.orderId(), checkpoint.batchIdempotencyKey());
      }
    }
  }

  private OrderDetailResponse replaceBatch(
      OrderActor actor,
      UUID orderId,
      long expectedVersion,
      List<ReplacementPair> pairs,
      String reason,
      UUID requestedInventorySourceWarehouseId,
      UUID presentationId,
      UUID batchIdempotencyKey) {
    if (actor == null
        || orderId == null
        || expectedVersion < 0
        || pairs == null
        || pairs.isEmpty()
        || batchIdempotencyKey == null) {
      throw new IllegalArgumentException("Cabin replacement command is invalid");
    }
    orderMutations.requireNoOpenMutation(actor, orderId);
    OrderDetailResponse order = reads.get(actor, orderId);
    Map<UUID, UUID> mapping = new LinkedHashMap<>();
    for (ReplacementPair pair : pairs) {
      if (pair == null
          || pair.oldRentalItemId() == null
          || pair.replacementRentalItemId() == null
          || pair.oldRentalItemId().equals(pair.replacementRentalItemId())
          || mapping.putIfAbsent(pair.oldRentalItemId(), pair.replacementRentalItemId()) != null) {
        throw new IllegalArgumentException("Cabin replacement command is invalid");
      }
    }
    if (Set.copyOf(mapping.values()).size() != mapping.size()
        || !java.util.Collections.disjoint(mapping.keySet(), Set.copyOf(mapping.values()))) {
      throw new IllegalArgumentException("Cabin replacement command is invalid");
    }
    requireManagerAndOrder(actor, order, expectedVersion, mapping);
    UUID inventorySourceWarehouseId =
        inventorySources.requireWritableReplacementSource(
            actor, order.warehouseId(), requestedInventorySourceWarehouseId);
    mapping.keySet().forEach(oldUnitId -> requireTripNotStarted(orderId, oldUnitId));
    preflightOldFurnitureTasks(
        actor, orderId, mapping.keySet(), batchIdempotencyKey, presentationId != null);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        reservations.replacementComposition(orderId, mapping);
    List<ReplacementPreparation> preparations = new ArrayList<>(pairs.size());
    for (int index = 0; index < pairs.size(); index++) {
      ReplacementPair pair = pairs.get(index);
      List<LogisticsDependencyGateway.OrderEquipmentRequirement> replacementRequirements =
          composition.stream()
              .filter(unit -> pair.replacementRentalItemId().equals(unit.rentalItemId()))
              .findFirst()
              .orElseThrow()
              .requirements();
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan;
      try {
        plan =
            dependencies.planOrderFurnitureMovements(
                orderId,
                inventorySourceWarehouseId,
                pair.replacementRentalItemId(),
                pair.oldRentalItemId(),
                replacementRequirements,
                composition);
      } catch (LogisticsDependencyException exception) {
        throw RentalOrderProblems.dependencyProblem(exception);
      }
      requireDirectReplacementPlan(
          plan,
          orderId,
          inventorySourceWarehouseId,
          pair.oldRentalItemId(),
          pair.replacementRentalItemId());
      UUID pairIdempotencyKey =
          pairs.size() == 1
              ? batchIdempotencyKey
              : UUID.nameUUIDFromBytes(
                  ("presentation-unit-replacement:"
                          + batchIdempotencyKey
                          + ":"
                          + index
                          + ":"
                          + pair.oldRentalItemId())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String checksum =
          OrderCommandChecksum.sha256(
              REPLACE_UNIT,
              List.of(
                  orderId.toString(),
                  Long.toString(expectedVersion),
                  Integer.toString(index),
                  pair.oldRentalItemId().toString(),
                  pair.replacementRentalItemId().toString(),
                  inventorySourceWarehouseId.toString(),
                  reason == null ? "" : reason,
                  presentationId == null ? "" : presentationId.toString(),
                  batchIdempotencyKey.toString()));
      preparations.add(
          new ReplacementPreparation(
              new ReplacementCheckpointCommand(
                  orderId,
                  expectedVersion,
                  order.warehouseId(),
                  inventorySourceWarehouseId,
                  pair.oldRentalItemId(),
                  pair.replacementRentalItemId(),
                  reason,
                  actor.subjectId(),
                  actor.role(),
                  pairIdempotencyKey,
                  batchIdempotencyKey,
                  index,
                  checksum,
                  presentationId),
              plan));
    }
    furnitureTasks.checkpointReplacements(List.copyOf(preparations));
    return process(orderId, batchIdempotencyKey, true, presentationId != null);
  }

  private void recover(UUID orderId, UUID batchIdempotencyKey) {
    try {
      process(orderId, batchIdempotencyKey, false, false);
    } catch (RuntimeException ignored) {
      // The durable checkpoint remains pending; a later scheduled pass replays the same asset key.
    }
  }

  private OrderDetailResponse process(
      UUID orderId, UUID batchIdempotencyKey, boolean propagate, boolean presentationBooking) {
    orderMutations.requireNoOpenMutation(orderId);
    List<ReplacementCheckpoint> checkpoints =
        furnitureTasks.replacementBatch(orderId, batchIdempotencyKey);
    ReplacementCheckpoint first = checkpoints.getFirst();
    OrderActor actor = actor(first);
    if (checkpoints.stream().noneMatch(ReplacementCheckpoint::pending)) {
      return reads.get(actor, orderId);
    }
    if (checkpoints.stream().anyMatch(checkpoint -> !checkpoint.pending())) {
      throw RentalOrderProblems.conflict(
          "REPLACEMENT_BATCH_INCONSISTENT", "Пакетная замена требует восстановления");
    }
    if (checkpoints.stream()
        .anyMatch(
            checkpoint ->
                !first
                    .inventorySourceWarehouseId()
                    .equals(checkpoint.inventorySourceWarehouseId()))) {
      throw RentalOrderProblems.conflict(
          "REPLACEMENT_BATCH_INCONSISTENT", "Пакетная замена содержит разные склады-источники");
    }
    Map<UUID, UUID> mapping = new LinkedHashMap<>();
    checkpoints.forEach(
        checkpoint ->
            mapping.put(checkpoint.oldRentalItemId(), checkpoint.replacementRentalItemId()));
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        reservations.replacementComposition(orderId, mapping);
    List<LogisticsDependencyGateway.OrderUnitReplacement> replacements =
        checkpoints.stream()
            .map(
                checkpoint ->
                    new LogisticsDependencyGateway.OrderUnitReplacement(
                        checkpoint.oldRentalItemId(),
                        checkpoint.replacementRentalItemId(),
                        replacementMovement(checkpoint)))
            .toList();
    try {
      fenceAffectedTrips(checkpoints);
      LogisticsDependencyGateway.OrderUnitsReplacementReceipt receipt =
          first.warehouseId().equals(first.inventorySourceWarehouseId())
              ? dependencies.replaceOrderUnits(
                  batchIdempotencyKey,
                  orderId,
                  first.warehouseId(),
                  first.presentationId(),
                  first.actorSubjectId(),
                  first.actorRole(),
                  composition,
                  replacements)
              : dependencies.replaceOrderUnits(
                  batchIdempotencyKey,
                  orderId,
                  first.warehouseId(),
                  first.inventorySourceWarehouseId(),
                  first.presentationId(),
                  first.actorSubjectId(),
                  first.actorRole(),
                  composition,
                  replacements);
      try {
        return reservations.finalizeReplacements(orderId, batchIdempotencyKey, receipt).response();
      } catch (RuntimeException exception) {
        throw new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.TRANSIENT,
            "Atomic cabin replacement is awaiting local reconciliation",
            exception);
      }
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        restoreAffectedTrips(checkpoints);
        furnitureTasks.rejectReplacementBatch(
            orderId,
            batchIdempotencyKey,
            exception.dependencyCode() == null
                ? "REPLACEMENT_REJECTED"
                : exception.dependencyCode());
      }
      if (propagate) {
        if (presentationBooking) throw exception;
        throw RentalOrderProblems.dependencyProblem(exception);
      }
      return null;
    }
  }

  private void fenceAffectedTrips(List<ReplacementCheckpoint> checkpoints) {
    for (UUID documentId : affectedDocumentIds(checkpoints)) {
      LogisticsDocument document =
          documents
              .findById(documentId)
              .orElseThrow(
                  () ->
                      RentalOrderProblems.conflict(
                          "REPLACEMENT_DOCUMENT_NOT_FOUND", "Ходка замены не найдена"));
      driverTaskPlanner.cancelBeforeStart(
          document, documentLines.findAllByDocument_IdOrderByLineNumber(documentId));
    }
  }

  private void restoreAffectedTrips(List<ReplacementCheckpoint> checkpoints) {
    for (UUID documentId : affectedDocumentIds(checkpoints)) {
      LogisticsDocument document =
          documents
              .findById(documentId)
              .orElseThrow(
                  () ->
                      RentalOrderProblems.conflict(
                          "REPLACEMENT_DOCUMENT_NOT_FOUND", "Ходка замены не найдена"));
      driverTaskPlanner.plan(
          document, documentLines.findAllByDocument_IdOrderByLineNumber(documentId));
    }
  }

  private static List<UUID> affectedDocumentIds(List<ReplacementCheckpoint> checkpoints) {
    return checkpoints.stream()
        .map(ReplacementCheckpoint::documentId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
  }

  private LogisticsDependencyGateway.OrderUnitReplacementMovement replacementMovement(
      ReplacementCheckpoint checkpoint) {
    if (checkpoint.equipmentMovementTaskId() == null) return null;
    return movementTasks.replacementMovement(
        checkpoint.equipmentMovementTaskId(), checkpoint.replacementRentalItemId());
  }

  private void requireManagerAndOrder(
      OrderActor actor,
      OrderDetailResponse order,
      long expectedVersion,
      Map<UUID, UUID> replacements) {
    if (!actor.globalAdministrator() && !actor.localAdministrator()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Warehouse manager role is required for cabin replacement");
    }
    if (order.version() != expectedVersion) {
      throw RentalOrderProblems.conflict("ORDER_VERSION_CONFLICT", "Заказ был изменён параллельно");
    }
    if ((order.status() != RentalOrderStatus.DRAFT && order.status() != RentalOrderStatus.SAVED)
        || order.warehouseId() == null) {
      throw RentalOrderProblems.conflict(
          "ORDER_NOT_REPLACEABLE", "В этом заказе нельзя заменить бытовку");
    }
    access.requireWarehouseEdit(actor, order.warehouseId());
    Set<UUID> current =
        order.units().stream()
            .map(unit -> unit.unit().id())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!current.containsAll(replacements.keySet())
        || current.stream().anyMatch(Set.copyOf(replacements.values())::contains)) {
      throw RentalOrderProblems.conflict(
          "REPLACEMENT_UNIT_INVALID", "Состав бытовок заказа не соответствует замене");
    }
  }

  private void requireTripNotStarted(UUID orderId, UUID oldRentalItemId) {
    if (!documentService.isRentalOrderUnitReplacementPreStart(orderId, oldRentalItemId)) {
      throw started();
    }
  }

  private void preflightOldFurnitureTasks(
      OrderActor actor,
      UUID orderId,
      Set<UUID> oldRentalItemIds,
      UUID batchIdempotencyKey,
      boolean presentationBooking) {
    boolean awaitingCancellation = false;
    for (LogisticsDocument document :
        documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.SHIPMENT, orderId)) {
      if (document.getState() == LogisticsDocumentState.CANCELLED) continue;
      Set<UUID> documentOldIds =
          documentLines.findAllByDocument_IdOrderByLineNumber(document.getId()).stream()
              .map(LogisticsDocumentLine::getAssetId)
              .filter(oldRentalItemIds::contains)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      for (UUID oldRentalItemId : documentOldIds) {
        ExistingShipmentFurnitureMovement movement =
            furnitureTasks.existingOrdinaryMovement(document.getId(), oldRentalItemId);
        if (movement == null || movement.state() == EquipmentMovementTaskState.COMPLETED) {
          continue;
        }
        if (movement.state() == EquipmentMovementTaskState.EXECUTING
            || movement.state() == EquipmentMovementTaskState.RECONCILIATION_REQUIRED) {
          throw furnitureTaskActive();
        }
        if (Set.of(
                EquipmentMovementTaskState.CANCELLED,
                EquipmentMovementTaskState.EXPIRED,
                EquipmentMovementTaskState.CONFLICT)
            .contains(movement.state())) {
          furnitureTasks.removeReleasedOrdinaryMovement(
              document.getId(), oldRentalItemId, movement.equipmentMovementTaskId());
          continue;
        }
        if (movement.state() != EquipmentMovementTaskState.CANCELLING) {
          UUID cancellationKey =
              UUID.nameUUIDFromBytes(
                  ("replacement-old-furniture-cancel:"
                          + batchIdempotencyKey
                          + ":"
                          + movement.equipmentMovementTaskId())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
          try {
            movementTasks.cancel(
                actor.subjectId(),
                movement.equipmentMovementTaskId(),
                cancellationKey,
                new CancelEquipmentMovementTaskRequest(movement.taskVersion()));
          } catch (dev.buhanzaz.rwms.logistics.service.LogisticsConflictException exception) {
            ExistingShipmentFurnitureMovement refreshed =
                furnitureTasks.existingOrdinaryMovement(document.getId(), oldRentalItemId);
            if (refreshed == null
                || Set.of(
                        EquipmentMovementTaskState.CANCELLED,
                        EquipmentMovementTaskState.EXPIRED,
                        EquipmentMovementTaskState.CONFLICT)
                    .contains(refreshed.state())) {
              if (refreshed != null) {
                furnitureTasks.removeReleasedOrdinaryMovement(
                    document.getId(), oldRentalItemId, refreshed.equipmentMovementTaskId());
              }
              continue;
            }
            if (refreshed.state() == EquipmentMovementTaskState.EXECUTING
                || refreshed.state() == EquipmentMovementTaskState.RECONCILIATION_REQUIRED) {
              throw furnitureTaskActive();
            }
          }
        }
        awaitingCancellation = true;
      }
    }
    if (awaitingCancellation) {
      if (presentationBooking) {
        throw new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.TRANSIENT,
            "Old cabin furniture task cancellation is still being reconciled");
      }
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "REPLACEMENT_FURNITURE_CANCELLING",
          "Дождитесь отмены старого задания на перемещение мебели и повторите замену");
    }
  }

  private static void requireDirectReplacementPlan(
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan,
      UUID orderId,
      UUID inventorySourceWarehouseId,
      UUID oldRentalItemId,
      UUID replacementRentalItemId) {
    if (plan == null
        || !orderId.equals(plan.orderId())
        || !replacementRentalItemId.equals(plan.unitId())
        || plan.lines() == null) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    for (LogisticsDependencyGateway.OrderFurnitureMovementPlanLine line : plan.lines()) {
      if (line == null
          || !inventorySourceWarehouseId.equals(line.sourceWarehouseId())
          || !inventorySourceWarehouseId.equals(line.targetWarehouseId())
          || line.sourceRentalItemId() != null
              && !oldRentalItemId.equals(line.sourceRentalItemId())
          || !replacementRentalItemId.equals(line.targetRentalItemId())
          || line.sourceBalanceId() == null
          || line.expectedSourceBalanceVersion() < 0
          || line.quantity() < 1) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
    }
  }

  private static OrderActor actor(ReplacementCheckpoint checkpoint) {
    boolean global = Set.of("SYSTEM_ADMIN", "WMS_ADMIN").contains(checkpoint.actorRole());
    boolean local = "WAREHOUSE_MANAGER".equals(checkpoint.actorRole());
    return new OrderActor(
        checkpoint.actorSubjectId(),
        checkpoint.actorRole(),
        checkpoint.actorSubjectId().toString(),
        warehouseScopes(checkpoint.warehouseId(), checkpoint.inventorySourceWarehouseId()),
        warehouseScopes(checkpoint.warehouseId(), checkpoint.inventorySourceWarehouseId()),
        global,
        local,
        true,
        true);
  }

  private static String requireReason(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 2000) {
      throw new IllegalArgumentException("Replacement reason is required");
    }
    return normalized;
  }

  private static Set<UUID> warehouseScopes(UUID serviceWarehouseId, UUID sourceWarehouseId) {
    java.util.LinkedHashSet<UUID> values = new java.util.LinkedHashSet<>();
    values.add(serviceWarehouseId);
    values.add(sourceWarehouseId);
    return Set.copyOf(values);
  }

  private static OrderProblemException started() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "REPLACEMENT_SHIPMENT_STARTED",
        "Начатую ходку нельзя перевести на другую бытовку");
  }

  private static OrderProblemException furnitureTaskActive() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "REPLACEMENT_FURNITURE_TASK_ACTIVE",
        "Выполняемое задание мебели нужно завершить до замены бытовки");
  }

  /** One explicit mapping whose list position is durable client choice order. */
  private record ReplacementPair(UUID oldRentalItemId, UUID replacementRentalItemId) {}
}
