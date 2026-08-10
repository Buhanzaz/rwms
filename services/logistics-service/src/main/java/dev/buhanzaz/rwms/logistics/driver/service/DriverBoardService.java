package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.CapitalRepairCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardDateColumnResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardLane;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardRepairPlaceCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverTaskAudienceResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the authenticated driver board from logistics-owned task state and assignment scope.
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
  private final LogisticsTransactionLock transactionLock;

  public DriverBoardResponse board(UUID warehouseId) {
    LogisticsDependencyGateway.DriverBoardSnapshot board =
        dependencies.readDriverBoard(warehouseId);
    LogisticsDependencyGateway.RepairPlaceProjection places =
        dependencies.readRepairPlaces(warehouseId);
    Map<UUID, DriverLogisticsTask> localTasks = localTasks(warehouseId);
    List<DriverBoardRepairPlaceCardResponse> repairPlaces =
        repairPlaces(warehouseId, places);
    List<CapitalRepairCardResponse> capitalRepairs =
        capitalRepairs(warehouseId, localTasks.values());

    return new DriverBoardResponse(
        warehouseId,
        warehouseToday(warehouseId),
        board.queueId(),
        board.queueVersion(),
        places.repairPlaceCount(),
        DriverQueueScheduler.usedRepairPlaceCount(places),
        (long) repairPlaces.size(),
        places.availableCount(),
        DriverQueueScheduler.inboundRepairPlaceAvailable(places),
        places.automaticRefillDelayMinutes(),
        places.overCapacity(),
        repairPlaces,
        board.current().stream()
            .map(value -> card(value, localTasks.get(value.externalTaskId())))
            .toList(),
        board.dates().stream()
            .map(
                column ->
                    new DriverBoardDateColumnResponse(
                        column.date(),
                        column.tasks().stream()
                            .map(value -> card(value, localTasks.get(value.externalTaskId())))
                            .toList()))
            .toList(),
        capitalRepairs);
  }

  @Transactional
  public DriverBoardCardResponse move(
      UUID externalTaskId, MoveDriverBoardTaskRequest request) {
    transactionLock.acquire("driver-queue:" + request.warehouseId());
    DriverLogisticsTask local =
        tasks.findForUpdateByExternalTaskId(externalTaskId)
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Для задания отсутствует единый логистический workflow"));
    if (!request.warehouseId().equals(local.getWarehouseId())) {
      throw new LogisticsConflictException(
          "Задание не принадлежит выбранному складу");
    }
    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.readDriverTask(externalTaskId);
    if (!request.warehouseId().equals(current.warehouseId())) {
      throw new LogisticsConflictException(
          "Задание не принадлежит выбранному складу");
    }
    requirePublicMoveScope(local, current, request);
    LocalDate today = warehouseToday(request.warehouseId());
    if (request.targetDate().isBefore(today)) {
      throw new IllegalArgumentException(
          "Дата логистического задания не может быть в прошлом");
    }
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
        LogisticsDependencyGateway.RepairPlaceProjection places =
            dependencies.readRepairPlaces(request.warehouseId());
        // A repair delivery additionally releases its reserved place. The timed hold prevents an
        // immediate refill while that recoverable effect is being confirmed.
        local.markManualPromotionHold(places.automaticRefillDelayMinutes());
      }
      tasks.saveAndFlush(local);
      if (local.getKind().consumesRepairPlace()) {
        processor.processUntilIdle(local.getId());
        local = tasks.findById(local.getId()).orElseThrow();
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
   * Creates the capital-to-production movement directly in the selected calendar lane. The
   * browser sends one idempotent command; registration and queue insertion stay in the
   * logistics-owned workflow.
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
  public void returnToCapitalRepairs(
      UUID externalTaskId, ReturnCapitalRepairRequest request) {
    transactionLock.acquire("driver-queue:" + request.warehouseId());
    DriverLogisticsTask local =
        tasks.findForUpdateByExternalTaskId(externalTaskId)
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Для задания отсутствует единый логистический workflow"));
    if (!request.warehouseId().equals(local.getWarehouseId())) {
      throw new LogisticsConflictException(
          "Задание не принадлежит выбранному складу");
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
      throw new LogisticsConflictException(
          "Очередь перемещений уже изменилась");
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
    DriverLogisticsTask refreshed =
        tasks.findById(local.getId()).orElseThrow();
    return card(moved, refreshed);
  }

  private LocalDate warehouseToday(UUID warehouseId) {
    try {
      OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
      return at.toInstant()
          .atZone(ZoneId.of(dependencies.warehouseTimeZoneAt(warehouseId, at).timeZone()))
          .toLocalDate();
    } catch (RuntimeException exception) {
      throw new LogisticsConflictException(
          "Для склада не настроен корректный часовой пояс");
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

  private Map<UUID, DriverLogisticsTask> localTasks(UUID warehouseId) {
    Map<UUID, DriverLogisticsTask> result = new LinkedHashMap<>();
    for (DriverLogisticsTask task :
        tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId)) {
      result.put(task.getExternalTaskId(), task);
    }
    return result;
  }

  private List<CapitalRepairCardResponse> capitalRepairs(
      UUID warehouseId, java.util.Collection<DriverLogisticsTask> localTasks) {
    java.util.Set<UUID> alreadyMoved =
        localTasks.stream()
            .filter(task -> task.getKind() == DriverTaskKind.CAPITAL_TO_PRODUCTION)
            .filter(task -> task.getSourceType() == DriverTaskSourceType.CAPITAL_REPAIR)
            .filter(task -> task.getState() != DriverTaskState.CANCELLED)
            .map(DriverLogisticsTask::getSourceId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    java.util.ArrayList<CapitalRepairCardResponse> result = new java.util.ArrayList<>();
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

  private static DriverBoardCardResponse card(
      LogisticsDependencyGateway.DriverBoardTask board,
      DriverLogisticsTask local) {
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
        board.scheduledDate(),
        board.lane(),
        board.priority(),
        board.pinned(),
        board.queuePosition());
  }

  /**
   * Restricts document delivery work to ordering inside its current driver/date/lane section.
   * Movement work retains the existing broader date/lane scheduling policy, but the public
   * command never changes either kind's server-owned audience.
   */
  private static void requirePublicMoveScope(
      DriverLogisticsTask task,
      LogisticsDependencyGateway.DriverBoardTask current,
      MoveDriverBoardTaskRequest request) {
    if (task.getKind() != DriverTaskKind.SHIPMENT
        && task.getKind() != DriverTaskKind.RETURN) {
      return;
    }
    if (!request.targetLane().name().equals(current.lane())
        || !request.targetDate().equals(current.scheduledDate())) {
      throw new LogisticsConflictException(
          "Отгрузку или возврат можно переставлять только внутри своей очереди водителя и даты");
    }
    if (current.driverAudience() == null
        || current.driverAudience().mode() != task.getDriverAudienceMode()
        || !java.util.Objects.equals(
            current.driverAudience().workerId(), task.getPlannedDriverWorkerId())) {
      throw new LogisticsConflictException(
          "Аудитория задания водителя изменилась; обновите доску");
    }
  }

  public record CapitalRepairScheduleResult(
      DriverBoardCardResponse card, boolean replayed) {}
}
