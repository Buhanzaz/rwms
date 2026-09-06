package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.CapitalRepairCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardDateColumnResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardLane;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardRepairPlaceCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverTaskAudienceResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.MoveDriverBoardTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.ReturnCapitalRepairRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.ScheduleCapitalRepairRequest;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the authenticated driver board from logistics-owned task state and assignment scope, and
 * moves only whole grouped document trips. A locked local pre-start check runs before task-board;
 * after remote success the workflow store synchronizes the owning document date recoverably.
 */
@Service
@RequiredArgsConstructor
public class DriverBoardService {
  private static final int CAPITAL_PAGE_SIZE = 200;
  private static final Set<String> PHYSICAL_REPAIR_PLACE_STATES =
      Set.of("OCCUPIED", "READY_TO_RELEASE");

  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDependencyGateway dependencies;
  private final DriverTaskWorkflowStore workflowStore;
  private final DriverTaskProcessor processor;
  private final DriverQueueScheduler scheduler;
  private final DriverTaskService driverTaskService;
  private final DriverTripProjectionService tripProjection;
  private final LogisticsTransactionLock transactionLock;
  private final CustomerDeliveryCapacityFence capacityFence;

  public DriverBoardResponse board(UUID warehouseId) {
    LogisticsDependencyGateway.DriverBoardSnapshot board =
        dependencies.readDriverBoard(warehouseId);
    LogisticsDependencyGateway.RepairPlaceProjection places =
        dependencies.readRepairPlaces(warehouseId);
    Map<UUID, DriverLogisticsTask> localTasks = localTasks(warehouseId, board);
    Map<UUID, dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripDetailsResponse>
        tripDetails = tripProjection.boardDetails(localTasks.values());
    List<DriverBoardRepairPlaceCardResponse> repairPlaces = repairPlaces(warehouseId, places);
    List<CapitalRepairCardResponse> capitalRepairs =
        capitalRepairs(warehouseId, tasks.findActiveCapitalRepairSourceIds(warehouseId));
    LocalDate today = warehouseToday(warehouseId);

    return new DriverBoardResponse(
        warehouseId,
        today,
        board.queueId(),
        board.queueVersion(),
        places.repairPlaceCount(),
        DriverQueueScheduler.usedRepairPlaceCount(places),
        (long) repairPlaces.size(),
        places.availableCount(),
        DriverQueueScheduler.inboundRepairPlaceAvailable(places),
        places.overCapacity(),
        repairPlaces,
        board.current().stream()
            .map(
                value -> {
                  DriverLogisticsTask task = localTasks.get(value.externalTaskId());
                  return card(value, task, task == null ? null : tripDetails.get(task.getId()));
                })
            .toList(),
        currentAndFutureDateColumns(board.dates(), localTasks, tripDetails, today),
        capitalRepairs);
  }

  /**
   * Floors overdue active task-board columns to the warehouse-local current date. This keeps every
   * recoverable card visible while the background workflow converges its persisted placement and
   * guarantees that the public board never offers a past movement target.
   */
  private static List<DriverBoardDateColumnResponse> currentAndFutureDateColumns(
      List<LogisticsDependencyGateway.DriverBoardDateColumn> columns,
      Map<UUID, DriverLogisticsTask> localTasks,
      Map<UUID, dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripDetailsResponse>
          tripDetails,
      LocalDate today) {
    Map<LocalDate, List<DriverBoardCardResponse>> grouped = new TreeMap<>();
    for (LogisticsDependencyGateway.DriverBoardDateColumn column : columns) {
      LocalDate effectiveDate = column.date().isBefore(today) ? today : column.date();
      List<DriverBoardCardResponse> cards =
          grouped.computeIfAbsent(effectiveDate, ignored -> new ArrayList<>());
      for (LogisticsDependencyGateway.DriverBoardTask value : column.tasks()) {
        DriverLogisticsTask task = localTasks.get(value.externalTaskId());
        cards.add(
            card(
                value,
                task,
                task == null ? null : tripDetails.get(task.getId()),
                effectiveDate));
      }
    }
    return grouped.entrySet().stream()
        .map(entry -> new DriverBoardDateColumnResponse(entry.getKey(), entry.getValue()))
        .toList();
  }

