package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.ReorderBoardEntryRequest;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reorders only unpinned waiting real cards inside one ordinary physical queue.
 *
 * <p>Active work, route shadows and pinned slots remain untouched. The command serializes all
 * warehouse queue mutations, checks the entry version, queue version and observed target-card
 * identity, persists only changed card positions, and appends entry plus queue facts in the same
 * caller transaction.
 */
@Service
class TaskBoardEntryOrderingService {
  private final QueueEntryRepository entries;
  private final WorkQueueRepository queues;
  private final TaskBoardQueuePositionCoordinator queuePositions;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;

  TaskBoardEntryOrderingService(
      QueueEntryRepository entries,
      WorkQueueRepository queues,
      TaskBoardQueuePositionCoordinator queuePositions,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter) {
    this.entries = entries;
    this.queues = queues;
    this.queuePositions = queuePositions;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
  }

  /** Applies a target index in the queue's complete reorderable-card list. */
  void reorder(UUID warehouseId, UUID entryId, ReorderBoardEntryRequest request) {
    queuePositions.lockQueueMutation(warehouseId);
    QueueEntry source =
        entries
            .findByIdForUpdate(entryId)
            .orElseThrow(() -> new NotFoundException("Этап не найден"));
    if (!warehouseId.equals(source.getTask().getWarehouseId())) {
      throw new NotFoundException("Этап не найден");
    }
    checkVersion(source.getVersion(), request.expectedEntryVersion(), "Этап");
    requireReorderable(source);

    WorkQueue queue =
        queues
            .findByIdForUpdateWithDefinition(source.getQueue().getId())
            .orElseThrow(() -> new NotFoundException("Очередь не найдена"));
    checkVersion(queue.getVersion(), request.expectedQueueVersion(), "Очередь");

    List<QueueEntry> current = reorderableEntries(queue);
    int sourceIndex = current.indexOf(source);
    if (sourceIndex < 0) {
      throw new ConflictException("Этап больше нельзя переставить");
    }
    if (request.targetIndex() >= current.size()) {
      throw new ConflictException("Позиция перестановки вышла за границы очереди");
    }
    QueueEntry target = current.get(request.targetIndex());
    if (!target.getId().equals(request.targetEntryId())) {
      throw new ConflictException("Очередь изменилась; обновите доску перед перестановкой");
    }
    if (sourceIndex == request.targetIndex()) return;

    Map<dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore.StreamRef, Long> streamVersions =
        queuePositions.lockEntryStreams(current);
    long queueStreamVersion =
        eventSourcing.lock(TaskBoardAggregateType.WORK_QUEUE, queue.getId());
    List<Integer> positions =
        current.stream().map(QueueEntry::getQueuePosition).sorted().toList();
    List<QueueEntry> reordered = new ArrayList<>(current);
    reordered.remove(sourceIndex);
    reordered.add(request.targetIndex(), source);

    List<QueueEntry> changed = new ArrayList<>();
    for (int index = 0; index < reordered.size(); index++) {
      QueueEntry candidate = reordered.get(index);
      int position = positions.get(index);
      if (candidate.getQueuePosition() == position) continue;
      candidate.setQueuePosition(position);
      candidate.touch();
      projectionWriter.save(entries, candidate);
      changed.add(candidate);
    }
    queue.touch();
    queue = projectionWriter.save(queues, queue);
    projectionWriter.flush();
    for (QueueEntry candidate : changed) {
      eventSourcing.entryChanged(
          candidate,
          queuePositions.streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, candidate.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_MOVED);
    }
    eventSourcing.queueChanged(
        queue, queueStreamVersion, TaskBoardEventTypes.WORK_QUEUE_REORDERED);
  }

  private List<QueueEntry> reorderableEntries(WorkQueue queue) {
    return OrdinaryQueueAvailabilityPolicy.orderedEntries(
            entries.findAllActiveByQueueIdAndStatusIn(
                queue.getId(), TaskStatus.ACTIVE, Set.of(EntryStatus.WAITING)))
        .stream()
        .filter(entry -> entry.getEntryType() == EntryType.REAL)
        .filter(entry -> !entry.getTask().isPinned())
        .sorted(
            Comparator.comparingInt(QueueEntry::getQueuePosition)
                .thenComparing(entry -> entry.getId().toString()))
        .toList();
  }

  private void requireReorderable(QueueEntry entry) {
    if (entry.getQueue() == null
        || entry.getQueue().getPurpose() != QueuePurpose.GENERAL
        || entry.getStatus() != EntryStatus.WAITING
        || entry.getEntryType() != EntryType.REAL
        || entry.getTask().isPinned()) {
      throw new ConflictException(
          "Переставить можно только незакреплённый ожидающий реальный этап");
    }
  }
}
