package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * Maintains one warehouse-owned rolling logistics queue.
 *
 * <p>The task board remains authoritative for visual order and queue-entry versions. This service
 * derives only the repair-capacity-aware scheduled-date placement and then converges it through the
 * task-board's optimistic move command.
 */
@Service
@RequiredArgsConstructor
public class DriverQueueScheduler {
  private static final int MAX_AUTO_PROMOTIONS_PER_PASS = 128;

  private final DriverLogisticsTaskRepository tasks;
  private final DriverTaskService taskService;
  private final DriverTaskWorkflowStore store;
  private final DriverTaskProcessor processor;
  private final LogisticsDependencyGateway dependencies;

  @Transactional
  public void reconcileAndPromote(UUID warehouseId) {
    if (!dependencies.isWarehouseDriverQueueAvailable(warehouseId)) return;
    lockWarehouseQueue(warehouseId);

    LogisticsDependencyGateway.RepairPlaceProjection repairPlaces =
        dependencies.readRepairPlaces(warehouseId);
    discoverRemovalTasks(repairPlaces);
    // A current -> scheduled move is an explicit operator decision. Freeze both automatic
    // promotion and rolling reflow while the maintenance-owned hold is active so the relay cannot
    // alter the entry version between two manual drag-and-drop operations.
    if (hasActiveManualPromotionHold(warehouseId)) return;
    LocalDate today = warehouseToday(warehouseId);
    reflowScheduledQueue(warehouseId, today, repairPlaces.repairPlaceCount());

    LogisticsDependencyGateway.DriverBoardSnapshot board =
        dependencies.readDriverBoard(warehouseId);

    // A current lane is an ordered queue, not a singleton. Synchronize every visible current task
    // before deciding whether another task can be appended.
    for (LogisticsDependencyGateway.DriverBoardTask current : board.current()) {
      DriverLogisticsTask local = local(current.externalTaskId());
      if (local != null) processor.processUntilIdle(local.getId());
    }

    Set<UUID> promotedInPass = new HashSet<>();
    long remainingInboundSlots = schedulableInboundSlots(repairPlaces);
    DriverTaskKind promotedTail = null;
    for (int pass = 0; pass < MAX_AUTO_PROMOTIONS_PER_PASS; pass++) {
      board = dependencies.readDriverBoard(warehouseId);
      repairPlaces = dependencies.readRepairPlaces(warehouseId);
      if (hasActiveManualPromotionHold(warehouseId)) return;
      DriverTaskKind previousKind =
          promotedTail != null ? promotedTail : currentTailKind(board);

      Candidate selected =
          select(
              candidates(board, today, promotedInPass),
              previousKind,
              Math.min(
                  remainingInboundSlots,
                  schedulableInboundSlots(repairPlaces)));
      if (selected == null) return;
      boolean reservesPlace =
          selected.task().getKind().consumesRepairPlace()
              && selected.task().getRepairPlaceAllocationVersion() == null;
      promote(selected, repairPlaces);
      promotedInPass.add(selected.task().getExternalTaskId());
      promotedTail = selected.task().getKind();
      if (reservesPlace) remainingInboundSlots--;
    }
  }