  /**
   * Moves a whole board task. The locked local pre-start check precedes every task-board read or
   * command, then retains the local task/document locks across task-board's version-fenced command.
   * This is an intentional bounded remote-under-lock exception: releasing the document lock before
   * the remote command would let a locally started trip move while its task-board entry still
   * reports {@code WAITING}. The dependency client applies the configured connect/read deadlines
   * (2s/5s by default); a remote success followed by local rollback is converged by the existing
   * task status poll through {@link DriverTaskWorkflowStore#confirmStatus(UUID,
   * LogisticsDependencyGateway.DriverBoardTask)}.
   */
  @Transactional
  public DriverBoardCardResponse move(UUID externalTaskId, MoveDriverBoardTaskRequest request) {
    transactionLock.acquire("driver-queue:" + request.warehouseId());
    LocalDate today = warehouseToday(request.warehouseId());
    if (request.targetDate().isBefore(today)) {
      throw new IllegalArgumentException("Дата логистического задания не может быть в прошлом");
    }
    capacityFence.acquireDay(request.warehouseId(), request.targetDate());
    DriverLogisticsTask local =
        tasks
            .findForUpdateByExternalTaskId(externalTaskId)
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Для задания отсутствует единый логистический workflow"));
    if (!request.warehouseId().equals(local.getWarehouseId())) {
      throw new LogisticsConflictException("Задание не принадлежит выбранному складу");
    }
    if (local.isOverdueTrip(today) || local.getTripExpiryRequestedAt() != null) {
      throw new LogisticsConflictException("День рейса завершён: требуется новый рейс");
    }
    if (local.getSourceType() == DriverTaskSourceType.LOGISTICS_DOCUMENT) {
      workflowStore.requireGroupedDocumentMovePreStart(local.getId());
    }
    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.readDriverTask(externalTaskId);
    if (!request.warehouseId().equals(current.warehouseId())) {
      throw new LogisticsConflictException("Задание не принадлежит выбранному складу");
    }
    requirePublicMoveScope(local, current, request);
    if (request.targetLane() == DriverBoardLane.CURRENT) {
      if (!"CURRENT".equals(current.lane())
          && local.getKind() != DriverTaskKind.CAPITAL_TO_PRODUCTION) {
        throw new LogisticsConflictException(
            "В «Текущие задания» вручную можно добавить только капитальный ремонт");
      }
      return moveToCurrent(local, current, request);
    }
    LogisticsDependencyGateway.DriverBoardTask moved =
        dependencies.moveDriverTask(
            externalTaskId,
            request.expectedTaskVersion(),
            request.expectedEntryVersion(),
            request.targetLane().name(),
            request.targetDate(),
            request.targetIndex(),
            null);
    workflowStore.confirmStatus(local.getId(), moved);
    local = tasks.findById(local.getId()).orElseThrow();
    if ("CURRENT".equals(current.lane()) && "SCHEDULED".equals(moved.lane())) {
      local.markFixedDate(moved.scheduledDate());
      if (local.getKind().consumesRepairPlace()) {
        // The legacy timestamp is retained only as a durable "release pending" marker. Queue
        // scheduling never reads its deadline, and the marker is cleared with the reservation.
        local.markRepairPlaceReleasePending();
      }
      tasks.saveAndFlush(local);
      if (local.getKind().consumesRepairPlace()) {
        processor.processUntilIdle(local.getId());
        local = tasks.findById(local.getId()).orElseThrow();
        scheduler.reconcileAndPromote(request.warehouseId());
      }
    }
    if ("SCHEDULED".equals(current.lane())
        && "SCHEDULED".equals(moved.lane())
        && !current.scheduledDate().equals(moved.scheduledDate())) {
      local.markFixedDate(moved.scheduledDate());
      tasks.saveAndFlush(local);
      local = tasks.findById(local.getId()).orElseThrow();
    }
    return card(moved, local);
  }

