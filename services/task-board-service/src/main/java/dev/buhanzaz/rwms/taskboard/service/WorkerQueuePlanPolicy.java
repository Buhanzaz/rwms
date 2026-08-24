package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Applies one warehouse queue's publication switch and bounded waiting-work plan to WorkerApp.
 *
 * <p>A disabled queue is absent from WorkerApp even when it contains active work. In an enabled
 * queue every active real entry remains visible, waiting shadows are never published, and the
 * configured limit counts only waiting real entries in canonical board order. Manager and
 * DriverApp projections do not use this policy.
 */
@Component
class WorkerQueuePlanPolicy {
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final QueueEntryRepository entries;

  WorkerQueuePlanPolicy(QueueEntryRepository entries) {
    this.entries = entries;
  }

  /** Returns the WorkerApp-visible subset of one already SES-gated ordinary queue. */
  List<QueueEntry> visibleEntries(WorkQueue queue, Collection<QueueEntry> candidates) {
    if (!isPublished(queue)) return List.of();

    int waitingCount = 0;
    List<QueueEntry> result = new ArrayList<>();
    for (QueueEntry entry : OrdinaryQueueAvailabilityPolicy.orderedEntries(candidates)) {
      if (entry.getEntryType() != EntryType.REAL) continue;
      if (entry.getStatus() == EntryStatus.IN_PROGRESS || entry.getStatus() == EntryStatus.PAUSED) {
        result.add(entry);
      } else if (entry.getStatus() == EntryStatus.WAITING
          && waitingCount++ < queue.getAvailableTaskLimit()) {
        result.add(entry);
      }
    }
    return List.copyOf(result);
  }

  /** Returns whether a direct WorkerApp lookup is still inside the current publication window. */
  boolean isVisible(QueueEntry entry) {
    WorkQueue queue = entry.getQueue();
    if (queue == null || queue.getPurpose() != QueuePurpose.GENERAL) return true;
    if (!isPublished(queue)
        || entry.getEntryType() != EntryType.REAL
        || !UNFINISHED.contains(entry.getStatus())) {
      return false;
    }
    if (!RepairRoutePhaseOrder.isSesQueue(queue)
        && entries.findAllByTaskIdOrderByRouteIndexAsc(entry.getTask().getId()).stream()
            .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
            .anyMatch(candidate -> RepairRoutePhaseOrder.isSesQueue(candidate.getQueue()))) {
      return false;
    }
    if (entry.getStatus() == EntryStatus.IN_PROGRESS
        || entry.getStatus() == EntryStatus.PAUSED) {
      return true;
    }
    List<QueueEntry> candidates =
        entries.findAllActiveByQueueIdAndStatusIn(
            queue.getId(), TaskStatus.ACTIVE, UNFINISHED);
    return visibleEntries(queue, candidates).stream()
        .anyMatch(candidate -> candidate.getId().equals(entry.getId()));
  }

  /** Captures all ordinary entry IDs currently discoverable through WorkerApp in one warehouse. */
  Set<UUID> visibleEntryIds(UUID warehouseId) {
    List<QueueEntry> allEntries =
        entries.findAllUnfinishedOrdinaryByWarehouseId(
            warehouseId, TaskStatus.ACTIVE, UNFINISHED, QueuePurpose.LOGISTICS_DRIVER);
    Set<UUID> sesTaskIds =
        allEntries.stream()
            .filter(entry -> RepairRoutePhaseOrder.isSesQueue(entry.getQueue()))
            .map(entry -> entry.getTask().getId())
            .collect(java.util.stream.Collectors.toSet());
    Map<WorkQueue, List<QueueEntry>> byQueue =
        allEntries.stream()
            .filter(
                entry ->
                    !sesTaskIds.contains(entry.getTask().getId())
                        || RepairRoutePhaseOrder.isSesQueue(entry.getQueue()))
            .collect(java.util.stream.Collectors.groupingBy(QueueEntry::getQueue));
    Set<UUID> result = new LinkedHashSet<>();
    byQueue.forEach(
        (queue, candidates) ->
            visibleEntries(queue, candidates).forEach(entry -> result.add(entry.getId())));
    return Set.copyOf(result);
  }

  /** Rejects a stale or crafted WorkerApp TAKE outside the current warehouse plan. */
  void requireTakeAllowed(QueueEntry entry) {
    if (!isVisible(entry)) {
      throw new ConflictException("Задание не входит в активный план WorkerApp");
    }
  }

  /** Returns whether an ordinary queue is currently published to WorkerApp. */
  boolean isPublished(WorkQueue queue) {
    return queue.getPurpose() != QueuePurpose.GENERAL || queue.isWorkerFeedEnabled();
  }
}