  /**
   * Performs an explicit current-lane insertion. A general or capital movement never consumes a
   * repair place. A repair delivery may proceed only against a free place, a paired ready removal,
   * or its own existing reservation.
   */
  @Transactional
  public void promoteRequested(UUID taskId) {
    DriverLogisticsTask task =
        tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
    lockWarehouseQueue(task.getWarehouseId());
    processor.processUntilIdle(taskId);
    task = tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
    if (task.getState() != DriverTaskState.SCHEDULED) {
      if (task.getState() == DriverTaskState.CURRENT) return;
      throw new LogisticsConflictException(
          "Задание нельзя перенести в «Текущее задание»");
    }

    OffsetDateTime now = now();
    List<DriverLogisticsTask> heldTasks =
        tasks
            .findAllByWarehouseIdAndStateAndManualPromotionHoldUntilAfterOrderByManualPromotionHoldUntilAscIdAsc(
                task.getWarehouseId(), DriverTaskState.SCHEDULED, now);
    DriverLogisticsTask held = selectHeldTask(heldTasks, task.getId());

    LogisticsDependencyGateway.RepairPlaceProjection preflight =
        dependencies.readRepairPlaces(task.getWarehouseId());
    ensureManualPromotionCanUseCapacity(task, held, preflight);

    // One manual insertion replaces exactly one active manually-created hole. If that hole had
    // reserved a place, release it before reserving the replacement task.
    if (held != null) {
      processor.processUntilIdle(held.getId());
      DriverLogisticsTask released =
          tasks.findById(held.getId()).orElseThrow(LogisticsNotFoundException::new);
      if (released.getKind().consumesRepairPlace()
          && released.getRepairPlaceAllocationVersion() != null) {
        throw new LogisticsConflictException(
            "Не удалось освободить ремонтное место после ручного переноса");
      }
      released.clearManualPromotionHold();
      tasks.saveAndFlush(released);
    }

    task = tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
    UUID externalTaskId = task.getExternalTaskId();
    // Registration and the board projection are separate read paths.  An explicit operator
    // promotion must use the task-board's authoritative point lookup, not wait for a whole-board
    // snapshot to contain the just-registered card.  In particular, this lets a capital repair
    // enter CURRENT immediately even while the scheduled-date projection is catching up.
    LogisticsDependencyGateway.DriverBoardTask boardTask =
        dependencies.readDriverTask(externalTaskId);
    if (!isMovableScheduledSnapshot(boardTask, task.getWarehouseId())) {
      throw new LogisticsConflictException("Задание нельзя перенести в «Текущее задание»");
    }
    LogisticsDependencyGateway.RepairPlaceProjection places =
        dependencies.readRepairPlaces(task.getWarehouseId());
    promote(new Candidate(task, boardTask), places);
  }

  private void reflowScheduledQueue(UUID warehouseId, LocalDate today, int repairPlaceCount) {
    if (repairPlaceCount < 1) {
      throw new LogisticsConflictException("На складе не настроено количество ремонтных мест");
    }
    LogisticsDependencyGateway.DriverBoardSnapshot board =
        dependencies.readDriverBoard(warehouseId);
    Map<UUID, DriverLogisticsTask> localTasks = localTasksByExternalId(warehouseId);
    List<ScheduledCandidate> ordered = scheduledCandidates(board, localTasks);
    List<ScheduledPlacement> placements =
        schedulePlacements(ordered, today, repairPlaceCount);

    for (ScheduledPlacement placement : placements) {
      DriverLogisticsTask task = placement.candidate().task();
      // Task-board is an independently versioned aggregate. Always use a fresh task + entry
      // snapshot for the command; stale relay plans fail closed rather than overwrite a user move.
      LogisticsDependencyGateway.DriverBoardTask fresh =
          dependencies.readDriverTask(task.getExternalTaskId());
      if (!isMovableScheduledSnapshot(fresh, warehouseId)) continue;
      if (fresh.scheduledDate().equals(placement.date())
          && fresh.queuePosition() == placement.index()) {
        continue;
      }
      LogisticsDependencyGateway.DriverBoardTask moved =
          dependencies.moveDriverTask(
              fresh.externalTaskId(),
              fresh.taskVersion(),
              fresh.entryVersion(),
              "SCHEDULED",
              placement.date(),
              placement.index());
      workflowStoreConfirmScheduled(task.getId(), moved);
    }
  }

  private void workflowStoreConfirmScheduled(
      UUID taskId, LogisticsDependencyGateway.DriverBoardTask moved) {
    store.confirmStatus(taskId, moved);
  }

  private static List<ScheduledCandidate> scheduledCandidates(
      LogisticsDependencyGateway.DriverBoardSnapshot board,
      Map<UUID, DriverLogisticsTask> localTasks) {
    List<ScheduledCandidate> result = new ArrayList<>();
    board.dates().stream()
        .sorted(Comparator.comparing(LogisticsDependencyGateway.DriverBoardDateColumn::date))
        .forEach(
            column ->
                column.tasks().stream()
                    .sorted(
                        Comparator.comparingInt(
                                LogisticsDependencyGateway.DriverBoardTask::queuePosition)
                            .thenComparing(
                                value -> value.externalTaskId().toString()))
                    .forEach(
                        boardTask -> {
                          DriverLogisticsTask local = localTasks.get(boardTask.externalTaskId());
                          if (local != null
                              && local.getState() == DriverTaskState.SCHEDULED
                              && "SCHEDULED".equals(boardTask.lane())) {
                            result.add(new ScheduledCandidate(local, boardTask));
                          }
                        }));
    return List.copyOf(result);
  }

