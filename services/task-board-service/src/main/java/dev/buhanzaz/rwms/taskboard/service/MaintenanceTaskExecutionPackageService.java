package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves one worker-executable maintenance package from consecutive route entries in the same
 * physical queue.
 *
 * <p>Maintenance keeps one route entry per repair stage for source reconciliation. WorkerApp,
 * however, executes a consecutive same-queue segment as one unit: its detail includes every work
 * and material snapshot in the segment and one completion closes all still-unfinished members.
 * Other task sources retain entry-by-entry execution.
 */
@Service
final class MaintenanceTaskExecutionPackageService {
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final TaskSyncSourceRepository sources;

  MaintenanceTaskExecutionPackageService(TaskSyncSourceRepository sources) {
    this.sources = sources;
  }

  /** Returns the route segment whose content is presented with the selected worker entry. */
  List<RegisteredRouteStepDto> detailSteps(
      BoardEntryDto entry, BoardTaskRegistrationDto registration) {
    if (registration == null || registration.route() == null) return List.of();
    List<RegisteredRouteStepDto> route = registration.route();
    int currentIndex = dtoIndexOf(route, entry.id());
    if (currentIndex < 0) return List.of();
    if (!isMaintenance(entry.source())) return List.of(route.get(currentIndex));

    int start = dtoSegmentStart(route, currentIndex);
    int end = dtoSegmentEnd(route, currentIndex);
    return List.copyOf(route.subList(start, end + 1));
  }

  /**
   * Returns the representative entry and all later unfinished members completed by its command.
   * Earlier members of the same segment must already be terminal, preserving route order.
   */
  List<QueueEntry> unfinishedExecutionEntries(
      QueueEntry current, List<QueueEntry> orderedRoute) {
    if (!isMaintenance(current.getTask().getId())) return List.of(current);
    int currentIndex = entityIndexOf(orderedRoute, current.getId());
    if (currentIndex < 0) {
      throw new IllegalStateException("Текущий этап отсутствует в маршруте задания");
    }

    int start = entitySegmentStart(orderedRoute, currentIndex);
    int end = entitySegmentEnd(orderedRoute, currentIndex);
    for (int index = start; index < currentIndex; index++) {
      if (UNFINISHED.contains(orderedRoute.get(index).getStatus())) {
        throw new ConflictException("Сначала завершите предыдущую часть пакета работ");
      }
    }

    List<QueueEntry> result = new ArrayList<>();
    result.add(current);
    for (int index = currentIndex + 1; index <= end; index++) {
      QueueEntry candidate = orderedRoute.get(index);
      if (!UNFINISHED.contains(candidate.getStatus())) continue;
      if (candidate.getStatus() != EntryStatus.WAITING
          || candidate.getEntryType() != EntryType.SHADOW) {
        throw new ConflictException(
            "Следующая часть пакета работ уже исполняется отдельно");
      }
      result.add(candidate);
    }
    return List.copyOf(result);
  }

  /** Returns the remaining package budget assigned to the representative KPI segment. */
  Long budgetSeconds(List<QueueEntry> unfinishedEntries) {
    long total = 0;
    boolean hasBudget = false;
    for (QueueEntry entry : unfinishedEntries) {
      if (entry.getCurrentBudgetSeconds() == null) continue;
      total = Math.addExact(total, entry.getCurrentBudgetSeconds());
      hasBudget = true;
    }
    return hasBudget ? total : null;
  }

  /** Returns the displayed duration of all unfinished members in the selected package. */
  Integer plannedDurationMinutes(
      BoardEntryDto current, List<RegisteredRouteStepDto> packageSteps) {
    if (packageSteps.isEmpty() || isTerminal(current.status())) {
      return current.plannedDurationMinutes();
    }
    int total = 0;
    boolean hasDuration = false;
    for (RegisteredRouteStepDto step : packageSteps) {
      if (!UNFINISHED.contains(step.status()) || step.plannedDurationMinutes() == null) continue;
      total = Math.addExact(total, step.plannedDurationMinutes());
      hasDuration = true;
    }
    if (hasDuration) return total;
    return current.plannedDurationMinutes();
  }

  /** Recalculates the worker timer against the unfinished package's combined duration. */
  TaskTimerSnapshot timerSnapshot(
      BoardEntryDto current,
      List<RegisteredRouteStepDto> packageSteps,
      Integer packageDurationMinutes) {
    TaskTimerSnapshot timer = current.timerSnapshot();
    if (timer == null
        || packageSteps.size() < 2
        || packageDurationMinutes == null
        || isTerminal(current.status())) {
      return timer;
    }
    long budgetSeconds = Math.multiplyExact(packageDurationMinutes.longValue(), 60L);
    long remainingSeconds = Math.subtractExact(budgetSeconds, timer.countedActiveSeconds());
    BigDecimal remainingPercent =
        BigDecimal.valueOf(remainingSeconds)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(budgetSeconds), 2, RoundingMode.HALF_UP);
    return new TaskTimerSnapshot(
        timer.countedActiveSeconds(),
        remainingSeconds,
        remainingPercent,
        timer.timerState(),
        timer.nextTransitionAt(),
        timer.serverTime());
  }

  private boolean isMaintenance(UUID taskId) {
    return sources
        .findById(taskId)
        .map(source -> source.getSourceType() == TaskSourceType.MAINTENANCE_REPAIR)
        .orElse(false);
  }

  private static boolean isMaintenance(TaskSourceReferenceDto source) {
    return source != null && source.type() == TaskSourceType.MAINTENANCE_REPAIR;
  }

  private static boolean isTerminal(EntryStatus status) {
    return status == EntryStatus.DONE || status == EntryStatus.CANCELLED;
  }

  private static int dtoIndexOf(List<RegisteredRouteStepDto> route, UUID entryId) {
    for (int index = 0; index < route.size(); index++) {
      if (route.get(index).entryId().equals(entryId)) return index;
    }
    return -1;
  }

  private static int dtoSegmentStart(
      List<RegisteredRouteStepDto> route, int currentIndex) {
    UUID queueId = route.get(currentIndex).workQueueId();
    int start = currentIndex;
    while (start > 0 && Objects.equals(route.get(start - 1).workQueueId(), queueId)) start--;
    return start;
  }

  private static int dtoSegmentEnd(
      List<RegisteredRouteStepDto> route, int currentIndex) {
    UUID queueId = route.get(currentIndex).workQueueId();
    int end = currentIndex;
    while (end + 1 < route.size()
        && Objects.equals(route.get(end + 1).workQueueId(), queueId)) end++;
    return end;
  }

  private static int entityIndexOf(List<QueueEntry> route, UUID entryId) {
    for (int index = 0; index < route.size(); index++) {
      if (route.get(index).getId().equals(entryId)) return index;
    }
    return -1;
  }

  private static int entitySegmentStart(List<QueueEntry> route, int currentIndex) {
    UUID queueId = route.get(currentIndex).getQueue().getId();
    int start = currentIndex;
    while (start > 0
        && Objects.equals(route.get(start - 1).getQueue().getId(), queueId)) start--;
    return start;
  }

  private static int entitySegmentEnd(List<QueueEntry> route, int currentIndex) {
    UUID queueId = route.get(currentIndex).getQueue().getId();
    int end = currentIndex;
    while (end + 1 < route.size()
        && Objects.equals(route.get(end + 1).getQueue().getId(), queueId)) end++;
    return end;
  }
}
