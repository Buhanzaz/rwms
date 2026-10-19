package dev.buhanzaz.rwms.logistics.order.service;

import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningReplacementOutcome.APPLIED;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningReplacementOutcome.REPLAYED;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverBoardTask;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskAudience;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplacementResult;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplacementSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplacementTask;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplacementTaskResult;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentReplacementRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverAudienceMode;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningReplacementOutcome;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningAssignmentsRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningAssignmentsResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacedPlanningAssignment;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacedPlanningDriverShift;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningAssignmentReplacementReceipt;
import dev.buhanzaz.rwms.logistics.planning.repository.PlanningAssignmentReplacementReceiptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns same-lineage replacement of one complete published planner revision. Local task rows remain
 * locked while task-board applies its all-or-nothing batch. If the remote transaction commits and
 * the local transaction later rolls back, an exact retry receives task-board's durable replay and
 * converges the local projections without recreating shipments.
 */
@Service
public class PlanningAssignmentReplacementService {
  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDocumentRepository documents;
  private final RentalOrderRepository orders;
  private final PlanningAssignmentReplacementReceiptRepository receipts;
  private final RentalOrderPlanningIntegrationService planning;
  private final LogisticsDependencyGateway dependencies;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Creates the owner service with a replaceable UTC clock for deterministic receipt tests. */
  public PlanningAssignmentReplacementService(
      DriverLogisticsTaskRepository tasks,
      LogisticsDocumentRepository documents,
      RentalOrderRepository orders,
      PlanningAssignmentReplacementReceiptRepository receipts,
      RentalOrderPlanningIntegrationService planning,
      LogisticsDependencyGateway dependencies,
      ObjectMapper objectMapper,
      ObjectProvider<Clock> clocks) {
    this.tasks = tasks;
    this.documents = documents;
    this.orders = orders;
    this.receipts = receipts;
    this.planning = planning;
    this.dependencies = dependencies;
    this.objectMapper = objectMapper;
    this.clock = clocks.getIfAvailable(Clock::systemUTC);
  }