  /**
   * Assigns the flattened queue to date buckets without changing its relative order. A manually
   * selected future date is a lower bound, never a one-time display value; AUTO items can roll
   * forward or back to the first capacity-aware date no earlier than today.
   */
  private static List<ScheduledPlacement> schedulePlacements(
      List<ScheduledCandidate> candidates, LocalDate today, int repairPlaceCount) {
    LocalDate cursor = today;
    Map<LocalDate, Integer> inboundByDate = new HashMap<>();
    Map<LocalDate, Integer> nextIndexByDate = new HashMap<>();
    List<ScheduledPlacement> result = new ArrayList<>(candidates.size());
    for (ScheduledCandidate candidate : candidates) {
      DriverLogisticsTask task = candidate.task();
      LocalDate lowerBound = lowerBound(task, today);
      LocalDate date = cursor.isAfter(lowerBound) ? cursor : lowerBound;
      if (task.getKind().consumesRepairPlace()) {
        while (inboundByDate.getOrDefault(date, 0) >= repairPlaceCount) {
          date = date.plusDays(1);
        }
        inboundByDate.merge(date, 1, Integer::sum);
      }
      int index = nextIndexByDate.getOrDefault(date, 0);
      nextIndexByDate.put(date, index + 1);
      result.add(new ScheduledPlacement(candidate, date, index));
      cursor = date;
    }
    return List.copyOf(result);
  }

  private static LocalDate lowerBound(DriverLogisticsTask task, LocalDate today) {
    if (task.getPlanningMode() != DriverTaskPlanningMode.FIXED_DATE) return today;
    LocalDate fixed = task.getFixedDateLowerBound();
    if (fixed == null) fixed = task.getScheduledDate();
    return fixed.isAfter(today) ? fixed : today;
  }

  private static boolean isMovableScheduledSnapshot(
      LogisticsDependencyGateway.DriverBoardTask task, UUID warehouseId) {
    return task != null
        && warehouseId.equals(task.warehouseId())
        && "SCHEDULED".equals(task.lane())
        && "ACTIVE".equals(task.status())
        && "WAITING".equals(task.entryStatus());
  }

  private void discoverRemovalTasks(
      LogisticsDependencyGateway.RepairPlaceProjection projection) {
    for (LogisticsDependencyGateway.RepairPlaceAllocation allocation :
        projection.allocations()) {
      if (!"READY_TO_RELEASE".equals(allocation.state())) continue;
      DriverTaskService.CreateResult result =
          taskService.ensureRemovalTask(
              projection.warehouseId(),
              allocation.repairId(),
              allocation.rentalItemId(),
              allocation.priority());
      store.bindReleaseAllocation(result.response().id(), allocation);
      processor.processUntilIdle(result.response().id());
    }
  }

  private List<Candidate> candidates(
      LogisticsDependencyGateway.DriverBoardSnapshot board,
      LocalDate today,
      Set<UUID> excludedTaskIds) {
    List<Candidate> result = new ArrayList<>();
    for (LogisticsDependencyGateway.DriverBoardDateColumn column : board.dates()) {
      if (column.date().isAfter(today)) break;
      for (LogisticsDependencyGateway.DriverBoardTask boardTask : column.tasks()) {
        DriverLogisticsTask local = local(boardTask.externalTaskId());
        if (local != null
            && local.getState() == DriverTaskState.SCHEDULED
            && !excludedTaskIds.contains(local.getExternalTaskId())) {
          result.add(new Candidate(local, boardTask));
        }
      }
    }
    return result;
  }