  /**
   * Creates the capital-to-production movement directly in the selected calendar lane. The browser
   * sends one idempotent command; registration and queue insertion stay in the logistics-owned
   * workflow.
   */
  @Transactional
  public CapitalRepairScheduleResult scheduleCapitalRepair(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID repairId,
      ScheduleCapitalRepairRequest request) {
    transactionLock.acquire("driver-queue:" + request.warehouseId());
    DriverTaskService.CreateResult created =
        driverTaskService.createCapitalMovement(
            actorSubjectId,
            idempotencyKey,
            request.warehouseId(),
            repairId,
            DriverTaskPlanningMode.FIXED_DATE,
            request.targetDate());
    return scheduleCapitalRepair(created, request);
  }

  @Transactional
  public CapitalRepairScheduleResult scheduleCapitalRepair(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID repairId,
      ScheduleCapitalRepairRequest request,
      AdmissionTicket admission) {
    transactionLock.acquire("driver-queue:" + request.warehouseId());
    DriverTaskService.CreateResult created =
        driverTaskService.createCapitalMovement(
            actorSubjectId,
            idempotencyKey,
            request.warehouseId(),
            repairId,
            DriverTaskPlanningMode.FIXED_DATE,
            request.targetDate(),
            admission);
    return scheduleCapitalRepair(created, request);
  }

  private CapitalRepairScheduleResult scheduleCapitalRepair(
      DriverTaskService.CreateResult created, ScheduleCapitalRepairRequest request) {
    processor.processUntilIdle(created.response().id());

    DriverLogisticsTask local =
        tasks
            .findForUpdate(created.response().id())
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Для капитального ремонта отсутствует единый логистический workflow"));
    LogisticsDependencyGateway.DriverBoardTask scheduled =
        dependencies.readDriverTask(local.getExternalTaskId());
    if (!isSchedulableCapitalSnapshot(scheduled, request.warehouseId())) {
      throw new LogisticsConflictException(
          "Капитальный ремонт нельзя поставить в запланированную очередь");
    }
    if (!request.targetDate().equals(scheduled.scheduledDate())
        || request.targetIndex() != scheduled.queuePosition()) {
      scheduled =
          dependencies.moveDriverTask(
              scheduled.externalTaskId(),
              scheduled.taskVersion(),
              scheduled.entryVersion(),
              DriverBoardLane.SCHEDULED.name(),
              request.targetDate(),
              request.targetIndex(),
              null);
      workflowStore.confirmStatus(local.getId(), scheduled);
      local = tasks.findById(local.getId()).orElseThrow();
    }
    return new CapitalRepairScheduleResult(card(scheduled, local), created.replayed());
  }

  @Transactional
  public void returnToCapitalRepairs(UUID externalTaskId, ReturnCapitalRepairRequest request) {
    transactionLock.acquire("driver-queue:" + request.warehouseId());
    DriverLogisticsTask local =
        tasks
            .findForUpdateByExternalTaskId(externalTaskId)
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Для задания отсутствует единый логистический workflow"));
    if (!request.warehouseId().equals(local.getWarehouseId())) {
      throw new LogisticsConflictException("Задание не принадлежит выбранному складу");
    }
    if (local.getSourceType() != DriverTaskSourceType.CAPITAL_REPAIR
        || local.getKind() != DriverTaskKind.CAPITAL_TO_PRODUCTION) {
      throw new LogisticsConflictException(
          "Вернуть в капитальные ремонты можно только перемещение капитального ремонта");
    }
    if (local.getState() == DriverTaskState.CANCELLED) {
      return;
    }
    if (local.getState().isTerminal()) {
      throw new LogisticsConflictException(
          "Завершённое или уже отменённое перемещение вернуть нельзя");
    }
    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.readDriverTask(externalTaskId);
    if ("CANCELLED".equals(current.status())) {
      workflowStore.confirmStatus(local.getId(), current);
      return;
    }
    LogisticsDependencyGateway.DriverBoardTask cancelled =
        dependencies.cancelDriverTask(externalTaskId, request.expectedTaskVersion());
    workflowStore.confirmStatus(local.getId(), cancelled);
  }

