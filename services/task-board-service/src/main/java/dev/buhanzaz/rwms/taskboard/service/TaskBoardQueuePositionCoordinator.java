package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Serializes and applies persisted queue-position mutations.
 *
 * <p>This is a narrow technical coordinator: it owns advisory locks, stream fences, pinned-slot
 * restoration and position normalization. It neither validates domain ownership nor creates a
 * task, so all authoritative board state remains in the task-board repositories.
 */
@Service
class TaskBoardQueuePositionCoordinator {
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final QueueEntryRepository entries;
  private final JdbcTemplate jdbc;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;

  TaskBoardQueuePositionCoordinator(
      QueueEntryRepository entries,
      JdbcTemplate jdbc,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter) {
    this.entries = entries;
    this.jdbc = jdbc;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
  }

  void normalizeCurrentLogisticsPositions(
      UUID warehouseId, Collection<QueueEntry> routeEntries) {
    WorkQueue queue =
        routeEntries.stream()
            .map(QueueEntry::getQueue)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    if (queue == null || queue.getPurpose() != QueuePurpose.LOGISTICS_DRIVER) return;
    List<QueueEntry> current =
        entries.findAllByQueueIdOrderByQueuePositionAsc(queue.getId()).stream()
            .filter(value -> value.getTask().getLane() == TaskLane.CURRENT)
            .filter(value -> UNFINISHED.contains(value.getStatus()))
            .sorted(
                Comparator.comparingInt(QueueEntry::getQueuePosition)
                    .thenComparing(value -> value.getTask().getId().toString()))
            .toList();
    int position = 0;
    for (QueueEntry value : current) {
      value.setQueuePosition(position++);
      projectionWriter.save(entries, value);
    }
  }

  void lockQueuePositions(UUID warehouseId, List<WorkQueue> routeQueues) {
    routeQueues.stream()
        .map(queue -> queue.getId().toString())
        .distinct()
        .sorted()
        .forEach(key -> lock("queue-position:" + key));
  }

  /**
   * Acquires the transaction-scoped warehouse fence before queue and event-stream locks.
   *
   * <p>Callers take this lock first, then {@link #lockQueuePositions(UUID, List)} (which sorts
   * queue keys), and finally any stream fences. That single order prevents two multi-queue
   * mutations from deadlocking while leaving authorization, source and status decisions to their
   * domain owner.
   */
  void lockQueueMutation(UUID warehouseId) {
    lock("queue-position:warehouse:" + warehouseId);
  }

