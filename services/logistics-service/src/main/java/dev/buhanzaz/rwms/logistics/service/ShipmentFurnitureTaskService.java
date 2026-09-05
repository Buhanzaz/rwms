package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessState;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureTaskResult;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureTaskStatusView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureTaskView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.mapper.ShipmentFurnitureTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCommandRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts the authoritative furniture composition of every active cabin in a saved order into
 * existing movement tasks for a shipment. It also persists and recovers ordered cabin-replacement
 * checkpoints, while asset-service remains the owner of physical contents, reservations and plans.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipmentFurnitureTaskService {
  private static final int DEFAULT_PLANNED_DURATION_MINUTES = 60;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository documentLines;
  private final RentalOrderRepository orders;
  private final RentalOrderMutationCommandRepository orderMutationCommands;
  private final RentalOrderEquipmentRequirementRepository requirements;
  private final ShipmentFurnitureMovementTaskRepository taskLinks;
  private final LogisticsDependencyGateway dependencies;
  private final EquipmentMovementTaskService movementTasks;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final ShipmentFurnitureTaskResponseMapper mapper;

  /**
   * Persists the existing movement-task/link checkpoint before an atomic cabin replacement. A
   * replay with the same order-scoped key returns the same checkpoint and never replans lines.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ShipmentFurnitureMovementTask checkpointReplacement(
      ReplacementCheckpointCommand command,
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan) {
    return checkpointReplacements(List.of(new ReplacementPreparation(command, plan))).getFirst();
  }

  /**
   * Atomically persists every pair checkpoint and existing movement task before one asset batch
   * call. A crash can therefore expose either the whole recoverable batch or no batch at all. The
   * locked order cannot acquire a competing checkpoint while a durable cancel/remove command is
   * pending or quarantined.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<ShipmentFurnitureMovementTask> checkpointReplacements(
      List<ReplacementPreparation> preparations) {
    if (preparations == null || preparations.isEmpty()) {
      throw new IllegalArgumentException("Replacement preparations are required");
    }
    ReplacementCheckpointCommand first = preparations.getFirst().command();
    if (first == null) {
      throw new IllegalArgumentException("Replacement command is required");
    }
    RentalOrder order =
        orders
            .findForUpdate(first.orderId())
            .orElseThrow(() -> new LogisticsConflictException("Заказ замены не найден"));
    requirePayment(order);
    if (orderMutationCommands.existsByOrder_IdAndStateIn(
        first.orderId(), Set.of(State.PENDING, State.QUARANTINED))) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_MUTATION_PENDING",
          "Операция заказа ещё восстанавливается");
    }
    List<ShipmentFurnitureMovementTask> replay =
        taskLinks.findAllByOrder_IdAndReplacementBatchIdempotencyKeyOrderByReplacementPairIndexAsc(
            first.orderId(), first.batchIdempotencyKey());
    if (!replay.isEmpty()) {
      if (replay.size() != preparations.size()) {
        throw new LogisticsConflictException("Состав пакетной замены уже зафиксирован иначе");
      }
      for (int index = 0; index < preparations.size(); index++) {
        ReplacementCheckpointCommand expected = preparations.get(index).command();
        ShipmentFurnitureMovementTask existing = replay.get(index);
        if (existing.getReplacementPairIndex() != index
            || !existing.getReplacementIdempotencyKey().equals(expected.idempotencyKey())
            || !existing.matchesReplacementRequest(expected.requestSha256())
            || !existing.getOldRentalItemId().equals(expected.oldRentalItemId())
            || !existing.getRentalItemId().equals(expected.replacementRentalItemId())
            || !existing
                .getReplacementInventorySourceWarehouseId()
                .equals(expected.inventorySourceWarehouseId())) {
          throw new LogisticsConflictException(
              "Idempotency-Key уже использован для другой замены бытовки");
        }
      }
      return replay;
    }
    Set<UUID> oldIds = new HashSet<>();
    Set<UUID> newIds = new HashSet<>();
    List<ShipmentFurnitureMovementTask> created = new ArrayList<>();
    for (int index = 0; index < preparations.size(); index++) {
      ReplacementPreparation preparation = preparations.get(index);
      ReplacementCheckpointCommand command = preparation.command();
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan = preparation.plan();
      if (command == null
          || plan == null
          || command.pairIndex() != index
          || !first.orderId().equals(command.orderId())
          || !first.batchIdempotencyKey().equals(command.batchIdempotencyKey())
          || !java.util.Objects.equals(first.presentationId(), command.presentationId())
          || !first.actorSubjectId().equals(command.actorSubjectId())
          || !first.actorRole().equals(command.actorRole())
          || !first.inventorySourceWarehouseId().equals(command.inventorySourceWarehouseId())
          || !java.util.Objects.equals(first.reason(), command.reason())
          || !oldIds.add(command.oldRentalItemId())
          || !newIds.add(command.replacementRentalItemId())
          || !order.getWarehouseId().equals(command.warehouseId())
          || order.getVersion() != command.expectedOrderVersion()
          || !command.orderId().equals(plan.orderId())
          || !command.replacementRentalItemId().equals(plan.unitId())) {
        throw new LogisticsConflictException("План перемещения мебели не соответствует замене");
      }
      LogisticsDocument document = replacementDocument(order.getId(), command.oldRentalItemId());
      UUID taskId = null;
      if (!plan.lines().isEmpty()) {
        UUID taskKey =
            UUID.nameUUIDFromBytes(
                ("order-unit-replacement-movement:"
                        + command.orderId()
                        + ":"
                        + command.idempotencyKey())
                    .getBytes(StandardCharsets.UTF_8));
        AdmissionTicket admission =
            warehouseLifecycle.ownedContinuation(
                document == null ? order.getId() : document.getId(),
                taskKey,
                List.of(
                    new AdmissionRequirement(
                        command.inventorySourceWarehouseId(),
                        WarehouseOperationDirection.OUTGOING)));
        EquipmentMovementTaskService.CreateResult movement =
            movementTasks.create(
                command.actorSubjectId(),
                taskKey,
                new CreateEquipmentMovementTaskRequest(
                    command.inventorySourceWarehouseId(),
                    plan.unitNumber(),
                    DEFAULT_PLANNED_DURATION_MINUTES,
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(30),
                    plan.lines().stream().map(ShipmentFurnitureTaskService::toTaskLine).toList()),
                admission);
        taskId = movement.response().id();
        movementTasks.freezeReplacementSources(taskId, plan.lines());
        movementTasks.deferReplacementPreparation(taskId);
      }
      created.add(
          ShipmentFurnitureMovementTask.createReplacement(
              order,
              document,
              command.oldRentalItemId(),
              command.replacementRentalItemId(),
              plan.unitNumber(),
              taskId,
              plan.lines().size(),
              command.reason(),
              command.actorSubjectId(),
              command.actorRole(),
              command.idempotencyKey(),
              command.batchIdempotencyKey(),
              command.pairIndex(),
              command.requestSha256(),
              command.presentationId(),
              command.inventorySourceWarehouseId(),
              OffsetDateTime.now(ZoneOffset.UTC)));
    }
    if (!java.util.Collections.disjoint(oldIds, newIds)) {
      throw new LogisticsConflictException("План перемещения мебели не соответствует замене");
    }
    return List.copyOf(taskLinks.saveAllAndFlush(created));
  }

  /** Loads a scalar durable replacement checkpoint for scheduled recovery. */
  public ReplacementCheckpoint replacementCheckpoint(UUID checkpointId) {
    ShipmentFurnitureMovementTask link =
        taskLinks.findById(checkpointId).orElseThrow(LogisticsNotFoundException::new);
    if (!link.isReplacement()) throw new LogisticsNotFoundException();
    return checkpoint(link);
  }

  /** Loads the complete durable batch in its original pair order. */
  public List<ReplacementCheckpoint> replacementBatch(UUID orderId, UUID batchIdempotencyKey) {
    List<ReplacementCheckpoint> checkpoints =
        taskLinks
            .findAllByOrder_IdAndReplacementBatchIdempotencyKeyOrderByReplacementPairIndexAsc(
                orderId, batchIdempotencyKey)
            .stream()
            .map(ShipmentFurnitureTaskService::checkpoint)
            .toList();
    if (checkpoints.isEmpty()) throw new LogisticsNotFoundException();
    return checkpoints;
  }

  private static ReplacementCheckpoint checkpoint(ShipmentFurnitureMovementTask link) {
    return new ReplacementCheckpoint(
        link.getId(),
        link.getOrder().getId(),
        link.getOrder().getWarehouseId(),
        link.getReplacementInventorySourceWarehouseId(),
        link.getDocument() == null ? null : link.getDocument().getId(),
        link.getOldRentalItemId(),
        link.getRentalItemId(),
        link.getReplacementReason(),
        link.getReplacementActorSubjectId(),
        link.getReplacementActorRole(),
        link.getReplacementIdempotencyKey(),
        link.getReplacementBatchIdempotencyKey(),
        link.getReplacementPairIndex(),
        link.getReplacementRequestSha256(),
        link.getReplacementPresentationId(),
        link.getEquipmentMovementTaskId(),
        link.getReplacementSourceReservationId(),
        link.getReplacementCompletedAt(),
        link.getReplacementRejectedAt());
  }

  public List<UUID> pendingReplacementCheckpointIds() {
    return taskLinks.findPendingReplacementIds();
  }

  public boolean isPendingReplacementMovement(UUID taskId) {
    return taskLinks
        .findByEquipmentMovementTaskId(taskId)
        .filter(ShipmentFurnitureMovementTask::isReplacementPending)
        .isPresent();
  }

  /**
   * Returns the ordinary furniture task that could still mutate an old cabin before replacement.
   */
  public ExistingShipmentFurnitureMovement existingOrdinaryMovement(
      UUID documentId, UUID rentalItemId) {
    if (documentId == null || rentalItemId == null) return null;
    ShipmentFurnitureMovementTask link =
        taskLinks.findByDocument_IdAndRentalItemId(documentId, rentalItemId).orElse(null);
    if (link == null || link.isReplacement() || link.getEquipmentMovementTaskId() == null) {
      return null;
    }
    EquipmentMovementTask task = movementTasks.required(link.getEquipmentMovementTaskId());
    return new ExistingShipmentFurnitureMovement(
        link.getId(), task.getId(), task.getVersion(), task.getState());
  }

  /** Removes only a terminal, released ordinary task link before a fresh replacement plan. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void removeReleasedOrdinaryMovement(
      UUID documentId, UUID rentalItemId, UUID equipmentMovementTaskId) {
    ShipmentFurnitureMovementTask link =
        taskLinks.findByDocumentAndRentalItemForUpdate(documentId, rentalItemId).orElse(null);
    if (link == null) return;
    if (link.isReplacement()
        || !equipmentMovementTaskId.equals(link.getEquipmentMovementTaskId())) {
      throw new LogisticsConflictException("Задание мебели бытовки было изменено параллельно");
    }
    EquipmentMovementTask task = movementTasks.required(equipmentMovementTaskId);
    if (!Set.of(
            EquipmentMovementTaskState.CANCELLED,
            EquipmentMovementTaskState.EXPIRED,
            EquipmentMovementTaskState.CONFLICT)
        .contains(task.getState())) {
      throw new LogisticsConflictException("Резервы старого задания мебели ещё не освобождены");
    }
    taskLinks.delete(link);
    taskLinks.flush();
  }

  /**
   * Resolves the authoritative same-order furniture composition only when an ordinary shipment
   * movement line takes surplus from another active cabin of that order. Stock and unrelated cabin
   * sources intentionally retain the legacy context-free reservation call.
   */
  public OrderMovementReservationContext movementReservationContext(
      UUID equipmentMovementTaskId, UUID sourceRentalItemId) {
    if (equipmentMovementTaskId == null || sourceRentalItemId == null) return null;
    ShipmentFurnitureMovementTask link =
        taskLinks.findByEquipmentMovementTaskId(equipmentMovementTaskId).orElse(null);
    if (link == null) return null;
    boolean replacement = link.isReplacement();
    if (replacement
        && (link.getReplacementCompletedAt() == null
            || link.getReplacementRejectedAt() != null
            || link.getReplacementSourceReservationId() == null
            || !sourceRentalItemId.equals(link.getOldRentalItemId()))) {
      throw new LogisticsConflictException(
          "Replacement movement reservation is not ready for replay");
    }
    if (!replacement && link.getDocument() == null) return null;
    UUID orderId = replacement ? link.getOrder().getId() : link.getDocument().getRentalOrderId();
    UUID targetRentalItemId = link.getRentalItemId();
    if (orderId == null || sourceRentalItemId.equals(targetRentalItemId)) return null;
    RentalOrder order =
        orders
            .findWithClientById(orderId)
            .orElseThrow(
                () -> new LogisticsConflictException("Заказ перемещения мебели не найден"));
    UUID movementWarehouseId =
        replacement
            ? link.getReplacementInventorySourceWarehouseId()
            : order.getWarehouseId();
    if (!replacement) {
      LogisticsDocumentLine shipmentLine =
          documentLines.findAllByDocument_IdOrderByLineNumber(link.getDocument().getId()).stream()
              .filter(line -> targetRentalItemId.equals(line.getAssetId()))
              .findFirst()
              .orElseThrow(
                  () -> new LogisticsConflictException("Строка бытовки отгрузки не найдена"));
      movementWarehouseId = shipmentLine.getInventorySourceWarehouseId();
    }
    List<LogisticsDependencyGateway.OrderUnitReservation> active =
        dependencies.readOrderUnits(orderId);
    if (active == null
        || active.stream()
            .anyMatch(
                unit ->
                    unit == null
                        || !orderId.equals(unit.orderId())
                        || unit.unit() == null
                        || unit.warehouseId() == null
                        || !unit.warehouseId().equals(unit.unit().warehouseId()))) {
      throw new LogisticsConflictException("Склад вернул некорректный состав заказа");
    }
    Set<UUID> activeIds =
        active.stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!activeIds.contains(targetRentalItemId)
        || (!replacement && !activeIds.contains(sourceRentalItemId))) {
      return null;
    }
    UUID requiredMovementWarehouseId = movementWarehouseId;
    if (active.stream()
        .filter(
            unit ->
                targetRentalItemId.equals(unit.unitId())
                    || (!replacement && sourceRentalItemId.equals(unit.unitId())))
        .anyMatch(unit -> !requiredMovementWarehouseId.equals(unit.warehouseId()))) {
      throw new LogisticsConflictException(
          "Перемещение мебели не соответствует складу-источнику отгрузки");
    }
    Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit =
        requirementsByUnit(
            requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
                orderId));
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        activeIds.stream()
            .sorted()
            .map(
                unitId ->
                    new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                        unitId, byUnit.getOrDefault(unitId, List.of())))
            .toList();
    return new OrderMovementReservationContext(
        orderId,
        targetRentalItemId,
        composition,
        replacement ? link.getReplacementSourceReservationId() : null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void rejectReplacement(UUID checkpointId, String code) {
    ShipmentFurnitureMovementTask link =
        taskLinks.findForUpdate(checkpointId).orElseThrow(LogisticsNotFoundException::new);
    if (!link.isReplacementPending()) return;
    if (link.getEquipmentMovementTaskId() != null) {
      movementTasks.rejectReplacementPreparation(link.getEquipmentMovementTaskId(), code);
    }
    link.rejectReplacement(OffsetDateTime.now(ZoneOffset.UTC));
    taskLinks.saveAndFlush(link);
  }

  /** Rejects every prepared pair after one permanent atomic asset-batch rejection. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void rejectReplacementBatch(UUID orderId, UUID batchIdempotencyKey, String code) {
    List<ShipmentFurnitureMovementTask> links =
        taskLinks.findReplacementBatchForUpdate(orderId, batchIdempotencyKey);
    if (links.isEmpty()) throw new LogisticsNotFoundException();
    OffsetDateTime rejectedAt = OffsetDateTime.now(ZoneOffset.UTC);
    for (ShipmentFurnitureMovementTask link : links) {
      if (!link.isReplacementPending()) continue;
      if (link.getEquipmentMovementTaskId() != null) {
        movementTasks.rejectReplacementPreparation(link.getEquipmentMovementTaskId(), code);
      }
      link.rejectReplacement(rejectedAt);
    }
    taskLinks.saveAllAndFlush(links);
  }

  private LogisticsDocument replacementDocument(UUID orderId, UUID oldRentalItemId) {
    for (LogisticsDocument document : documents.findAllByRentalOrderIdForUpdate(orderId)) {
      if (document.getDocumentType() != LogisticsDocumentType.SHIPMENT
          || document.getState() == LogisticsDocumentState.CANCELLED
          || document.getState() == LogisticsDocumentState.SHIPPED
          || document.getState() == LogisticsDocumentState.COMPLETED) {
        continue;
      }
      boolean contains =
          documentLines.findAllByDocument_IdOrderByLineNumber(document.getId()).stream()
              .anyMatch(line -> oldRentalItemId.equals(line.getAssetId()));
      if (contains) return document;
    }
    return null;
  }

  /**
   * Reuses completed order-level replacement movements when their replacement cabins are first
   * assigned to a shipment. Checkpoints without a physical movement task intentionally remain
   * order-only so an ordinary stock-to-cabin task can still be planned when needed.
   */
  @Transactional
  public void attachReplacementMovementsToShipment(
      LogisticsDocument shipment, List<UUID> rentalItemIds) {
    if (shipment == null
        || shipment.getId() == null
        || shipment.getDocumentType() != LogisticsDocumentType.SHIPMENT
        || shipment.getRentalOrderId() == null
        || rentalItemIds == null
        || rentalItemIds.isEmpty()) {
      throw new IllegalArgumentException("Shipment replacement attachment is invalid");
    }
    Set<UUID> selected = Set.copyOf(rentalItemIds);
    List<ShipmentFurnitureMovementTask> attachable =
        taskLinks.findAttachableReplacementMovementsForUpdate(
            shipment.getRentalOrderId(), selected);
    Set<UUID> attachedUnits = new HashSet<>();
    for (ShipmentFurnitureMovementTask link : attachable) {
      if (!attachedUnits.add(link.getRentalItemId())) {
        throw new IllegalStateException("Duplicate replacement movement for shipment cabin");
      }
      link.attachToShipment(shipment);
    }
    if (!attachable.isEmpty()) taskLinks.saveAllAndFlush(attachable);
  }

  /**
   * Returns the current physical-readiness gate for a shipment. The plan is recalculated from the
   * asset service so completion means the cabin really matches the saved order, not merely that a
   * browser once created a task.
   */
  public ShipmentFurnitureReadinessView readiness(UUID documentId) {
    LogisticsDocument shipment = requiredShipment(documentId);
    if (shipment.getRentalOrderId() == null) {
      return new ShipmentFurnitureReadinessView(
          shipment.getId(),
          shipment.getVersion(),
          ShipmentFurnitureReadinessState.NOT_REQUIRED,
          List.of());
    }

    RentalOrder order = requiredSavedOrder(shipment);
    List<RentalOrderEquipmentRequirement> desiredRows =
        requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            order.getId());
    Map<UUID, Long> orderRequirements = aggregateRequirements(desiredRows);
    List<LogisticsDocumentLine> lines = linesRequired(shipment.getId());
    List<ShipmentFurnitureMovementTask> links =
        taskLinks.findAllByDocument_IdOrderByUnitNumberAsc(shipment.getId()).stream()
            .filter(link -> link.getEquipmentMovementTaskId() != null)
            .toList();
    if (orderRequirements.isEmpty() && links.isEmpty()) {
      return new ShipmentFurnitureReadinessView(
          shipment.getId(),
          shipment.getVersion(),
          ShipmentFurnitureReadinessState.NOT_REQUIRED,
          List.of());
    }
    Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit =
        requirementsByUnit(desiredRows);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        activeOrderComposition(order, lines, byUnit);
    Map<UUID, ShipmentFurnitureMovementTask> existingByUnit = existingByUnit(links);
    Map<UUID, EquipmentMovementTask> tasksByUnit = new LinkedHashMap<>();
    for (ShipmentFurnitureMovementTask link : links) {
      EquipmentMovementTask task = movementTasks.required(link.getEquipmentMovementTaskId());
      tasksByUnit.put(link.getRentalItemId(), task);
    }

    Set<UUID> lineUnitIds = new HashSet<>();
    List<ShipmentFurnitureTaskStatusView> taskViews = new ArrayList<>();
    boolean requiresTaskCreation = false;
    boolean awaitingTaskCompletion = false;
    boolean blocked = false;
    for (LogisticsDocumentLine line : lines) {
      UUID unitId = line.getAssetId();
      lineUnitIds.add(unitId);
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan =
          planFor(
              shipment,
              order,
              line.getInventorySourceWarehouseId(),
              unitId,
              byUnit,
              composition);
      ShipmentFurnitureMovementTask link = existingByUnit.get(unitId);
      if (link == null) {
        requiresTaskCreation |= !plan.lines().isEmpty();
        taskViews.add(
            new ShipmentFurnitureTaskStatusView(
                unitId,
                plan.unitNumber(),
                null,
                null,
                null,
                null,
                0,
                false,
                false,
                plan.lines().isEmpty()));
        continue;
      }

      EquipmentMovementTaskState state = tasksByUnit.get(unitId).getState();
      if (state == EquipmentMovementTaskState.COMPLETED) {
        blocked |= !plan.lines().isEmpty();
      } else if (state.isTerminal()) {
        blocked = true;
      } else {
        awaitingTaskCompletion = true;
      }
      taskViews.add(toStatusView(link, tasksByUnit.get(unitId), plan.lines().isEmpty()));
    }
    blocked |= existingByUnit.keySet().stream().anyMatch(unitId -> !lineUnitIds.contains(unitId));

    ShipmentFurnitureReadinessState state =
        blocked
            ? ShipmentFurnitureReadinessState.BLOCKED
            : requiresTaskCreation
                ? ShipmentFurnitureReadinessState.REQUIRES_TASK_CREATION
                : awaitingTaskCompletion
                    ? ShipmentFurnitureReadinessState.AWAITING_TASK_COMPLETION
                    : ShipmentFurnitureReadinessState.READY;
    return new ShipmentFurnitureReadinessView(
        shipment.getId(), shipment.getVersion(), state, List.copyOf(taskViews));
  }

  /** Enforces the furniture gate for every command that starts or confirms a shipment. */
  public void requireShipmentFurnitureReady(UUID documentId) {
    ShipmentFurnitureReadinessView readiness = readiness(documentId);
    switch (readiness.state()) {
      case NOT_REQUIRED, READY -> {
        return;
      }
      case REQUIRES_TASK_CREATION ->
          throw new LogisticsConflictException(
              "Перед отгрузкой требуется создать задание на перемещение мебели");
      case AWAITING_TASK_COMPLETION ->
          throw new LogisticsConflictException(
              "Перед отгрузкой требуется закрыть задание на перемещение мебели");
      case BLOCKED ->
          throw new LogisticsConflictException(
              "Задание на перемещение мебели требует внимания перед отгрузкой");
    }
  }

  @Transactional
  public ShipmentFurnitureTaskResult createForShipment(
      UUID actorSubjectId, UUID idempotencyKey, UUID documentId, long expectedVersion) {
    if (actorSubjectId == null || idempotencyKey == null || expectedVersion < 0) {
      throw new IllegalArgumentException("Shipment furniture task command is invalid");
    }
    LogisticsDocument reference = requiredShipment(documentId);
    if (reference.getRentalOrderId() == null) {
      throw new LogisticsConflictException(
          "Мебель можно добавить только в отгрузку из сохранённого заказа");
    }
    RentalOrder order =
        orders
            .findForUpdate(reference.getRentalOrderId())
            .orElseThrow(() -> new LogisticsConflictException("Заказ отгрузки не найден"));
    LogisticsDocument shipment =
        documents.findForUpdate(documentId).orElseThrow(LogisticsNotFoundException::new);
    if (shipment.getDocumentType() != LogisticsDocumentType.SHIPMENT) {
      throw new LogisticsNotFoundException();
    }
    if (shipment.getVersion() != expectedVersion) {
      throw new LogisticsConflictException("Отгрузка была изменена параллельно");
    }
    if (shipment.getState() != LogisticsDocumentState.DRAFT
        || !order.getId().equals(shipment.getRentalOrderId())) {
      throw new LogisticsConflictException(
          "Мебель можно добавить только в черновик отгрузки из сохранённого заказа");
    }
    if (order.getStatus() != RentalOrderStatus.SAVED
        || !shipment.getWarehouseId().equals(order.getWarehouseId())) {
      throw new LogisticsConflictException("Сохранённый заказ отгрузки больше не актуален");
    }

    requirePayment(order);

    List<RentalOrderEquipmentRequirement> desiredRows =
        requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            order.getId());
    Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit =
        requirementsByUnit(desiredRows);
    List<LogisticsDocumentLine> shipmentLines = linesRequired(shipment.getId());
    Set<UUID> shipmentUnitIds =
        shipmentLines.stream()
            .map(LogisticsDocumentLine::getAssetId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (taskLinks
        .existsByOrder_IdAndOldRentalItemIdInAndReplacementCompletedAtIsNullAndReplacementRejectedAtIsNull(
            order.getId(), shipmentUnitIds)) {
      throw new LogisticsConflictException(
          "Замена бытовки ещё не завершена; задание мебели для старой бытовки недоступно");
    }
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        activeOrderComposition(order, shipmentLines, byUnit);
    Map<UUID, ShipmentFurnitureMovementTask> existingByUnit =
        existingByUnit(
            taskLinks.findAllByDocument_IdOrderByUnitNumberAsc(shipment.getId()).stream()
                .filter(link -> link.getEquipmentMovementTaskId() != null)
                .toList());

    List<ShipmentFurnitureTaskView> result = new ArrayList<>();
    for (LogisticsDocumentLine line : shipmentLines) {
      UUID unitId = line.getAssetId();
      ShipmentFurnitureMovementTask existing = existingByUnit.get(unitId);
      if (existing != null) {
        result.add(mapper.toView(existing));
        continue;
      }
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan =
          planFor(
              shipment,
              order,
              line.getInventorySourceWarehouseId(),
              unitId,
              byUnit,
              composition);
      if (plan.lines().isEmpty()) {
        result.add(new ShipmentFurnitureTaskView(unitId, plan.unitNumber(), null, 0));
        continue;
      }
      UUID taskKey =
          UUID.nameUUIDFromBytes(
              ("shipment-furniture-task-v1:"
                      + shipment.getId()
                      + ":"
                      + unitId
                      + ":"
                      + idempotencyKey)
                  .getBytes(StandardCharsets.UTF_8));
      AdmissionTicket admission =
          warehouseLifecycle.ownedContinuation(
              shipment.getId(),
              taskKey,
              List.of(
                  new AdmissionRequirement(
                      line.getInventorySourceWarehouseId(),
                      WarehouseOperationDirection.OUTGOING)));
      EquipmentMovementTaskService.CreateResult task =
          movementTasks.create(
              actorSubjectId,
              taskKey,
              new CreateEquipmentMovementTaskRequest(
                  line.getInventorySourceWarehouseId(),
                  plan.unitNumber(),
                  DEFAULT_PLANNED_DURATION_MINUTES,
                  OffsetDateTime.now(ZoneOffset.UTC).plusDays(30),
                  plan.lines().stream().map(ShipmentFurnitureTaskService::toTaskLine).toList()),
              admission);
      ShipmentFurnitureMovementTask link =
          taskLinks.saveAndFlush(
              ShipmentFurnitureMovementTask.create(
                  shipment, unitId, plan.unitNumber(), task.response().id(), plan.lines().size()));
      result.add(mapper.toView(link));
    }
    return new ShipmentFurnitureTaskResult(
        shipment.getId(), shipment.getVersion(), List.copyOf(result));
  }

  private LogisticsDocument requiredShipment(UUID documentId) {
    LogisticsDocument shipment =
        documents.findById(documentId).orElseThrow(LogisticsNotFoundException::new);
    if (shipment.getDocumentType() != LogisticsDocumentType.SHIPMENT) {
      throw new LogisticsNotFoundException();
    }
    return shipment;
  }

  private RentalOrder requiredSavedOrder(LogisticsDocument shipment) {
    RentalOrder order =
        orders
            .findWithClientById(shipment.getRentalOrderId())
            .orElseThrow(() -> new LogisticsConflictException("Заказ отгрузки не найден"));
    if (order.getStatus() != RentalOrderStatus.SAVED
        || !shipment.getWarehouseId().equals(order.getWarehouseId())) {
      throw new LogisticsConflictException("Сохранённый заказ отгрузки больше не актуален");
    }
    requirePayment(order);
    return order;
  }

  private static void requirePayment(RentalOrder order) {
    if (!RentalOrderPaymentState.allowsFulfillment(order.getPaymentState())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_PAYMENT_REQUIRED",
          "Сначала подтвердите оплату бытовок и мебели");
    }
  }

  private static Map<UUID, ShipmentFurnitureMovementTask> existingByUnit(
      List<ShipmentFurnitureMovementTask> links) {
    Map<UUID, ShipmentFurnitureMovementTask> values = new LinkedHashMap<>();
    for (ShipmentFurnitureMovementTask link : links) {
      if (values.putIfAbsent(link.getRentalItemId(), link) != null) {
        throw new IllegalStateException("Duplicate shipment furniture task link");
      }
    }
    return values;
  }

  private LogisticsDependencyGateway.OrderFurnitureMovementPlan planFor(
      LogisticsDocument shipment,
      RentalOrder order,
      UUID inventorySourceWarehouseId,
      UUID unitId,
      Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit,
      List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition) {
    LogisticsDependencyGateway.OrderFurnitureMovementPlan plan =
        dependencies.planOrderFurnitureMovements(
            order.getId(),
            inventorySourceWarehouseId,
            unitId,
            null,
            byUnit.getOrDefault(unitId, List.of()),
            composition);
    validatePlan(plan, order.getId(), unitId, inventorySourceWarehouseId);
    return plan;
  }

  private static ShipmentFurnitureTaskStatusView toStatusView(
      ShipmentFurnitureMovementTask link, EquipmentMovementTask task, boolean contentReady) {
    return new ShipmentFurnitureTaskStatusView(
        link.getRentalItemId(),
        link.getUnitNumber(),
        link.getEquipmentMovementTaskId(),
        task.getExternalTaskId(),
        task.getTaskBoardTaskId(),
        task.getState(),
        link.getLineCount(),
        true,
        task.getState() == EquipmentMovementTaskState.COMPLETED,
        contentReady);
  }

  private List<LogisticsDocumentLine> linesRequired(UUID shipmentId) {
    List<LogisticsDocumentLine> lines =
        documentLines.findAllByDocument_IdOrderByLineNumber(shipmentId);
    if (lines.isEmpty()) {
      throw new LogisticsConflictException("В отгрузке нет бытовок из заказа");
    }
    return lines;
  }

  private static Map<UUID, Long> aggregateRequirements(List<RentalOrderEquipmentRequirement> rows) {
    Map<UUID, Long> values = new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement row : rows) {
      if (row.getQuantity() > 0) {
        values.merge(row.getEquipmentId(), row.getQuantity(), Math::addExact);
      }
    }
    return values;
  }

  private static Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>>
      requirementsByUnit(List<RentalOrderEquipmentRequirement> rows) {
    Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> values =
        new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement row : rows) {
      if (row.getQuantity() > 0) {
        values
            .computeIfAbsent(row.getRentalItemId(), ignored -> new ArrayList<>())
            .add(
                new LogisticsDependencyGateway.OrderEquipmentRequirement(
                    row.getEquipmentId(), row.getQuantity()));
      }
    }
    values.replaceAll(
        (ignored, unitRows) ->
            unitRows.stream()
                .sorted(
                    Comparator.comparing(
                        LogisticsDependencyGateway.OrderEquipmentRequirement::equipmentId))
                .toList());
    return values;
  }

  private static List<LogisticsDependencyGateway.OrderEquipmentRequirement> dependencyRequirements(
      Map<UUID, Long> requirements) {
    return requirements.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                new LogisticsDependencyGateway.OrderEquipmentRequirement(
                    entry.getKey(), entry.getValue()))
        .toList();
  }

  private List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> activeOrderComposition(
      RentalOrder order,
      List<LogisticsDocumentLine> shipmentLines,
      Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit) {
    List<LogisticsDependencyGateway.OrderUnitReservation> active =
        dependencies.readOrderUnits(order.getId());
    if (active == null || active.isEmpty()) {
      throw new LogisticsConflictException("Склад не подтвердил состав заказа для мебели");
    }
    Map<UUID, LogisticsDependencyGateway.OrderUnitReservation> activeById =
        new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderUnitReservation reservation : active) {
      if (reservation == null
          || reservation.unit() == null
          || reservation.unitId() == null
          || !reservation.unitId().equals(reservation.unit().id())
          || !order.getId().equals(reservation.orderId())
          || reservation.warehouseId() == null
          || !reservation.warehouseId().equals(reservation.unit().warehouseId())
          || !"ACTIVE".equals(reservation.state())
          || activeById.putIfAbsent(reservation.unitId(), reservation) != null) {
        throw new LogisticsConflictException("Склад вернул некорректный состав заказа для мебели");
      }
    }
    for (LogisticsDocumentLine line : shipmentLines) {
      LogisticsDependencyGateway.OrderUnitReservation reservation = activeById.get(line.getAssetId());
      if (reservation == null
          || !line.getInventorySourceWarehouseId().equals(reservation.warehouseId())) {
        throw new LogisticsConflictException("Состав отгрузки больше не входит в активный заказ");
      }
    }
    return activeById.keySet().stream()
        .sorted()
        .map(
            unitId ->
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                    unitId, byUnit.getOrDefault(unitId, List.of())))
        .toList();
  }

  private static void validatePlan(
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan,
      UUID orderId,
      UUID unitId,
      UUID warehouseId) {
    if (plan == null
        || !orderId.equals(plan.orderId())
        || !unitId.equals(plan.unitId())
        || plan.unitNumber() == null
        || plan.unitNumber().isBlank()
        || plan.lines() == null) {
      throw new LogisticsConflictException("Складской сервис вернул некорректный план мебели");
    }
    for (LogisticsDependencyGateway.OrderFurnitureMovementPlanLine line : plan.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.equipmentName() == null
          || line.sourceWarehouseId() == null
          || !warehouseId.equals(line.sourceWarehouseId())
          || line.sourceLocationKind() == null
          || line.expectedSourceBalanceVersion() < 0
          || line.targetWarehouseId() == null
          || !warehouseId.equals(line.targetWarehouseId())
          || line.targetLocationKind() == null
          || line.quantity() < 1) {
        throw new LogisticsConflictException(
            "Складской сервис вернул некорректную строку плана мебели");
      }
    }
  }

  private static EquipmentMovementLineRequest toTaskLine(
      LogisticsDependencyGateway.OrderFurnitureMovementPlanLine line) {
    return new EquipmentMovementLineRequest(
        line.equipmentId(),
        line.sourceRentalItemId(),
        location(line.sourceLocationKind()),
        line.expectedSourceBalanceVersion(),
        line.targetRentalItemId(),
        location(line.targetLocationKind()),
        line.quantity());
  }

  /** Asset order context required to reserve a same-order cabin-surplus movement line. */
  public record OrderMovementReservationContext(
      UUID orderId,
      UUID targetRentalItemId,
      List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> units,
      UUID replacementSourceReservationId) {
    public OrderMovementReservationContext(
        UUID orderId,
        UUID targetRentalItemId,
        List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> units) {
      this(orderId, targetRentalItemId, units, null);
    }
  }

  private static EquipmentMovementLocationKind location(String value) {
    try {
      return EquipmentMovementLocationKind.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new LogisticsConflictException(
          "Складской сервис вернул неизвестный тип размещения мебели");
    }
  }

  /** Frozen local intent needed to recover one asset-side cabin replacement idempotently. */
  public record ReplacementCheckpointCommand(
      UUID orderId,
      long expectedOrderVersion,
      UUID warehouseId,
      UUID inventorySourceWarehouseId,
      UUID oldRentalItemId,
      UUID replacementRentalItemId,
      String reason,
      UUID actorSubjectId,
      String actorRole,
      UUID idempotencyKey,
      UUID batchIdempotencyKey,
      int pairIndex,
      String requestSha256,
      UUID presentationId) {
    public ReplacementCheckpointCommand(
        UUID orderId,
        long expectedOrderVersion,
        UUID warehouseId,
        UUID oldRentalItemId,
        UUID replacementRentalItemId,
        String reason,
        UUID actorSubjectId,
        String actorRole,
        UUID idempotencyKey,
        UUID batchIdempotencyKey,
        int pairIndex,
        String requestSha256,
        UUID presentationId) {
      this(
          orderId,
          expectedOrderVersion,
          warehouseId,
          warehouseId,
          oldRentalItemId,
          replacementRentalItemId,
          reason,
          actorSubjectId,
          actorRole,
          idempotencyKey,
          batchIdempotencyKey,
          pairIndex,
          requestSha256,
          presentationId);
    }
  }

  /** One ordered pair and its already validated direct asset movement plan. */
  public record ReplacementPreparation(
      ReplacementCheckpointCommand command,
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan) {}

  /** Scalar replacement intent safe to use outside the checkpoint read transaction. */
  public record ReplacementCheckpoint(
      UUID id,
      UUID orderId,
      UUID warehouseId,
      UUID inventorySourceWarehouseId,
      UUID documentId,
      UUID oldRentalItemId,
      UUID replacementRentalItemId,
      String reason,
      UUID actorSubjectId,
      String actorRole,
      UUID idempotencyKey,
      UUID batchIdempotencyKey,
      int pairIndex,
      String requestSha256,
      UUID presentationId,
      UUID equipmentMovementTaskId,
      UUID replacementSourceReservationId,
      OffsetDateTime completedAt,
      OffsetDateTime rejectedAt) {
    public ReplacementCheckpoint(
        UUID id,
        UUID orderId,
        UUID warehouseId,
        UUID documentId,
        UUID oldRentalItemId,
        UUID replacementRentalItemId,
        String reason,
        UUID actorSubjectId,
        String actorRole,
        UUID idempotencyKey,
        UUID batchIdempotencyKey,
        int pairIndex,
        String requestSha256,
        UUID presentationId,
        UUID equipmentMovementTaskId,
        UUID replacementSourceReservationId,
        OffsetDateTime completedAt,
        OffsetDateTime rejectedAt) {
      this(
          id,
          orderId,
          warehouseId,
          warehouseId,
          documentId,
          oldRentalItemId,
          replacementRentalItemId,
          reason,
          actorSubjectId,
          actorRole,
          idempotencyKey,
          batchIdempotencyKey,
          pairIndex,
          requestSha256,
          presentationId,
          equipmentMovementTaskId,
          replacementSourceReservationId,
          completedAt,
          rejectedAt);
    }

    public boolean pending() {
      return completedAt == null && rejectedAt == null;
    }
  }

  /**
   * Immutable task state used by cabin-replacement preflight outside the repository transaction.
   */
  public record ExistingShipmentFurnitureMovement(
      UUID linkId,
      UUID equipmentMovementTaskId,
      long taskVersion,
      EquipmentMovementTaskState state) {}
}