  private DriverBoardCardResponse moveToCurrent(
      DriverLogisticsTask local,
      LogisticsDependencyGateway.DriverBoardTask current,
      MoveDriverBoardTaskRequest request) {
    if (current.taskVersion() != request.expectedTaskVersion()
        || current.entryVersion() != request.expectedEntryVersion()) {
      throw new LogisticsConflictException("Очередь перемещений уже изменилась");
    }
    LogisticsDependencyGateway.DriverBoardTask promoted = current;
    if (!"CURRENT".equals(current.lane())) {
      scheduler.promoteRequested(local.getId());
      promoted = dependencies.readDriverTask(current.externalTaskId());
    }
    LogisticsDependencyGateway.DriverBoardTask moved =
        dependencies.moveDriverTask(
            promoted.externalTaskId(),
            promoted.taskVersion(),
            promoted.entryVersion(),
            DriverBoardLane.CURRENT.name(),
            request.targetDate(),
            request.targetIndex(),
            null);
    workflowStore.confirmStatus(local.getId(), moved);
    DriverLogisticsTask refreshed = tasks.findById(local.getId()).orElseThrow();
    return card(moved, refreshed);
  }

  private LocalDate warehouseToday(UUID warehouseId) {
    try {
      OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
      return at.toInstant()
          .atZone(ZoneId.of(dependencies.warehouseTimeZoneAt(warehouseId, at).timeZone()))
          .toLocalDate();
    } catch (RuntimeException exception) {
      throw new LogisticsConflictException("Для склада не настроен корректный часовой пояс");
    }
  }

  private static boolean isSchedulableCapitalSnapshot(
      LogisticsDependencyGateway.DriverBoardTask board, UUID warehouseId) {
    return board != null
        && warehouseId.equals(board.warehouseId())
        && "SCHEDULED".equals(board.lane())
        && "ACTIVE".equals(board.status())
        && "WAITING".equals(board.entryStatus());
  }

  /** Loads local details only for task-board cards visible in this snapshot. */
  private Map<UUID, DriverLogisticsTask> localTasks(
      UUID warehouseId, LogisticsDependencyGateway.DriverBoardSnapshot board) {
    Set<UUID> externalTaskIds = new LinkedHashSet<>();
    board.current().forEach(task -> externalTaskIds.add(task.externalTaskId()));
    board.dates().forEach(column ->
        column.tasks().forEach(task -> externalTaskIds.add(task.externalTaskId())));
    if (externalTaskIds.isEmpty()) return Map.of();
    return tasks.findAllByWarehouseIdAndExternalTaskIdIn(warehouseId, externalTaskIds).stream()
        .collect(Collectors.toMap(
            DriverLogisticsTask::getExternalTaskId,
            Function.identity(),
            (left, right) -> left,
            LinkedHashMap::new));
  }

  private List<CapitalRepairCardResponse> capitalRepairs(
      UUID warehouseId, Collection<UUID> alreadyMovedSourceIds) {
    Set<UUID> alreadyMoved = Set.copyOf(alreadyMovedSourceIds);
    ArrayList<CapitalRepairCardResponse> result = new ArrayList<>();
    int page = 0;
    long loaded = 0;
    long total;
    do {
      LogisticsDependencyGateway.CapitalRepairPage response =
          dependencies.readCapitalRepairs(warehouseId, page, CAPITAL_PAGE_SIZE);
      total = response.totalElements();
      if (response.items().isEmpty() && loaded < total) {
        throw new LogisticsConflictException(
            "Каталог капитальных ремонтов вернул неполную страницу");
      }
      loaded += response.items().size();
      for (LogisticsDependencyGateway.CapitalRepair repair : response.items()) {
        if (alreadyMoved.contains(repair.repairId())) continue;
        LogisticsDependencyGateway.RentalItemSnapshot cabin =
            dependencies.readRentalItemSnapshot(repair.rentalItemId());
        if (!warehouseId.equals(repair.warehouseId())
            || !warehouseId.equals(cabin.warehouseId())
            || !repair.rentalItemId().equals(cabin.assetId())) {
          throw new LogisticsConflictException(
              "Капитальный ремонт и бытовка принадлежат разным складам");
        }
        result.add(
            new CapitalRepairCardResponse(
                repair.repairId(),
                repair.version(),
                repair.rentalItemId(),
                cabin.version(),
                cabin.number(),
                repair.priority(),
                repair.complexity().name(),
                repair.complexity().color(),
                repair.complexity().plannedMinutes(),
                repair.complexity().forcedCapital()));
      }
      page++;
    } while (loaded < total);
    return List.copyOf(result);
  }