  private Candidate select(
      List<Candidate> candidates,
      DriverTaskKind previousKind,
      long availableInboundSlots) {
    if (candidates.isEmpty()) return null;

    if (previousKind == null) {
      previousKind =
          tasks.findRecentByWarehouseAndState(
                  candidates.getFirst().task().getWarehouseId(), DriverTaskState.COMPLETED)
              .stream()
              .findFirst()
              .map(DriverLogisticsTask::getKind)
              .orElse(null);
    }

    if (previousKind != null && previousKind.releasesRepairPlace()) {
      Candidate inbound = firstEligibleInbound(candidates, availableInboundSlots);
      if (inbound != null) return inbound;
    }

    if (previousKind == null || !previousKind.releasesRepairPlace()) {
      Candidate outbound =
          candidates.stream()
              .filter(candidate -> candidate.task().getKind().releasesRepairPlace())
              .filter(candidate -> candidate.task().getRepairPlaceAllocationVersion() != null)
              .findFirst()
              .orElse(null);
      if (outbound != null) return outbound;
    }

    Candidate inbound = firstEligibleInbound(candidates, availableInboundSlots);
    if (inbound != null) return inbound;

    // If no inbound cabin can be reserved (for example all places are occupied), continue
    // evacuating ready cabins instead of deadlocking the queue.
    Candidate outbound =
        candidates.stream()
            .filter(candidate -> candidate.task().getKind().releasesRepairPlace())
            .filter(candidate -> candidate.task().getRepairPlaceAllocationVersion() != null)
            .findFirst()
            .orElse(null);
    if (outbound != null) return outbound;

    return candidates.stream()
        .filter(candidate -> !candidate.task().getKind().consumesRepairPlace())
        .filter(candidate -> !candidate.task().getKind().releasesRepairPlace())
        .findFirst()
        .orElse(null);
  }

  private Candidate firstEligibleInbound(
      List<Candidate> candidates, long availableInboundSlots) {
    return candidates.stream()
        .filter(candidate -> candidate.task().getKind().consumesRepairPlace())
        .filter(
            candidate ->
                candidate.task().getRepairPlaceAllocationVersion() != null
                    || availableInboundSlots > 0)
        .findFirst()
        .orElse(null);
  }

  private void promote(
      Candidate candidate,
      LogisticsDependencyGateway.RepairPlaceProjection repairPlaces) {
    DriverLogisticsTask task = candidate.task();
    if (task.getKind().consumesRepairPlace()
        && task.getRepairPlaceAllocationVersion() == null) {
      if (!inboundRepairPlaceAvailable(repairPlaces)) {
        throw new LogisticsConflictException(
            "На складе нет свободного или освобождаемого ремонтного места");
      }
      LogisticsDependencyGateway.RepairPlaceAllocation reservation =
          dependencies.transitionRepairPlace(
              derivedKey("reserve:" + task.getTaskBoardTaskVersion(), task.getId()),
              task.getWarehouseId(),
              task.getRepairId(),
              0,
              "reserve");
      store.confirmReservation(task.getId(), reservation);
    }

    LogisticsDependencyGateway.DriverBoardTask fresh =
        dependencies.readDriverTask(task.getExternalTaskId());
    if (!isMovableScheduledSnapshot(fresh, task.getWarehouseId())) {
      throw new LogisticsConflictException("Очередь перемещений уже изменилась");
    }
    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.setDriverTaskLane(
            fresh.externalTaskId(), fresh.taskVersion(), "CURRENT");
    if (task.getKind().releasesRepairPlace()) {
      current = insertRepairReturnAfterLeadingPins(task, current);
    }
    store.confirmCurrent(task.getId(), current);
  }

  /**
   * A completed repair is actionable immediately. It therefore leads the driver's waiting queue,
   * but never displaces the operator's leading pinned work (or task-board's protected active
   * prefix, which is enforced again by the downstream optimistic command).
   */
  private LogisticsDependencyGateway.DriverBoardTask insertRepairReturnAfterLeadingPins(
      DriverLogisticsTask task, LogisticsDependencyGateway.DriverBoardTask current) {
    LogisticsDependencyGateway.DriverBoardSnapshot board =
        dependencies.readDriverBoard(task.getWarehouseId());
    int targetIndex = 0;
    for (LogisticsDependencyGateway.DriverBoardTask candidate : board.current()) {
      if (task.getExternalTaskId().equals(candidate.externalTaskId())) break;
      if (!candidate.pinned()) break;
      targetIndex++;
    }
    if (current.queuePosition() == targetIndex) return current;
    return dependencies.moveDriverTask(
        current.externalTaskId(),
        current.taskVersion(),
        current.entryVersion(),
        "CURRENT",
        current.scheduledDate(),
        targetIndex);
  }

