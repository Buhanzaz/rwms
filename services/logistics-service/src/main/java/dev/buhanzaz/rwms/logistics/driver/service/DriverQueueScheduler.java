package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DriverQueueScheduler {
  private static final List<DriverTaskState> BLOCKING_STATES =
      List.of(DriverTaskState.CURRENT, DriverTaskState.FINALIZING);

  private final DriverLogisticsTaskRepository tasks;
  private final DriverTaskService taskService;
  private final DriverTaskWorkflowStore store;
  private final DriverTaskProcessor processor;
  private final LogisticsDependencyGateway dependencies;

  public void reconcileAndPromote(UUID warehouseId) {
    if (!dependencies.isWarehouseDriverQueueAvailable(warehouseId)) return;

    LogisticsDependencyGateway.RepairPlaceProjection repairPlaces =
        dependencies.readRepairPlaces(warehouseId);
    discoverRemovalTasks(repairPlaces);
    if (tasks.existsByWarehouseIdAndStateIn(warehouseId, BLOCKING_STATES)) return;

    LogisticsDependencyGateway.DriverBoardSnapshot board =
        dependencies.readDriverBoard(warehouseId);
    if (!board.current().isEmpty()) {
      DriverLogisticsTask current = local(board.current().getFirst().externalTaskId());
      if (current != null) processor.processUntilIdle(current.getId());
      return;
    }

    LocalDate today = warehouseToday(warehouseId);
    List<Candidate> candidates = candidates(board, today);
    Candidate selected = select(candidates, repairPlaces);
    if (selected != null) promote(selected, repairPlaces);
  }

  public void promoteRequested(UUID taskId) {
    DriverLogisticsTask task =
        tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
    processor.processUntilIdle(taskId);
    task = tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
    if (task.getState() != DriverTaskState.SCHEDULED) {
      if (task.getState() == DriverTaskState.CURRENT) return;
      throw new LogisticsConflictException(
          "Задание нельзя перенести в «Текущее задание»");
    }
    if (tasks.existsByWarehouseIdAndStateIn(task.getWarehouseId(), BLOCKING_STATES)) {
      throw new LogisticsConflictException(
          "В колонке «Текущее задание» уже есть активное задание");
    }
    LogisticsDependencyGateway.DriverBoardSnapshot board =
        dependencies.readDriverBoard(task.getWarehouseId());
    if (!board.current().isEmpty()) {
      throw new LogisticsConflictException(
          "В колонке «Текущее задание» уже есть активное задание");
    }
    UUID externalTaskId = task.getExternalTaskId();
    LogisticsDependencyGateway.DriverBoardTask boardTask =
        board.dates().stream()
            .flatMap(column -> column.tasks().stream())
            .filter(value -> externalTaskId.equals(value.externalTaskId()))
            .findFirst()
            .orElseThrow(
                () -> new LogisticsConflictException("Задание отсутствует в очереди водителей"));
    if (boardTask.scheduledDate().isAfter(warehouseToday(task.getWarehouseId()))) {
      throw new LogisticsConflictException(
          "Будущее задание сначала нужно перенести на текущую дату");
    }
    LogisticsDependencyGateway.RepairPlaceProjection places =
        dependencies.readRepairPlaces(task.getWarehouseId());
    promote(new Candidate(task, boardTask), places);
  }

  private void discoverRemovalTasks(
      LogisticsDependencyGateway.RepairPlaceProjection projection) {
    for (LogisticsDependencyGateway.RepairPlaceAllocation allocation :
        projection.allocations()) {
      if (!"READY_TO_RELEASE".equals(allocation.state())) continue;
      DriverTaskService.CreateResult result =
          taskService.ensureRemovalTask(
              projection.warehouseId(), allocation.repairId(), allocation.rentalItemId());
      store.bindRemovalAllocation(result.response().id(), allocation);
      processor.processUntilIdle(result.response().id());
    }
  }

  private List<Candidate> candidates(
      LogisticsDependencyGateway.DriverBoardSnapshot board, LocalDate today) {
    List<Candidate> result = new ArrayList<>();
    for (LogisticsDependencyGateway.DriverBoardDateColumn column : board.dates()) {
      if (column.date().isAfter(today)) break;
      for (LogisticsDependencyGateway.DriverBoardTask boardTask : column.tasks()) {
        DriverLogisticsTask local = local(boardTask.externalTaskId());
        if (local != null && local.getState() == DriverTaskState.SCHEDULED) {
          result.add(new Candidate(local, boardTask));
        }
      }
    }
    return result;
  }

  private Candidate select(
      List<Candidate> candidates,
      LogisticsDependencyGateway.RepairPlaceProjection repairPlaces) {
    if (candidates.isEmpty()) return null;
    DriverTaskKind lastKind =
        tasks.findRecentByWarehouseAndState(
                candidates.getFirst().task().getWarehouseId(), DriverTaskState.COMPLETED)
            .stream()
            .findFirst()
            .map(DriverLogisticsTask::getKind)
            .orElse(null);

    if (lastKind == DriverTaskKind.REMOVE_FROM_REPAIR) {
      Candidate inbound = firstEligibleInbound(candidates, repairPlaces);
      if (inbound != null) return inbound;
    }
    Candidate outbound =
        candidates.stream()
            .filter(candidate -> candidate.task().getKind().releasesRepairPlace())
            .filter(candidate -> candidate.task().getRepairPlaceAllocationVersion() != null)
            .findFirst()
            .orElse(null);
    if (outbound != null) return outbound;

    Candidate inbound = firstEligibleInbound(candidates, repairPlaces);
    if (inbound != null) return inbound;
    return candidates.stream()
        .filter(candidate -> !candidate.task().getKind().consumesRepairPlace())
        .filter(candidate -> !candidate.task().getKind().releasesRepairPlace())
        .findFirst()
        .orElse(null);
  }

  private Candidate firstEligibleInbound(
      List<Candidate> candidates,
      LogisticsDependencyGateway.RepairPlaceProjection repairPlaces) {
    return candidates.stream()
        .filter(candidate -> candidate.task().getKind().consumesRepairPlace())
        .filter(
            candidate ->
                candidate.task().getRepairPlaceAllocationVersion() != null
                    || repairPlaces.availableCount() > 0)
        .findFirst()
        .orElse(null);
  }

  private void promote(
      Candidate candidate,
      LogisticsDependencyGateway.RepairPlaceProjection repairPlaces) {
    DriverLogisticsTask task = candidate.task();
    if (task.getKind().consumesRepairPlace()
        && task.getRepairPlaceAllocationVersion() == null) {
      if (repairPlaces.availableCount() < 1) {
        throw new LogisticsConflictException(
            "На складе нет свободного ремонтного места");
      }
      LogisticsDependencyGateway.RepairPlaceAllocation reservation =
          dependencies.transitionRepairPlace(
              derivedKey("reserve", task.getId()),
              task.getWarehouseId(),
              task.getRepairId(),
              0,
              "reserve");
      store.confirmReservation(task.getId(), reservation);
    }
    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.setDriverTaskLane(
            task.getExternalTaskId(),
            candidate.boardTask().taskVersion(),
            "CURRENT");
    store.confirmCurrent(task.getId(), current);
  }

  private DriverLogisticsTask local(UUID externalTaskId) {
    return tasks.findByExternalTaskId(externalTaskId).orElse(null);
  }

  private LocalDate warehouseToday(UUID warehouseId) {
    try {
      return LocalDate.now(
          ZoneId.of(dependencies.readWarehouseIdentity(warehouseId).timeZone()));
    } catch (RuntimeException exception) {
      throw new LogisticsConflictException(
          "Для склада не настроен корректный часовой пояс");
    }
  }

  private static UUID derivedKey(String operation, UUID taskId) {
    return UUID.nameUUIDFromBytes(
        ("driver-task:" + operation + ":" + taskId).getBytes(StandardCharsets.UTF_8));
  }

  private record Candidate(
      DriverLogisticsTask task,
      LogisticsDependencyGateway.DriverBoardTask boardTask) {}
}
