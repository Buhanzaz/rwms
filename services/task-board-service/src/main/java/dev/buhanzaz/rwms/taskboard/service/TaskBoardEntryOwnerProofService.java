package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceMediaSnapshotRequest;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.EntryOwnerProofFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.OwnerMediaReferenceFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import java.util.ArrayList;
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

@Service
public class TaskBoardEntryOwnerProofService {
  private static final Set<AssignmentStatus> CURRENT_ASSIGNMENTS =
      Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED);

  private final QueueEntryRepository entries;
  private final TaskAssignmentRepository assignments;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final TaskBoardEventSourcing eventSourcing;

  public TaskBoardEntryOwnerProofService(
      QueueEntryRepository entries,
      TaskAssignmentRepository assignments,
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      TaskBoardEventSourcing eventSourcing) {
    this.entries = entries;
    this.assignments = assignments;
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.eventSourcing = eventSourcing;
  }

  /**
   * Publishes the complete media authorization projection for an entry.
   *
   * <p>A terminal entry remains active only while an already-reserved image is
   * still being uploaded. This lets a secondary worker finish a capture that
   * began before a primary worker completed the shared multi-class task, without
   * allowing a new reservation after completion.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(UUID warehouseId, UUID entryId, boolean requestedActive) {
    var entry =
        entries
            .findById(entryId)
            .filter(value -> warehouseId.equals(value.getTask().getWarehouseId()))
            .orElseThrow(() -> new NotFoundException("Задание не найдено"));
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
    assignments.findAllByQueueEntryIdAndStatusIn(entryId, CURRENT_ASSIGNMENTS).stream()
        .map(value -> value.getWorker().getId())
        .forEach(allowedWorkerIds::add);
    allowedWorkerIds.addAll(
        jdbc.queryForList(
            "select distinct worker_id from worker_task_evidence where entry_id=?",
            UUID.class,
            entryId));

    EntryOwnerProofFact proof =
        new EntryOwnerProofFact(
            "TASK_BOARD_ENTRY",
            entryId,
            warehouseId,
            entry.getRouteIndex(),
            active,
            allowedWorkerIds,
            sourceMedia(entry.getSourceMediaReferences()));
    List<Long> versions =
        jdbc.query(
            """
            select current_version
              from event_stream_head
             where aggregate_type=? and aggregate_id=?
             for update
            """,
            (result, row) -> result.getLong(1),
            TaskBoardAggregateType.TASK_BOARD_ENTRY_OWNER_PROOF.name(),
            entryId.toString());
    if (versions.isEmpty()) {
      eventSourcing.ownerProofCreated(proof);
    } else {
      eventSourcing.ownerProofChanged(proof, versions.getFirst());
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
}