  private List<DriverBoardRepairPlaceCardResponse> repairPlaces(
      UUID warehouseId, LogisticsDependencyGateway.RepairPlaceProjection places) {
    Map<UUID, LogisticsDependencyGateway.RentalItemSnapshot> cabins = new LinkedHashMap<>();
    java.util.ArrayList<DriverBoardRepairPlaceCardResponse> result = new java.util.ArrayList<>();
    for (LogisticsDependencyGateway.RepairPlaceAllocation allocation : places.allocations()) {
      if (!PHYSICAL_REPAIR_PLACE_STATES.contains(allocation.state())) continue;
      LogisticsDependencyGateway.RentalItemSnapshot cabin =
          cabins.computeIfAbsent(allocation.rentalItemId(), dependencies::readRentalItemSnapshot);
      if (!warehouseId.equals(allocation.warehouseId())
          || !warehouseId.equals(cabin.warehouseId())
          || !allocation.rentalItemId().equals(cabin.assetId())) {
        throw new LogisticsConflictException(
            "Ремонтное место и бытовка принадлежат разным складам");
      }
      result.add(
          new DriverBoardRepairPlaceCardResponse(
              allocation.repairId(),
              allocation.rentalItemId(),
              cabin.number(),
              allocation.state(),
              allocation.repairStageName(),
              allocation.repairStageState(),
              allocation.priority()));
    }
    return List.copyOf(result);
  }

  private DriverBoardCardResponse card(
      LogisticsDependencyGateway.DriverBoardTask board, DriverLogisticsTask local) {
    return card(board, local, local == null ? null : tripProjection.details(local.getId()));
  }

  private static DriverBoardCardResponse card(
      LogisticsDependencyGateway.DriverBoardTask board,
      DriverLogisticsTask local,
      dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripDetailsResponse
          tripDetails) {
    return card(board, local, tripDetails, board.scheduledDate());
  }

  private static DriverBoardCardResponse card(
      LogisticsDependencyGateway.DriverBoardTask board,
      DriverLogisticsTask local,
      dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripDetailsResponse
          tripDetails,
      LocalDate effectiveScheduledDate) {
    return new DriverBoardCardResponse(
        local == null ? null : local.getId(),
        board.externalTaskId(),
        board.taskId(),
        board.taskVersion(),
        board.entryId(),
        board.entryVersion(),
        board.title(),
        board.taskText(),
        board.unitNumber(),
        local == null ? null : local.getKind(),
        new DriverTaskAudienceResponse(
            board.driverAudience().mode(),
            board.driverAudience().workerId(),
            board.driverAudience().workerName()),
        local == null ? null : local.getState(),
        board.status(),
        board.entryStatus(),
        effectiveScheduledDate,
        board.lane(),
        board.priority(),
        board.pinned(),
        board.queuePosition(),
        tripDetails);
  }

  /**
   * Keeps a document trip's server-owned driver audience immutable while the ordinary board move
   * command reorders the whole task, including all grouped cabin members, across supported dates
   * and lanes.
   */
  private static void requirePublicMoveScope(
      DriverLogisticsTask task,
      LogisticsDependencyGateway.DriverBoardTask current,
      MoveDriverBoardTaskRequest request) {
    if (task.getKind() != DriverTaskKind.SHIPMENT
        && task.getKind() != DriverTaskKind.RETURN
        && task.getKind() != DriverTaskKind.TRANSFER) {
      return;
    }
    if (task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT) {
      throw new LogisticsConflictException("Перемещать можно только целую сгруппированную ходку");
    }
    if (current.driverAudience() == null
        || current.driverAudience().mode() != task.getDriverAudienceMode()
        || !java.util.Objects.equals(
            current.driverAudience().workerId(), task.getPlannedDriverWorkerId())) {
      throw new LogisticsConflictException("Аудитория задания водителя изменилась; обновите доску");
    }
  }

  /** Scheduled capital-repair board card plus create-command replay truth. */
  public record CapitalRepairScheduleResult(DriverBoardCardResponse card, boolean replayed) {}
}