  private void lock(String key) {
    String database =
        jdbc.execute(
            (ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
    if (database != null && "PostgreSQL".equalsIgnoreCase(database.trim())) {
      jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, key);
    }
  }

  Map<TaskBoardEventStore.StreamRef, Long> lockEntryStreams(
      Collection<QueueEntry> values) {
    return eventSourcing.lockStreams(
        values.stream()
            .map(
                value ->
                    new TaskBoardEventStore.StreamRef(
                        TaskBoardAggregateType.QUEUE_ENTRY, value.getId()))
            .toList());
  }

  Map<TaskBoardEventStore.StreamRef, Long> lockTaskAndEntryStreams(
      BoardTask task, Collection<QueueEntry> values) {
    List<TaskBoardEventStore.StreamRef> streams = new ArrayList<>();
    streams.add(
        new TaskBoardEventStore.StreamRef(TaskBoardAggregateType.BOARD_TASK, task.getId()));
    values.stream()
        .map(
            value ->
                new TaskBoardEventStore.StreamRef(
                    TaskBoardAggregateType.QUEUE_ENTRY, value.getId()))
        .forEach(streams::add);
    return eventSourcing.lockStreams(streams);
  }

  long streamVersion(
      Map<TaskBoardEventStore.StreamRef, Long> versions,
      TaskBoardAggregateType aggregateType,
      UUID aggregateId) {
    Long version = versions.get(new TaskBoardEventStore.StreamRef(aggregateType, aggregateId));
    if (version == null) {
      throw new IllegalStateException(
          "No locked event stream for " + aggregateType + "/" + aggregateId);
    }
    return version;
  }

  Map<UUID, QueueEntryPosition> positionsOf(Collection<QueueEntry> values) {
    return values.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                QueueEntry::getId,
                value -> new QueueEntryPosition(id(value.getQueue()), value.getQueuePosition()),
                (left, right) -> left,
                java.util.LinkedHashMap::new));
  }

  Set<UUID> changedPositionIds(
      Collection<QueueEntry> values, Map<UUID, QueueEntryPosition> before) {
    Set<UUID> result = new LinkedHashSet<>();
    for (QueueEntry value : values) {
      QueueEntryPosition old = before.get(value.getId());
      QueueEntryPosition current =
          new QueueEntryPosition(id(value.getQueue()), value.getQueuePosition());
      if (!Objects.equals(old, current)) result.add(value.getId());
    }
    return result;
  }


  int nextPosition(UUID warehouseId, WorkQueue q, LocalDate scheduledDate) {
    return orderedEntries(warehouseId, q, scheduledDate).stream()
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .mapToInt(QueueEntry::getQueuePosition)
            .max()
            .orElse(-1)
        + 1;
  }

  void normalizePositions(UUID warehouseId, WorkQueue q) {
    if (q.getPurpose() != QueuePurpose.LOGISTICS_DRIVER) {
      int position = 0;
      for (QueueEntry entry : orderedEntries(warehouseId, q).stream()
          .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
          .sorted(queuePositionOrder())
          .toList()) {
        entry.setQueuePosition(position++);
        projectionWriter.save(entries, entry);
      }
      return;
    }
    Map<LocalDate, List<QueueEntry>> byDate =
        orderedEntries(warehouseId, q).stream()
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .filter(entry -> entry.getTask().getLane() == TaskLane.SCHEDULED)
            .collect(
                java.util.stream.Collectors.groupingBy(
                    entry -> entry.getTask().getScheduledDate(),
                    java.util.LinkedHashMap::new,
                    java.util.stream.Collectors.toList()));
    for (List<QueueEntry> datedEntries : byDate.values()) {
      int position = 0;
      for (QueueEntry entry : datedEntries) {
        entry.setQueuePosition(position++);
        projectionWriter.save(entries, entry);
      }
    }
    normalizeCurrentLogisticsPositions(warehouseId, orderedEntries(warehouseId, q));
  }

  void insertAtPosition(
      UUID warehouseId,
      QueueEntry entry,
      WorkQueue target,
      LocalDate targetDate,
      int requestedIndex,
      Set<UUID> protectedTaskIds) {
    insertAtPosition(
        warehouseId,
        List.of(entry),
        target,
        targetDate,
        requestedIndex,
        protectedTaskIds);
  }

  void insertAtPosition(
      UUID warehouseId,
      List<QueueEntry> routeEntries,
      WorkQueue target,
      LocalDate targetDate,
      int requestedIndex,
      Set<UUID> protectedTaskIds) {
    Set<UUID> routeEntryIds =
        routeEntries.stream().map(QueueEntry::getId).collect(java.util.stream.Collectors.toSet());
    var ordered =
        new ArrayList<>(
            orderedEntries(warehouseId, target, targetDate).stream()
                .filter(e -> !routeEntryIds.contains(e.getId()) && UNFINISHED.contains(e.getStatus()))
                .toList());
    int firstMovable = 0;
    for (int position = 0; position < ordered.size(); position++) {
      QueueEntry candidate = ordered.get(position);
      if (protectedTaskIds.contains(candidate.getTask().getId())) {
        firstMovable = position + 1;
      }
    }
    int index = Math.max(firstMovable, Math.min(requestedIndex, ordered.size()));
    List<QueueEntry> orderedRouteEntries =
        routeEntries.stream().sorted(Comparator.comparingInt(QueueEntry::getRouteIndex)).toList();
    ordered.addAll(index, orderedRouteEntries);
    orderedRouteEntries.forEach(routeEntry -> routeEntry.setQueue(target));
    int position = 0;
    for (var current : ordered) {
      current.setQueuePosition(position++);
      projectionWriter.save(entries, current);
    }
  }

  /**
   * Captures the absolute slots occupied by pinned cards before a user-directed queue mutation.
   * Ordinary queue positions are warehouse-aggregate; logistics positions remain scoped by date
   * and lane, so a pin in a scheduled logistics column never reserves a slot in CURRENT.
   */
  Map<QueuePositionScope, List<PinnedQueueOrdinal>> pinnedQueueOrdinals(
      Collection<QueueEntry> candidates) {
    Map<QueuePositionScope, List<QueueEntry>> entriesByScope = new java.util.LinkedHashMap<>();
    for (QueueEntry candidate : candidates) {
      if (candidate.getQueue() == null || !UNFINISHED.contains(candidate.getStatus())) continue;
      entriesByScope
          .computeIfAbsent(queuePositionScope(candidate), ignored -> new ArrayList<>())
          .add(candidate);
    }

    Map<QueuePositionScope, List<PinnedQueueOrdinal>> result = new java.util.LinkedHashMap<>();
    for (Map.Entry<QueuePositionScope, List<QueueEntry>> scope : entriesByScope.entrySet()) {
      List<QueueEntry> ordered =
          scope.getValue().stream().sorted(queuePositionOrder()).toList();
      List<PinnedQueueOrdinal> pinned = new ArrayList<>();
      for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
        QueueEntry entry = ordered.get(ordinal);
        if (entry.getTask().isPinned()) {
          pinned.add(new PinnedQueueOrdinal(entry.getId(), ordinal));
        }
      }
      if (!pinned.isEmpty()) {
        result.put(scope.getKey(), List.copyOf(pinned));
      }
    }
    return result;
  }

  /**
   * Reinstates pinned cards in the slots they occupied before a manual move or priority insertion.
   * The non-pinned cards retain their requested order and fill the remaining slots. If a command
   * removes so many cards that an old ordinal can no longer exist, the pin moves only as far as is
   * mathematically necessary; ordinary completion intentionally does not call this method and is
   * therefore allowed to advance the queue naturally.
   */
  void restorePinnedQueueOrdinals(
      Map<QueuePositionScope, List<PinnedQueueOrdinal>> pinnedOrdinals) {
    for (Map.Entry<QueuePositionScope, List<PinnedQueueOrdinal>> scope : pinnedOrdinals.entrySet()) {
      List<QueueEntry> ordered = orderedEntries(scope.getKey());
      if (ordered.isEmpty()) continue;

      Map<UUID, QueueEntry> entriesById = new java.util.LinkedHashMap<>();
      ordered.forEach(entry -> entriesById.put(entry.getId(), entry));
      List<PinnedQueueOrdinal> presentPins =
          scope.getValue().stream()
              .filter(pin -> entriesById.containsKey(pin.entryId()))
              .sorted(
                  Comparator.comparingInt(PinnedQueueOrdinal::ordinal)
                      .thenComparing(pin -> pin.entryId().toString()))
              .toList();
      if (presentPins.isEmpty()) continue;

      List<QueueEntry> restored = new ArrayList<>(java.util.Collections.nCopies(ordered.size(), null));
      Set<UUID> pinnedEntryIds =
          presentPins.stream()
              .map(PinnedQueueOrdinal::entryId)
              .collect(java.util.stream.Collectors.toSet());
      List<QueueEntry> unpinned =
          ordered.stream().filter(entry -> !pinnedEntryIds.contains(entry.getId())).toList();

      int nextSlot = 0;
      for (int index = 0; index < presentPins.size(); index++) {
        PinnedQueueOrdinal pin = presentPins.get(index);
        int latestAvailableSlot = ordered.size() - (presentPins.size() - index);
        int slot = Math.max(nextSlot, Math.min(pin.ordinal(), latestAvailableSlot));
        restored.set(slot, entriesById.get(pin.entryId()));
        nextSlot = slot + 1;
      }

      int unpinnedIndex = 0;
      for (int index = 0; index < restored.size(); index++) {
        if (restored.get(index) == null) {
          restored.set(index, unpinned.get(unpinnedIndex++));
        }
      }
      for (int index = 0; index < restored.size(); index++) {
        QueueEntry entry = restored.get(index);
        if (entry.getQueuePosition() != index) {
          entry.setQueuePosition(index);
          projectionWriter.save(entries, entry);
        }
      }
    }
  }

  private List<QueueEntry> orderedEntries(QueuePositionScope scope) {
    return entries.findAllByQueueIdOrderByQueuePositionAsc(scope.queueId()).stream()
        .filter(entry -> UNFINISHED.contains(entry.getStatus()))
        .filter(entry -> queuePositionScope(entry).equals(scope))
        .sorted(queuePositionOrder())
        .toList();
  }

  private QueuePositionScope queuePositionScope(QueueEntry entry) {
    WorkQueue queue = entry.getQueue();
    TaskLane lane =
        queue.getPurpose() == QueuePurpose.LOGISTICS_DRIVER
            ? entry.getTask().getLane()
            : TaskLane.SCHEDULED;
    LocalDate scheduledDate =
        queue.getPurpose() == QueuePurpose.LOGISTICS_DRIVER
            ? entry.getTask().getScheduledDate()
            : null;
    return new QueuePositionScope(queue.getId(), scheduledDate, lane);
  }

  private Comparator<QueueEntry> queuePositionOrder() {
    return Comparator.comparingInt(QueueEntry::getQueuePosition)
        .thenComparing(entry -> entry.getId().toString());
  }

  Set<UUID> protectedTaskIds(Collection<QueueEntry> candidates) {
    return candidates.stream()
        .map(candidate -> candidate.getTask().getId())
        .distinct()
        .filter(
            taskId ->
                entries.findAllByTaskIdOrderByRouteIndexAsc(taskId).stream()
                    .anyMatch(
                        routeEntry ->
                            routeEntry.getStatus() == EntryStatus.IN_PROGRESS
                                || routeEntry.getStatus() == EntryStatus.PAUSED))
        .collect(java.util.stream.Collectors.toSet());
  }

  List<QueueEntry> orderedEntries(UUID warehouseId, WorkQueue q) {
    return entries.findAllByQueueIdOrderByQueuePositionAsc(q.getId());
  }

  List<QueueEntry> orderedEntries(
      UUID warehouseId, WorkQueue queue, LocalDate scheduledDate) {
    return orderedEntries(warehouseId, queue).stream()
        .filter(
            entry ->
                queue.getPurpose() != QueuePurpose.LOGISTICS_DRIVER
                    || (entry.getTask().getLane() == TaskLane.SCHEDULED
                        && Objects.equals(entry.getTask().getScheduledDate(), scheduledDate)))
        .sorted(Comparator.comparingInt(QueueEntry::getQueuePosition))
        .toList();
  }

  void insertByPriority(UUID warehouseId, QueueEntry entry) {
    WorkQueue queue = entry.getQueue();
    LocalDate date = entry.getTask().getScheduledDate();
    Map<QueuePositionScope, List<PinnedQueueOrdinal>> pinnedOrdinals =
        pinnedQueueOrdinals(orderedEntries(warehouseId, queue));
    var ordered =
        new ArrayList<>(
            orderedEntries(warehouseId, queue, date).stream()
                .filter(candidate -> !candidate.equals(entry))
                .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
                .toList());
    int protectedPrefix = 0;
    for (int index = 0; index < ordered.size(); index++) {
      QueueEntry candidate = ordered.get(index);
      if (candidate.getStatus() == EntryStatus.IN_PROGRESS
          || candidate.getStatus() == EntryStatus.PAUSED) {
        protectedPrefix = index + 1;
      }
    }
    int insertion = protectedPrefix;
    while (insertion < ordered.size()
        && ordered.get(insertion).getTask().getPriority() <= entry.getTask().getPriority()) {
      insertion++;
    }
    ordered.add(insertion, entry);
    int position = 0;
    for (QueueEntry candidate : ordered) {
      candidate.setQueuePosition(position++);
      projectionWriter.save(entries, candidate);
    }
    projectionWriter.flush();
    restorePinnedQueueOrdinals(pinnedOrdinals);
  }

  private UUID id(WorkQueue queue) {
    return queue == null ? null : queue.getId();
  }

  /** Snapshot of an entry's persisted queue identity and position before a coordinated mutation. */
  static record QueueEntryPosition(UUID queueId, int position) {}

  /**
   * Defines the ordering partition: ordinary queues are aggregate, while driver work remains
   * separated by schedule date and lane.
   */
  static record QueuePositionScope(UUID queueId, LocalDate scheduledDate, TaskLane lane) {}

  /** Remembers the absolute slot of a pinned entry within its ordering partition. */
  static record PinnedQueueOrdinal(UUID entryId, int ordinal) {}
}
