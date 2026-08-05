package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureTaskResult;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessState;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessView;
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
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts saved order intent into worker tasks only when a shipment operator
 * explicitly asks to reconcile a cabin. The asset service computes the delta.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipmentFurnitureTaskService {
  private static final int DEFAULT_PLANNED_DURATION_MINUTES = 60;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository documentLines;
  private final RentalOrderRepository orders;
  private final RentalOrderEquipmentRequirementRepository requirements;
  private final ShipmentFurnitureMovementTaskRepository taskLinks;
  private final LogisticsDependencyGateway dependencies;
  private final EquipmentMovementTaskService movementTasks;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final ShipmentFurnitureTaskResponseMapper mapper;

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
        requirements
            .findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(order.getId());
    Map<UUID, Long> orderRequirements = aggregateRequirements(desiredRows);
    List<LogisticsDocumentLine> lines = linesRequired(shipment.getId());
    List<ShipmentFurnitureMovementTask> links =
        taskLinks.findAllByDocument_IdOrderByUnitNumberAsc(shipment.getId());
    if (orderRequirements.isEmpty() && links.isEmpty()) {
      return new ShipmentFurnitureReadinessView(
          shipment.getId(),
          shipment.getVersion(),
          ShipmentFurnitureReadinessState.NOT_REQUIRED,
          List.of());
    }
    Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit =
        requirementsByUnit(desiredRows);
    Map<UUID, ShipmentFurnitureMovementTask> existingByUnit = existingByUnit(links);
    Map<UUID, EquipmentMovementTask> tasksByUnit = new LinkedHashMap<>();
    List<ShipmentFurnitureTaskStatusView> taskViews = new ArrayList<>();
    for (ShipmentFurnitureMovementTask link : links) {
      EquipmentMovementTask task = movementTasks.required(link.getEquipmentMovementTaskId());
      tasksByUnit.put(link.getRentalItemId(), task);
      taskViews.add(toStatusView(link, task));
    }

    Set<UUID> lineUnitIds = new HashSet<>();
    boolean requiresTaskCreation = false;
    boolean awaitingTaskCompletion = false;
    boolean blocked = false;
    for (LogisticsDocumentLine line : lines) {
      UUID unitId = line.getAssetId();
      lineUnitIds.add(unitId);
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan =
          planFor(shipment, order, unitId, byUnit, orderRequirements);
      ShipmentFurnitureMovementTask link = existingByUnit.get(unitId);
      if (link == null) {
        requiresTaskCreation |= !plan.lines().isEmpty();
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
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID documentId,
      long expectedVersion) {
    if (actorSubjectId == null || idempotencyKey == null || expectedVersion < 0) {
      throw new IllegalArgumentException("Shipment furniture task command is invalid");
    }
    LogisticsDocument shipment =
        documents
            .findForUpdate(documentId)
            .orElseThrow(LogisticsNotFoundException::new);
    if (shipment.getDocumentType() != LogisticsDocumentType.SHIPMENT) {
      throw new LogisticsNotFoundException();
    }
    if (shipment.getVersion() != expectedVersion) {
      throw new LogisticsConflictException("Отгрузка была изменена параллельно");
    }
    if (shipment.getState() != LogisticsDocumentState.DRAFT
        || shipment.getRentalOrderId() == null) {
      throw new LogisticsConflictException(
          "Мебель можно добавить только в черновик отгрузки из сохранённого заказа");
    }
    RentalOrder order = requiredSavedOrder(shipment);

    List<RentalOrderEquipmentRequirement> desiredRows =
        requirements
            .findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(order.getId());
    Map<UUID, Long> orderRequirements = aggregateRequirements(desiredRows);
    Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit =
        requirementsByUnit(desiredRows);
    Map<UUID, ShipmentFurnitureMovementTask> existingByUnit =
        existingByUnit(taskLinks.findAllByDocument_IdOrderByUnitNumberAsc(shipment.getId()));

    List<ShipmentFurnitureTaskView> result = new ArrayList<>();
    for (LogisticsDocumentLine line : linesRequired(shipment.getId())) {
      UUID unitId = line.getAssetId();
      ShipmentFurnitureMovementTask existing = existingByUnit.get(unitId);
      if (existing != null) {
        result.add(mapper.toView(existing));
        continue;
      }
      LogisticsDependencyGateway.OrderFurnitureMovementPlan plan =
          planFor(shipment, order, unitId, byUnit, orderRequirements);
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
                      shipment.getWarehouseId(), WarehouseOperationDirection.OUTGOING)));
      EquipmentMovementTaskService.CreateResult task =
          movementTasks.create(
              actorSubjectId,
              taskKey,
              new CreateEquipmentMovementTaskRequest(
                  shipment.getWarehouseId(),
                  plan.unitNumber(),
                  DEFAULT_PLANNED_DURATION_MINUTES,
                  OffsetDateTime.now(ZoneOffset.UTC).plusDays(30),
                  plan.lines().stream().map(ShipmentFurnitureTaskService::toTaskLine).toList()),
              admission);
      ShipmentFurnitureMovementTask link =
          taskLinks.saveAndFlush(
              ShipmentFurnitureMovementTask.create(
                  shipment,
                  unitId,
                  plan.unitNumber(),
                  task.response().id(),
                  plan.lines().size()));
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
    return order;
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
      UUID unitId,
      Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit,
      Map<UUID, Long> orderRequirements) {
    LogisticsDependencyGateway.OrderFurnitureMovementPlan plan =
        dependencies.planOrderFurnitureMovements(
            order.getId(),
            shipment.getWarehouseId(),
            unitId,
            byUnit.getOrDefault(unitId, List.of()),
            dependencyRequirements(orderRequirements));
    validatePlan(plan, order.getId(), unitId, shipment.getWarehouseId());
    return plan;
  }

  private static ShipmentFurnitureTaskStatusView toStatusView(
      ShipmentFurnitureMovementTask link, EquipmentMovementTask task) {
    return new ShipmentFurnitureTaskStatusView(
        link.getRentalItemId(),
        link.getUnitNumber(),
        link.getEquipmentMovementTaskId(),
        task.getExternalTaskId(),
        task.getTaskBoardTaskId(),
        task.getState(),
        link.getLineCount());
  }

  private List<LogisticsDocumentLine> linesRequired(UUID shipmentId) {
    List<LogisticsDocumentLine> lines = documentLines.findAllByDocument_IdOrderByLineNumber(shipmentId);
    if (lines.isEmpty()) {
      throw new LogisticsConflictException("В отгрузке нет бытовок из заказа");
    }
    return lines;
  }

  private static Map<UUID, Long> aggregateRequirements(
      List<RentalOrderEquipmentRequirement> rows) {
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
                .sorted(Comparator.comparing(LogisticsDependencyGateway.OrderEquipmentRequirement::equipmentId))
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
        throw new LogisticsConflictException("Складской сервис вернул некорректную строку плана мебели");
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

  private static EquipmentMovementLocationKind location(String value) {
    try {
      return EquipmentMovementLocationKind.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new LogisticsConflictException("Складской сервис вернул неизвестный тип размещения мебели");
    }
  }
}