  /**
   * Applies one complete pre-start replacement under local, order, task-board, source-plan, and
   * idempotency fences. No order, shipment, delivery date, or customer slot is created or changed.
   */
  @Transactional
  public ReplacePlanningAssignmentsResponse replace(
      UUID sourcePlanId, UUID idempotencyKey, ReplacePlanningAssignmentsRequest request) {
    requireCommand(sourcePlanId, idempotencyKey, request);
    String fingerprint = fingerprint(sourcePlanId, request);
    PlanningAssignmentReplacementReceipt receipt =
        receipts.findForUpdate(idempotencyKey).orElse(null);
    if (receipt != null) return replay(receipt, sourcePlanId, request, fingerprint);

    List<DriverLogisticsTask> membership = tasks.findAllForUpdateBySourcePlanId(sourcePlanId);
    if (membership.isEmpty()) {
      throw conflict(
          "PLANNING_REPLACEMENT_LINEAGE_MISSING",
          "Опубликованный план не содержит подтверждённой истории заданий");
    }
    receipt = receipts.findForUpdate(idempotencyKey).orElse(null);
    if (receipt != null) return replay(receipt, sourcePlanId, request, fingerprint);

    Map<UUID, PlanningAssignmentReplacementRequest> requested = requestedByExternalId(request);
    requireCompleteMembership(sourcePlanId, request, membership, requested);
    Map<UUID, LogisticsDocument> documentsById = lockDocuments(request);
    Map<UUID, RentalOrder> ordersById = lockOrders(request);
    List<LogisticsDependencyGateway.PlanningReplacementShift> shiftSnapshots =
        planning.validateAndSnapshotReplacement(sourcePlanId, request);

    Map<UUID, DriverBoardTask> currentBoardTasks = new LinkedHashMap<>();
    for (DriverLogisticsTask task : membership) {
      PlanningAssignmentReplacementRequest item = requested.get(task.getExternalTaskId());
      requireLocalAssignment(task, item, documentsById, ordersById, request);
      DriverBoardTask boardTask = dependencies.readDriverTask(task.getExternalTaskId());
      requireBoardTask(task, boardTask, item);
      currentBoardTasks.put(task.getExternalTaskId(), boardTask);
    }

    PlanningReplacementSnapshot snapshot =
        new PlanningReplacementSnapshot(
            request.warehouseId(),
            request.date(),
            request.expectedSourcePlanVersion(),
            request.replacementPlanVersion(),
            request.assignments().stream()
                .map(
                    item -> {
                      DriverLogisticsTask task = taskFor(membership, item.externalTaskId());
                      DriverBoardTask boardTask = currentBoardTasks.get(item.externalTaskId());
                      return new PlanningReplacementTask(
                          item.externalTaskId(),
                          task.getId(),
                          item.serviceWarehouseId(),
                          item.scheduledDate(),
                          boardTask.taskVersion(),
                          boardTask.entryVersion(),
                          item.targetQueuePosition(),
                          audience(item));
                    })
                .toList(),
            shiftSnapshots);
    PlanningReplacementResult remote =
        dependencies.replacePlanningAssignments(sourcePlanId, idempotencyKey, snapshot);
    Map<UUID, PlanningReplacementTaskResult> remoteTasks =
        requireRemoteResult(sourcePlanId, request, remote, requested.keySet(), shiftSnapshots);

    for (DriverLogisticsTask task : membership) {
      PlanningAssignmentReplacementRequest item = requested.get(task.getExternalTaskId());
      PlanningReplacementTaskResult boardResult = remoteTasks.get(task.getExternalTaskId());
      DriverBoardTask before = currentBoardTasks.get(task.getExternalTaskId());
      if (!before.entryId().equals(boardResult.entryId())
          || boardResult.queuePosition() != item.targetQueuePosition()) {
        throw conflict(
            "PLANNING_REPLACEMENT_REMOTE_DIVERGENCE",
            "Доска водителей вернула другой состав или порядок плана");
      }
      task.applyPlannerReplacement(
          item.expectedTaskVersion(),
          sourcePlanId,
          request.expectedSourcePlanVersion(),
          request.replacementPlanVersion(),
          domainAudience(item.driverAudienceMode()),
          item.driverWorkerId(),
          item.driverName(),
          item.provisionalEta(),
          boardResult.taskVersion(),
          boardResult.entryId());
    }
    tasks.saveAllAndFlush(membership);

    ReplacePlanningAssignmentsResponse response =
        response(APPLIED, sourcePlanId, request, membership, remoteTasks, remote);
    receipts.saveAndFlush(
        PlanningAssignmentReplacementReceipt.create(
            idempotencyKey,
            sourcePlanId,
            request.expectedSourcePlanVersion(),
            request.replacementPlanVersion(),
            request.warehouseId(),
            request.date(),
            fingerprint,
            encode(response),
            OffsetDateTime.now(clock)));
    return response;
  }

  private static void requireCommand(
      UUID sourcePlanId, UUID idempotencyKey, ReplacePlanningAssignmentsRequest request) {
    if (sourcePlanId == null
        || idempotencyKey == null
        || request == null
        || request.warehouseId() == null
        || request.date() == null
        || request.expectedSourcePlanVersion() == null
        || request.replacementPlanVersion() == null
        || request.assignments() == null
        || request.driverShiftPlans() == null) {
      throw new IllegalArgumentException("Planner replacement identity is required");
    }
    if (request.assignments().isEmpty()
        || request.replacementPlanVersion() <= request.expectedSourcePlanVersion()) {
      throw new IllegalArgumentException("Replacement plan version must be strictly newer");
    }
  }

  private static Map<UUID, PlanningAssignmentReplacementRequest> requestedByExternalId(
      ReplacePlanningAssignmentsRequest request) {
    try {
      return request.assignments().stream()
          .collect(
              Collectors.toMap(
                  PlanningAssignmentReplacementRequest::externalTaskId,
                  Function.identity(),
                  (left, right) -> {
                    throw new IllegalArgumentException(
                        "Planner replacement contains duplicate external tasks");
                  },
                  LinkedHashMap::new));
    } catch (NullPointerException exception) {
      throw new IllegalArgumentException("Planner replacement contains an incomplete task", exception);
    }
  }

