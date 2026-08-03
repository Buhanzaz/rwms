package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.CapitalRepairCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardDateColumnResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardLane;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.MoveDriverBoardTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DriverBoardService {
  private static final int CAPITAL_PAGE_SIZE = 200;

  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDependencyGateway dependencies;
  private final DriverTaskWorkflowStore workflowStore;
  private final DriverTaskProcessor processor;
  private final DriverQueueScheduler scheduler;

  public DriverBoardResponse board(UUID warehouseId) {
    LogisticsDependencyGateway.WarehouseIdentity warehouse =
        dependencies.readWarehouseIdentity(warehouseId);
    LogisticsDependencyGateway.DriverBoardSnapshot board =
        dependencies.readDriverBoard(warehouseId);
    LogisticsDependencyGateway.RepairPlaceProjection places =
        dependencies.readRepairPlaces(warehouseId);
    Map<UUID, DriverLogisticsTask> localTasks = localTasks(warehouseId);
    List<CapitalRepairCardResponse> capitalRepairs =
        capitalRepairs(warehouseId, localTasks.values());

    return new DriverBoardResponse(
        warehouseId,
        warehouseToday(warehouse),
        board.queueId(),
        board.queueVersion(),
        places.repairPlaceCount(),
        DriverQueueScheduler.usedRepairPlaceCount(places),
        places.availableCount(),
        DriverQueueScheduler.inboundRepairPlaceAvailable(places),
        places.automaticRefillDelayMinutes(),
        places.overCapacity(),
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
    tasks.acquireTransactionLock("driver-queue:" + request.warehouseId());
    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.readDriverTask(externalTaskId);
    if (!request.warehouseId().equals(current.warehouseId())) {
      throw new LogisticsConflictException(
          "Задание не принадлежит выбранному складу");
    }
    LocalDate today =
        LocalDate.now(
            ZoneId.of(
                dependencies
                    .readWarehouseIdentity(request.warehouseId())
                    .timeZone()));
    if (request.targetDate().isBefore(today)) {
      throw new IllegalArgumentException(
          "Дата логистического задания не может быть в прошлом");
    }
    DriverLogisticsTask local =
        tasks.findByExternalTaskId(externalTaskId)
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Для задания отсутствует единый логистический workflow"));
    if (request.targetLane() == DriverBoardLane.CURRENT) {
      return moveToCurrent(local, current, request);
    }
    LogisticsDependencyGateway.DriverBoardTask moved =
        dependencies.moveDriverTask(
            externalTaskId,
            request.expectedTaskVersion(),
            request.expectedEntryVersion(),
            request.targetLane().name(),
            request.targetDate(),
            request.targetIndex());
    workflowStore.confirmStatus(local.getId(), moved);
    local = tasks.findById(local.getId()).orElseThrow();
    if ("CURRENT".equals(current.lane()) && "SCHEDULED".equals(moved.lane())) {
      LogisticsDependencyGateway.RepairPlaceProjection places =
          dependencies.readRepairPlaces(request.warehouseId());
      // A manual date choice is durable scheduling intent. The timed hold prevents an immediate
      // refill; FIXED_DATE prevents the rolling reflow from pulling this exact task back to today.
      local.markFixedDate(moved.scheduledDate());
      local.markManualPromotionHold(places.automaticRefillDelayMinutes());
      tasks.saveAndFlush(local);
      processor.processUntilIdle(local.getId());
      local = tasks.findById(local.getId()).orElseThrow();
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
            request.targetIndex());
    workflowStore.confirmStatus(local.getId(), moved);
    DriverLogisticsTask refreshed =
        tasks.findById(local.getId()).orElseThrow();
    return card(moved, refreshed);
  }

  private static LocalDate warehouseToday(
      LogisticsDependencyGateway.WarehouseIdentity warehouse) {
    try {
      return LocalDate.now(ZoneId.of(warehouse.timeZone()));
    } catch (RuntimeException exception) {
      throw new LogisticsConflictException(
          "Для склада не настроен корректный часовой пояс");
    }
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
        local == null ? null : local.getState(),
        board.status(),
        board.entryStatus(),
        board.scheduledDate(),
        board.lane(),
        board.priority(),
        board.pinned(),
        board.queuePosition());
  }
}
