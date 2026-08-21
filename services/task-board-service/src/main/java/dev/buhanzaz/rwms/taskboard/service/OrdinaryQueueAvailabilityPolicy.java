package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
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
 * <p>Active work remains at the front of its queue. Waiting work follows its aggregate queue
 * position, except that a pinned real card keeps its visible slot while an earlier shadow is
 * promoted; persisted schedule dates do not partition or order ordinary work. A route's first
 * unfinished entry is the only executable phase; later entries remain durable shadows until that
 * phase completes.
 */
final class OrdinaryQueueAvailabilityPolicy {
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private static final Comparator<QueueEntry> PRESENTATION_ORDER =
      Comparator.comparingInt(OrdinaryQueueAvailabilityPolicy::statusOrder)
          .thenComparingInt(OrdinaryQueueAvailabilityPolicy::entryTypeOrder)
          .thenComparingInt(OrdinaryQueueAvailabilityPolicy::pinOrder)
          .thenComparingInt(QueueEntry::getQueuePosition)
          .thenComparing(entry -> entry.getTask().getId().toString())
          .thenComparingInt(QueueEntry::getRouteIndex)
          .thenComparing(entry -> entry.getId().toString());

  private OrdinaryQueueAvailabilityPolicy() {}

  private static int statusOrder(QueueEntry entry) {
    return entry.getStatus() == EntryStatus.WAITING ? 1 : 0;
  }

  private static int entryTypeOrder(QueueEntry entry) {
    return entry.getEntryType() == EntryType.REAL ? 0 : 1;
  }

  private static int pinOrder(QueueEntry entry) {
    return entry.getEntryType() == EntryType.REAL
            && entry.getStatus() == EntryStatus.WAITING
            && entry.getTask().isPinned()
        ? 0
        : 1;
  }

  /**
   * Returns active and bounded waiting real cards before the selected tasks' future shadows.
   *
   * <p>A real card therefore skips inactive placeholders without destroying their persisted queue
   * positions. When an earlier shadow is promoted it resumes that earlier position; a pinned real
   * card remains ahead of newly promoted unpinned work.
   */
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
    queueEntries.stream()
        .filter(entry -> entry.getEntryType() == EntryType.SHADOW)
        .filter(entry -> entry.getStatus() == EntryStatus.WAITING)
        .forEach(result::add);
    return result.stream().sorted(PRESENTATION_ORDER).toList();
  }

  /** Returns the waiting real entries currently admitted by the configured queue window. */
  static List<QueueEntry> availableWaitingEntries(
      Collection<QueueEntry> queueEntries, int waitingLimit) {
    requireLimit(waitingLimit);
    return queueEntries.stream()
        .filter(entry -> entry.getEntryType() == EntryType.REAL)
        .filter(entry -> entry.getStatus() == EntryStatus.WAITING)
        .sorted(PRESENTATION_ORDER)
        .limit(waitingLimit)
        .toList();
  }

  /**
   * Chooses the next executable route step after excluding entries completed by the current
   * atomic command. The persisted route order is the sole phase precedence.
   */
  static QueueEntry nextExecutableRouteEntry(
      Collection<QueueEntry> route, Collection<UUID> excludedEntryIds) {
    List<QueueEntry> unfinished =
        route.stream()
            .filter(entry -> !excludedEntryIds.contains(entry.getId()))
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .toList();
    return unfinished.stream().min(Comparator.comparingInt(QueueEntry::getRouteIndex)).orElse(null);
  }

  private static void requireLimit(int waitingLimit) {
    if (waitingLimit < 1 || waitingLimit > 50) {
      throw new IllegalArgumentException("Лимит доступных заданий должен быть от 1 до 50");
    }
  }
}