  private static void requireCompleteMembership(
      UUID sourcePlanId,
      ReplacePlanningAssignmentsRequest request,
      List<DriverLogisticsTask> membership,
      Map<UUID, PlanningAssignmentReplacementRequest> requested) {
    Set<UUID> currentIds =
        membership.stream()
            .map(DriverLogisticsTask::getExternalTaskId)
            .collect(Collectors.toUnmodifiableSet());
    if (!currentIds.equals(requested.keySet())) {
      throw conflict(
          "PLANNING_REPLACEMENT_MEMBERSHIP_CHANGED",
          "Состав опубликованного плана изменился; запросите его заново");
    }
    for (DriverLogisticsTask task : membership) {
      if (task.getSourcePlanId() == null
          || !sourcePlanId.equals(task.getSourcePlanId())
          || task.getSourcePlanVersion() == null
          || task.getSourcePlanVersion() != request.expectedSourcePlanVersion()
          || !request.warehouseId().equals(task.getSourcePlanWarehouseId())
          || !request.date().equals(task.getSourcePlanDate())) {
        throw conflict(
            "PLANNING_REPLACEMENT_SOURCE_CHANGED",
            "Версия опубликованного плана изменилась; запросите его заново");
      }
    }
  }

  private Map<UUID, LogisticsDocument> lockDocuments(ReplacePlanningAssignmentsRequest request) {
    Set<UUID> ids =
        request.assignments().stream()
            .map(PlanningAssignmentReplacementRequest::documentId)
            .collect(Collectors.toUnmodifiableSet());
    List<LogisticsDocument> locked = documents.findAllForUpdateByIdIn(ids);
    if (locked.size() != ids.size()) {
      throw conflict(
          "PLANNING_REPLACEMENT_DOCUMENT_CHANGED",
          "Одна из опубликованных отгрузок больше не существует");
    }
    return locked.stream().collect(Collectors.toMap(LogisticsDocument::getId, Function.identity()));
  }

  private Map<UUID, RentalOrder> lockOrders(ReplacePlanningAssignmentsRequest request) {
    Set<UUID> ids =
        request.assignments().stream()
            .map(PlanningAssignmentReplacementRequest::orderId)
            .collect(Collectors.toUnmodifiableSet());
    List<RentalOrder> locked = orders.findAllForUpdateByIdIn(ids);
    if (locked.size() != ids.size()) {
      throw conflict(
          "PLANNING_REPLACEMENT_ORDER_CHANGED",
          "Один из заказов опубликованного плана больше не существует");
    }
    return locked.stream().collect(Collectors.toMap(RentalOrder::getId, Function.identity()));
  }

  private static void requireLocalAssignment(
      DriverLogisticsTask task,
      PlanningAssignmentReplacementRequest item,
      Map<UUID, LogisticsDocument> documentsById,
      Map<UUID, RentalOrder> ordersById,
      ReplacePlanningAssignmentsRequest request) {
    LogisticsDocument document = documentsById.get(item.documentId());
    RentalOrder order = ordersById.get(item.orderId());
    List<UUID> taskUnits = task.getMembers().stream().map(member -> member.getCabinId()).toList();
    if (task.getVersion() != item.expectedTaskVersion()
        || task.getState() != DriverTaskState.SCHEDULED
        || task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT
        || task.getKind() != DriverTaskKind.SHIPMENT
        || !task.getSourceId().equals(item.documentId())
        || !task.getWarehouseId().equals(item.serviceWarehouseId())
        || !task.getScheduledDate().equals(item.scheduledDate())
        || !request.date().equals(item.scheduledDate())
        || task.getTaskBoardTaskId() == null
        || task.getTaskBoardEntryId() == null
        || task.getTaskBoardTaskVersion() == null
        || !"WAITING".equals(task.getTaskBoardEntryStatus())
        || document == null
        || document.getDocumentType() != LogisticsDocumentType.SHIPMENT
        || !item.orderId().equals(document.getRentalOrderId())
        || !item.serviceWarehouseId().equals(document.getWarehouseId())
        || !item.scheduledDate().equals(document.getScheduledDate())
        || !RentalOrderPlanningIntegrationService.plannerSubjectId()
            .equals(document.getRequestedBySubjectId())
        || order == null
        || order.getVersion() != item.expectedOrderVersion()
        || !taskUnits.equals(item.unitIds())) {
      throw conflict(
          "PLANNING_REPLACEMENT_ASSIGNMENT_CHANGED",
          "Отгрузка, заказ или состав бытовок изменились после публикации плана");
    }
  }

