package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Defines the deterministic presentation order of an ordinary task-board queue.
 *
 * <p>Active work remains at the front of its queue. Waiting work follows its aggregate queue
 * position, except that a pinned real card keeps its visible slot while an earlier shadow is
 * promoted; persisted schedule dates do not partition or order ordinary work. This policy never
 * truncates the backlog: a route's first unfinished entry is executable by default, while a
 * manager may explicitly make another future entry executable in parallel. WorkerApp publication
 * and the mandatory SES gate are separate server policies layered over this complete canonical
 * order.
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
   * Returns every unfinished real and shadow entry in stable queue presentation order.
   *
   * <p>A promoted real card resumes its persisted queue position, except that an explicitly pinned
   * waiting real remains ahead. Manager surfaces receive this complete list; the server applies
   * the warehouse-local WorkerApp plan to the native worker feed and TAKE admission separately.
   */
  static List<QueueEntry> orderedEntries(Collection<QueueEntry> queueEntries) {
    return queueEntries.stream()
        .filter(entry -> UNFINISHED.contains(entry.getStatus()))
        .sorted(PRESENTATION_ORDER)
        .toList();
  }

  /**
   * Chooses the earliest unfinished route step after excluding entries completed by the current
   * atomic command. Completion uses this step for default promotion; manager-promoted future real
   * entries may already be executable independently.
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
}