  private static void ensureManualPromotionCanUseCapacity(
      DriverLogisticsTask task,
      DriverLogisticsTask activeHeldTask,
      LogisticsDependencyGateway.RepairPlaceProjection repairPlaces) {
    if (!task.getKind().consumesRepairPlace()
        || task.getRepairPlaceAllocationVersion() != null
        || inboundRepairPlaceAvailable(repairPlaces)) {
      return;
    }
    boolean heldReservationCanBeReleased =
        activeHeldTask != null
            && activeHeldTask.getKind().consumesRepairPlace()
            && activeHeldTask.getRepairPlaceAllocationVersion() != null;
    if (!heldReservationCanBeReleased) {
      throw new LogisticsConflictException(
          "На складе нет свободного или освобождаемого ремонтного места");
    }
  }

  private static DriverLogisticsTask selectHeldTask(
      List<DriverLogisticsTask> heldTasks, UUID requestedTaskId) {
    return heldTasks.stream()
        .filter(candidate -> candidate.getId().equals(requestedTaskId))
        .findFirst()
        .orElseGet(() -> heldTasks.stream().findFirst().orElse(null));
  }

  private DriverLogisticsTask local(UUID externalTaskId) {
    return tasks.findByExternalTaskId(externalTaskId).orElse(null);
  }

  private Map<UUID, DriverLogisticsTask> localTasksByExternalId(UUID warehouseId) {
    Map<UUID, DriverLogisticsTask> result = new LinkedHashMap<>();
    for (DriverLogisticsTask task :
        tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId)) {
      result.put(task.getExternalTaskId(), task);
    }
    return result;
  }

  private boolean hasActiveManualPromotionHold(UUID warehouseId) {
    return tasks.existsByWarehouseIdAndStateAndManualPromotionHoldUntilAfter(
        warehouseId, DriverTaskState.SCHEDULED, now());
  }

  static boolean inboundRepairPlaceAvailable(
      LogisticsDependencyGateway.RepairPlaceProjection repairPlaces) {
    return schedulableInboundSlots(repairPlaces) > 0;
  }

  static long usedRepairPlaceCount(
      LogisticsDependencyGateway.RepairPlaceProjection repairPlaces) {
    return repairPlaces.occupiedCount()
        + Math.max(repairPlaces.reservedCount(), repairPlaces.readyToReleaseCount());
  }

  static long schedulableInboundSlots(
      LogisticsDependencyGateway.RepairPlaceProjection repairPlaces) {
    long pairedAfterRemoval =
        Math.max(0, repairPlaces.readyToReleaseCount() - repairPlaces.reservedCount());
    return repairPlaces.availableCount() + pairedAfterRemoval;
  }

  private DriverTaskKind currentTailKind(
      LogisticsDependencyGateway.DriverBoardSnapshot board) {
    if (board.current().isEmpty()) return null;
    return board.current().stream()
        .map(value -> local(value.externalTaskId()))
        .filter(java.util.Objects::nonNull)
        .map(DriverLogisticsTask::getKind)
        .reduce((left, right) -> right)
        .orElse(null);
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

  private void lockWarehouseQueue(UUID warehouseId) {
    tasks.acquireTransactionLock("driver-queue:" + warehouseId);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID derivedKey(String operation, UUID taskId) {
    return UUID.nameUUIDFromBytes(
        ("driver-task:" + operation + ":" + taskId).getBytes(StandardCharsets.UTF_8));
  }

  private record Candidate(
      DriverLogisticsTask task,
      LogisticsDependencyGateway.DriverBoardTask boardTask) {}

  private record ScheduledCandidate(
      DriverLogisticsTask task,
      LogisticsDependencyGateway.DriverBoardTask boardTask) {}

  private record ScheduledPlacement(ScheduledCandidate candidate, LocalDate date, int index) {}
}