  private static void requireBoardTask(
      DriverLogisticsTask task,
      DriverBoardTask boardTask,
      PlanningAssignmentReplacementRequest item) {
    if (boardTask == null
        || !task.getTaskBoardTaskId().equals(boardTask.taskId())
        || !task.getExternalTaskId().equals(boardTask.externalTaskId())
        || !task.getWarehouseId().equals(boardTask.warehouseId())
        || !task.getScheduledDate().equals(boardTask.scheduledDate())
        || !task.getTaskBoardEntryId().equals(boardTask.entryId())
        || boardTask.taskVersion() < task.getTaskBoardTaskVersion()
        || !"ACTIVE".equals(boardTask.status())
        || !"SCHEDULED".equals(boardTask.lane())
        || !"WAITING".equals(boardTask.entryStatus())
        || boardTask.doneAt() != null
        || !sameAudience(task, boardTask.driverAudience())
        || item.targetQueuePosition() < 0) {
      throw conflict(
          "PLANNING_REPLACEMENT_TASK_ADVANCED",
          "Задание уже начато, назначено вручную или изменено на доске водителей");
    }
  }

  private static boolean sameAudience(DriverLogisticsTask task, DriverTaskAudience audience) {
    return audience != null
        && audience.mode() == task.getDriverAudienceMode()
        && java.util.Objects.equals(audience.workerId(), task.getPlannedDriverWorkerId())
        && java.util.Objects.equals(audience.workerName(), task.getPlannedDriverNameSnapshot());
  }

  private static Map<UUID, PlanningReplacementTaskResult> requireRemoteResult(
      UUID sourcePlanId,
      ReplacePlanningAssignmentsRequest request,
      PlanningReplacementResult remote,
      Set<UUID> expectedTaskIds,
      List<LogisticsDependencyGateway.PlanningReplacementShift> expectedShifts) {
    if (remote == null
        || !("APPLIED".equals(remote.outcome()) || "REPLAYED".equals(remote.outcome()))
        || !sourcePlanId.equals(remote.sourcePlanId())
        || remote.sourcePlanVersion() != request.replacementPlanVersion()
        || !request.warehouseId().equals(remote.warehouseId())
        || !request.date().equals(remote.date())
        || remote.assignments() == null
        || remote.driverShiftPlans() == null) {
      throw conflict(
          "PLANNING_REPLACEMENT_REMOTE_DIVERGENCE",
          "Доска водителей не подтвердила новую версию плана");
    }
    Map<UUID, PlanningReplacementTaskResult> taskResults;
    try {
      taskResults =
          remote.assignments().stream()
              .collect(
                  Collectors.toMap(
                      PlanningReplacementTaskResult::externalTaskId, Function.identity()));
    } catch (RuntimeException exception) {
      throw conflict(
          "PLANNING_REPLACEMENT_REMOTE_DIVERGENCE",
          "Доска водителей вернула неоднозначный состав плана");
    }
    Set<UUID> shiftIds =
        remote.driverShiftPlans().stream()
            .map(LogisticsDependencyGateway.PlanningReplacementShiftResult::sourceShiftId)
            .collect(Collectors.toUnmodifiableSet());
    Set<UUID> expectedShiftIds =
        expectedShifts.stream()
            .map(LogisticsDependencyGateway.PlanningReplacementShift::sourceShiftId)
            .collect(Collectors.toUnmodifiableSet());
    if (!expectedTaskIds.equals(taskResults.keySet()) || !expectedShiftIds.equals(shiftIds)) {
      throw conflict(
          "PLANNING_REPLACEMENT_REMOTE_DIVERGENCE",
          "Доска водителей вернула неполный состав плана");
    }
    return taskResults;
  }

