package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps consecutive work in a configured queue pair with the group that completed the preceding
 * stage. Completion history is the durable assignment evidence; the next timer starts only on TAKE.
 */
@Component
class LinkedQueueContinuationPolicy {
  private final QueueEntryRepository entries;
  private final WorkerQueuePlanPolicy workerPlan;

  LinkedQueueContinuationPolicy(QueueEntryRepository entries, WorkerQueuePlanPolicy workerPlan) {
    this.entries = entries;
    this.workerPlan = workerPlan;
  }

  /** Resolves one consistent continuation view for a feed or a warehouse-locked TAKE command. */
  Continuations load(UUID warehouseId) {
    Map<UUID, Reservation> byEntry = new LinkedHashMap<>();
    Map<UUID, Reservation> nextByGroup = new LinkedHashMap<>();
    for (var candidate : entries.findLinkedContinuations(warehouseId)) {
      QueueEntry entry = candidate.getEntry();
      var group = candidate.getWorkerGroup();
      Reservation reservation =
          new Reservation(
              entry.getId(),
              group.getId(),
              group.getName(),
              entry.getTask().getUnitNumber() == null
                  ? entry.getTask().getTitle()
                  : entry.getTask().getUnitNumber());
      Reservation previous = byEntry.putIfAbsent(entry.getId(), reservation);
      if (previous != null && !previous.groupId().equals(group.getId())) {
        throw new IllegalStateException("У связанного этапа несколько основных групп");
      }
      // A disabled or out-of-plan continuation cannot prevent the group from taking published work.
      if (!entry.getQueue().isHidden() && workerPlan.isVisible(entry)) {
        nextByGroup.putIfAbsent(group.getId(), reservation);
      }
    }
    return new Continuations(Map.copyOf(byEntry), Map.copyOf(nextByGroup));
  }

  /** Read-only continuation evidence shared by feed filtering and command admission. */
  record Continuations(Map<UUID, Reservation> byEntry, Map<UUID, Reservation> nextByGroup) {
    boolean canTake(UUID entryId, UUID groupId) {
      Reservation reservation = byEntry.get(entryId);
      if (reservation != null && !reservation.groupId().equals(groupId)) return false;
      Reservation next = groupId == null ? null : nextByGroup.get(groupId);
      return next == null || next.entryId().equals(entryId);
    }

    void requireTakeAllowed(UUID entryId, UUID groupId) {
      Reservation reservation = byEntry.get(entryId);
      if (reservation != null && !reservation.groupId().equals(groupId)) {
        throw new ConflictException(
            "Продолжение работ закреплено за группой «" + reservation.groupName() + "»");
      }
      Reservation next = groupId == null ? null : nextByGroup.get(groupId);
      if (next != null && !next.entryId().equals(entryId)) {
        throw new ConflictException(
            "Сначала продолжите работы по бытовке " + next.unitNumber() + " в связанной очереди");
      }
    }
  }

  /** Group identity and cabin label used to explain a continuation conflict. */
  record Reservation(UUID entryId, UUID groupId, String groupName, String unitNumber) {}
}
