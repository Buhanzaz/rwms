package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceMediaSnapshotRequest;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.EntryOwnerProofFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.OwnerMediaReferenceFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes the task-board-owned media-authorization projection for an entry.
 *
 * <p>The projection contains entry scope, a narrow upload audience, the native task read audience
 * and immutable source-media references. Media-service can distinguish viewing from capture
 * without reading task-board tables or accepting browser-supplied ownership claims.
 */
@Service
public class TaskBoardEntryOwnerProofService {
  private static final Set<AssignmentStatus> CURRENT_ASSIGNMENTS =
      Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED);
  private static final Set<EntryStatus> OPEN_ENTRIES =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final QueueEntryRepository entries;
  private final TaskAssignmentRepository assignments;
  private final WorkerTaskAccessService taskAccess;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final TaskBoardEventSourcing eventSourcing;

  public TaskBoardEntryOwnerProofService(
      QueueEntryRepository entries,
      TaskAssignmentRepository assignments,
      WorkerTaskAccessService taskAccess,
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      TaskBoardEventSourcing eventSourcing) {
    this.entries = entries;
    this.assignments = assignments;
    this.taskAccess = taskAccess;
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.eventSourcing = eventSourcing;
  }

  /**
   * Publishes the complete media authorization projection for an entry.
   *
   * <p>A terminal entry remains active only while an already-reserved image is still being
   * uploaded. This lets a secondary worker finish a capture that began before a primary worker
   * completed the shared multi-class task, without allowing a new reservation after completion.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(UUID warehouseId, UUID entryId, boolean requestedActive) {
    QueueEntry entry = requireEntry(warehouseId, entryId);
    write(proof(entry, requestedActive), false);
  }

  /**
   * Rebuilds one proof from current task/workforce state and appends only when its payload changed.
   *
   * <p>This is the idempotent repair seam used for pre-reader-audience events and later
   * qualification, membership or queue-policy changes.
   */
  @Transactional
  public boolean reconcile(UUID warehouseId, UUID entryId) {
    QueueEntry entry = requireEntry(warehouseId, entryId);
    boolean open =
        entry.getTask().getStatus() == TaskStatus.ACTIVE
            && OPEN_ENTRIES.contains(entry.getStatus());
    return write(proof(entry, open), true);
  }

  private QueueEntry requireEntry(UUID warehouseId, UUID entryId) {
    return entries
        .findById(entryId)
        .filter(value -> warehouseId.equals(value.getTask().getWarehouseId()))
        .orElseThrow(() -> new NotFoundException("Задание не найдено"));
  }

  private EntryOwnerProofFact proof(QueueEntry entry, boolean requestedActive) {
    UUID entryId = entry.getId();
    boolean pendingEvidence =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
                select exists(
                    select 1
                      from worker_task_evidence
                     where entry_id=? and state in ('RESERVED','UPLOADING')
                )
                """,
                Boolean.class,
                entryId));
    boolean active = requestedActive || pendingEvidence;

    List<UUID> allowedWorkerIds = new ArrayList<>();
    (active
            ? assignments.findAllByQueueEntryIdAndStatusIn(entryId, CURRENT_ASSIGNMENTS)
            : assignments.findAllByQueueEntryId(entryId))
        .stream()
        .map(value -> value.getWorker().getId())
        .forEach(allowedWorkerIds::add);
    allowedWorkerIds.addAll(
        jdbc.queryForList(
            "select distinct worker_id from worker_task_evidence where entry_id=?",
            UUID.class,
            entryId));

    Set<UUID> readerWorkerIds = new LinkedHashSet<>();
    if (requestedActive) {
      readerWorkerIds.addAll(taskAccess.readerWorkerIds(entry));
    }
    readerWorkerIds.addAll(allowedWorkerIds);

    return new EntryOwnerProofFact(
        "TASK_BOARD_ENTRY",
        entryId,
        entry.getTask().getWarehouseId(),
        entry.getRouteIndex(),
        active,
        allowedWorkerIds,
        List.copyOf(readerWorkerIds),
        sourceMedia(entry.getSourceMediaReferences()));
  }

  private boolean write(EntryOwnerProofFact proof, boolean skipUnchanged) {
    String proofJson;
    try {
      proofJson = objectMapper.writeValueAsString(proof);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Не удалось сериализовать доступ к фотографиям задания", exception);
    }
    List<ProofHead> heads =
        jdbc.query(
            """
            select head.current_version, event.payload = ?::jsonb
              from event_stream_head head
              join domain_event event on event.event_id=head.last_event_id
             where head.aggregate_type=? and head.aggregate_id=?
             for update of head
            """,
            (result, row) -> new ProofHead(result.getLong(1), result.getBoolean(2)),
            proofJson,
            TaskBoardAggregateType.TASK_BOARD_ENTRY_OWNER_PROOF.name(),
            proof.ownerId().toString());
    if (heads.isEmpty()) {
      eventSourcing.ownerProofCreated(proof);
      return true;
    }
    ProofHead head = heads.getFirst();
    if (skipUnchanged && head.samePayload()) {
      return false;
    } else {
      eventSourcing.ownerProofChanged(proof, head.version());
      return true;
    }
  }

  private List<OwnerMediaReferenceFact> sourceMedia(String json) {
    try {
      List<TaskSourceMediaSnapshotRequest> references =
          objectMapper.readValue(json, new TypeReference<>() {});
      return references.stream()
          .map(value -> new OwnerMediaReferenceFact(value.mediaId(), value.generation()))
          .toList();
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Сохранённые исходные фотографии задания повреждены", exception);
    }
  }

  /** Locked latest proof version and its equality with the freshly calculated payload. */
  private record ProofHead(long version, boolean samePayload) {}
}