  private static ReplacePlanningAssignmentsResponse response(
      PlanningReplacementOutcome outcome,
      UUID sourcePlanId,
      ReplacePlanningAssignmentsRequest request,
      List<DriverLogisticsTask> membership,
      Map<UUID, PlanningReplacementTaskResult> remoteTasks,
      PlanningReplacementResult remote) {
    Map<UUID, DriverLogisticsTask> localTasks =
        membership.stream()
            .collect(Collectors.toMap(DriverLogisticsTask::getExternalTaskId, Function.identity()));
    return new ReplacePlanningAssignmentsResponse(
        outcome,
        sourcePlanId,
        request.replacementPlanVersion(),
        request.warehouseId(),
        request.date(),
        request.assignments().stream()
            .map(
                item -> {
                  DriverLogisticsTask task = localTasks.get(item.externalTaskId());
                  PlanningReplacementTaskResult board = remoteTasks.get(item.externalTaskId());
                  return new ReplacedPlanningAssignment(
                      item.orderId(),
                      item.expectedOrderVersion(),
                      item.documentId(),
                      item.externalTaskId(),
                      task.getVersion(),
                      board.taskVersion(),
                      board.entryId(),
                      board.entryVersion(),
                      board.queuePosition());
                })
            .toList(),
        remote.driverShiftPlans().stream()
            .map(
                shift ->
                    new ReplacedPlanningDriverShift(
                        shift.sourceShiftId(),
                        shift.shiftPlanVersion(),
                        shift.sourcePlanVersion()))
            .toList());
  }

  private ReplacePlanningAssignmentsResponse replay(
      PlanningAssignmentReplacementReceipt receipt,
      UUID sourcePlanId,
      ReplacePlanningAssignmentsRequest request,
      String fingerprint) {
    if (!sourcePlanId.equals(receipt.getSourcePlanId())
        || receipt.getExpectedSourcePlanVersion() != request.expectedSourcePlanVersion()
        || receipt.getReplacementPlanVersion() != request.replacementPlanVersion()
        || !request.warehouseId().equals(receipt.getSourcePlanWarehouseId())
        || !request.date().equals(receipt.getSourcePlanDate())
        || !fingerprint.equals(receipt.getRequestSha256())) {
      throw conflict(
          "PLANNING_REPLACEMENT_IDEMPOTENCY_CONFLICT",
          "Ключ повтора уже использован для другого изменения плана");
    }
    ReplacePlanningAssignmentsResponse applied = decode(receipt.getResponseJson());
    return new ReplacePlanningAssignmentsResponse(
        REPLAYED,
        applied.sourcePlanId(),
        applied.sourcePlanVersion(),
        applied.warehouseId(),
        applied.date(),
        applied.assignments(),
        applied.driverShiftPlans());
  }

  private String fingerprint(UUID sourcePlanId, ReplacePlanningAssignmentsRequest request) {
    try {
      return LogisticsEventStore.sha256(
          objectMapper.writeValueAsBytes(Map.of("sourcePlanId", sourcePlanId, "request", request)));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Planner replacement cannot be serialized", exception);
    }
  }

  private String encode(ReplacePlanningAssignmentsResponse response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Planner replacement response cannot be serialized", exception);
    }
  }

  private ReplacePlanningAssignmentsResponse decode(String responseJson) {
    try {
      return objectMapper.readValue(responseJson, ReplacePlanningAssignmentsResponse.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored planner replacement receipt is invalid", exception);
    }
  }

  private static DriverLogisticsTask taskFor(
      List<DriverLogisticsTask> tasks, UUID externalTaskId) {
    return tasks.stream()
        .filter(task -> externalTaskId.equals(task.getExternalTaskId()))
        .findFirst()
        .orElseThrow();
  }

  private static DriverTaskAudience audience(PlanningAssignmentReplacementRequest item) {
    return new DriverTaskAudience(
        domainAudience(item.driverAudienceMode()), item.driverWorkerId(), item.driverName());
  }

  private static DriverTaskAudienceMode domainAudience(PlanningDriverAudienceMode mode) {
    if (mode == null) throw new IllegalArgumentException("Driver audience is required");
    return switch (mode) {
      case ASSIGNED_DRIVER -> DriverTaskAudienceMode.ASSIGNED_DRIVER;
      case WAREHOUSE_DRIVERS -> DriverTaskAudienceMode.WAREHOUSE_DRIVERS;
    };
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }
}
