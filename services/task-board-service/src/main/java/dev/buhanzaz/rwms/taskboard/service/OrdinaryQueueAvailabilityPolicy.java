package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Defines the single deterministic availability order shared by ordinary board reads and TAKE.
 *
 * <p>Active work remains at the front of its queue. Waiting work follows by priority and aggregate
 * queue position; persisted schedule dates do not partition or order ordinary work. A task with
 * unfinished HOLDING work is gated by its first such step even when that step appears later in the
 * source route; this keeps SES work exclusive without deleting the route truth retained as
 * shadows.
 */
final class OrdinaryQueueAvailabilityPolicy {
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private static final Comparator<QueueEntry> CANONICAL_ORDER =
      Comparator.comparingInt(OrdinaryQueueAvailabilityPolicy::statusOrder)
          .thenComparingInt(
              entry ->
                  entry.getStatus() == EntryStatus.WAITING
                      ? entry.getTask().getPriority()
                      : 0)
          .thenComparingInt(QueueEntry::getQueuePosition)
          .thenComparing(entry -> entry.getTask().getId().toString())
          .thenComparingInt(QueueEntry::getRouteIndex)
          .thenComparing(entry -> entry.getId().toString());

  private OrdinaryQueueAvailabilityPolicy() {}

  private static int statusOrder(QueueEntry entry) {
    return entry.getStatus() == EntryStatus.WAITING ? 1 : 0;
  }

  /** Returns the initial route gate, preferring the first HOLDING queue over source order. */
  static int initialRouteGateIndex(List<WorkQueue> routeQueues) {
    for (int index = 0; index < routeQueues.size(); index++) {
      WorkQueue queue = routeQueues.get(index);
      if (queue != null && queue.getType() == QueueType.HOLDING) {
        return index;
      }
    }
    return 0;
  }

  /** Returns active real cards plus only the first configured waiting window in canonical order. */
  static List<QueueEntry> visibleEntries(Collection<QueueEntry> queueEntries, int waitingLimit) {
    requireLimit(waitingLimit);
    List<QueueEntry> result = new ArrayList<>();
    queueEntries.stream()
        .filter(entry -> entry.getEntryType() == EntryType.REAL)
        .filter(
            entry ->
                entry.getStatus() == EntryStatus.IN_PROGRESS
                    || entry.getStatus() == EntryStatus.PAUSED)
        .forEach(result::add);
    result.addAll(availableWaitingEntries(queueEntries, waitingLimit));
    return result.stream().sorted(CANONICAL_ORDER).toList();
  }

  /** Returns the waiting real entries currently admitted by the configured queue window. */
  static List<QueueEntry> availableWaitingEntries(
      Collection<QueueEntry> queueEntries, int waitingLimit) {
    requireLimit(waitingLimit);
    return queueEntries.stream()
        .filter(entry -> entry.getEntryType() == EntryType.REAL)
        .filter(entry -> entry.getStatus() == EntryStatus.WAITING)
        .sorted(CANONICAL_ORDER)
        .limit(waitingLimit)
        .toList();
  }

  /**
   * Chooses the next executable route step after excluding entries completed by the current
   * atomic command. HOLDING always wins over an earlier ordinary step.
   */
  static QueueEntry nextExecutableRouteEntry(
      Collection<QueueEntry> route, Collection<UUID> excludedEntryIds) {
    List<QueueEntry> unfinished =
        route.stream()
            .filter(entry -> !excludedEntryIds.contains(entry.getId()))
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .toList();
    return unfinished.stream()
        .filter(
            entry ->
                entry.getQueue() != null
                    && entry.getQueue().getType() == QueueType.HOLDING)
        .min(Comparator.comparingInt(QueueEntry::getRouteIndex))
        .orElseGet(
            () ->
                unfinished.stream()
                    .min(Comparator.comparingInt(QueueEntry::getRouteIndex))
                    .orElse(null));
  }

  private static void requireLimit(int waitingLimit) {
    if (waitingLimit < 1 || waitingLimit > 50) {
      throw new IllegalArgumentException("Лимит доступных заданий должен быть от 1 до 50");
    }
  }
}
