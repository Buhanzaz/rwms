package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.SetFutureTaskEntryAvailabilityRequest;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns the manager-controlled execution availability of still-future ordinary route entries.
 *
 * <p>The command shares the warehouse queue-mutation lock with take and completion, then locks the
 * complete route in route order, fences the selected entry version and persists its {@link
 * EntryType} together with the existing queue-entry changed fact. It never bypasses unfinished
 * SES work. The media-owner proof is refreshed in the same transaction so promotion grants and
 * demotion revokes the corresponding source-evidence read audience. Worker feeds are invalidated
 * only after commit, so a rolled-back manager command cannot advertise uncommitted availability.
 */
@Service
class TaskBoardFutureAvailabilityService {
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final QueueEntryRepository entries;
  private final TaskBoardQueuePositionCoordinator queuePositions;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final TaskBoardEntryOwnerProofService ownerProofs;
  private final WorkerInvalidationHub workerInvalidations;
  private final JdbcTemplate jdbc;

  TaskBoardFutureAvailabilityService(
      QueueEntryRepository entries,
      TaskBoardQueuePositionCoordinator queuePositions,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      TaskBoardEntryOwnerProofService ownerProofs,
      WorkerInvalidationHub workerInvalidations,
      JdbcTemplate jdbc) {
    this.entries = entries;
    this.queuePositions = queuePositions;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.ownerProofs = ownerProofs;
    this.workerInvalidations = workerInvalidations;
    this.jdbc = jdbc;
  }

  /**
   * Applies the requested future availability after rechecking the locked route and SES gate.
   *
   * <p>An exact desired-state replay is a no-op only while the entry is still a WAITING step
   * strictly after the earliest unfinished route position and is otherwise eligible for the same
   * command. A route transition that made it current therefore turns the replay into a conflict.
   */
  void setAvailability(
      UUID warehouseId,
      UUID entryId,
      SetFutureTaskEntryAvailabilityRequest request) {
    queuePositions.lockQueueMutation(warehouseId);
    QueueEntry observed =
        entries.findById(entryId).orElseThrow(() -> new NotFoundException("Этап не найден"));
    if (!warehouseId.equals(observed.getTask().getWarehouseId())) {
      throw new NotFoundException("Этап не найден");
    }

    List<QueueEntry> route = entries.findAllByTaskIdForUpdate(observed.getTask().getId());
    QueueEntry entry =
        route.stream()
            .filter(candidate -> entryId.equals(candidate.getId()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Этап не найден"));
    checkVersion(entry.getVersion(), request.expectedEntryVersion(), "Этап");
    requireEligibleRouteEntry(entry, route, request.available());

    EntryType requestedType = request.available() ? EntryType.REAL : EntryType.SHADOW;
    if (entry.getEntryType() == requestedType) return;

    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.QUEUE_ENTRY, entryId);
    entry.setEntryType(requestedType);
    QueueEntry changed = projectionWriter.saveAndFlush(entries, entry);
    eventSourcing.entryChanged(
        changed, streamVersion, TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
    ownerProofs.publish(warehouseId, changed.getId(), request.available());
    publishWorkerFeedChangedAfterCommit();
  }

  private void requireEligibleRouteEntry(
      QueueEntry entry, List<QueueEntry> route, boolean enabling) {
    if (entry.getTask().getStatus() != TaskStatus.ACTIVE
        || entry.getQueue() == null
        || entry.getQueue().getPurpose() != QueuePurpose.GENERAL
        || !entry.getQueue().isActive()
        || entry.getQueue().isHidden()) {
      throw new ConflictException(
          "Доступность можно менять только для активного обычного маршрута");
    }
    if (entry.getStatus() != EntryStatus.WAITING) {
      throw new ConflictException("Доступность можно менять только для ожидающего этапа");
    }

    QueueEntry earliestUnfinished =
        route.stream()
            .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
            .min(Comparator.comparingInt(QueueEntry::getRouteIndex))
            .orElse(null);
    if (earliestUnfinished == null
        || entry.getRouteIndex() <= earliestUnfinished.getRouteIndex()) {
      throw new ConflictException("Текущий этап маршрута нельзя перевести в будущие");
    }

    boolean unfinishedSesBeforeEntry =
        route.stream()
            .filter(candidate -> candidate.getRouteIndex() < entry.getRouteIndex())
            .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
            .anyMatch(candidate -> RepairRoutePhaseOrder.isSesQueue(candidate.getQueue()));
    if (enabling && unfinishedSesBeforeEntry) {
      throw new ConflictException("Сначала завершите обязательный этап СЭС");
    }
  }

  /** Registers a projection-only invalidation that cannot run for a rolled-back command. */
  private void publishWorkerFeedChangedAfterCommit() {
    Runnable dispatch = () -> workerInvalidations.feedChanged(workerRevision());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatch.run();
            }
          });
    } else {
      dispatch.run();
    }
  }

  private long workerRevision() {
    Long revision =
        jdbc.queryForObject(
            "select coalesce(sum(current_version + 1), 0)::bigint from event_stream_head",
            Long.class);
    return revision == null ? 0 : revision;
  }
}
