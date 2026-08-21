package dev.buhanzaz.rwms.taskboard.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Repairs task-entry media proofs after audience policy or workforce state changes.
 *
 * <p>Legacy proofs without the additive reader audience and every open entry are visited in bounded
 * UUID order. The transactional proof owner appends an event only when the calculated payload
 * differs, so repeated scans are idempotent and Kafka ordering remains aggregate-local.
 */
@Component
@Profile("!test")
public class TaskBoardEntryOwnerProofReconciler {
  private static final Logger log =
      LoggerFactory.getLogger(TaskBoardEntryOwnerProofReconciler.class);
  private static final int BATCH_SIZE = 500;

  private final JdbcTemplate jdbc;
  private final TaskBoardEntryOwnerProofService ownerProofs;
  private String afterEntryId = "";

  public TaskBoardEntryOwnerProofReconciler(
      JdbcTemplate jdbc, TaskBoardEntryOwnerProofService ownerProofs) {
    this.jdbc = jdbc;
    this.ownerProofs = ownerProofs;
  }

  /** Reconciles one bounded page and advances a process-local wraparound cursor. */
  @Scheduled(
      fixedDelayString = "${rwms.task-board.owner-proof-reconciliation-delay:PT30S}",
      initialDelayString = "${rwms.task-board.owner-proof-reconciliation-initial-delay:PT5S}")
  public void reconcile() {
    List<Candidate> candidates = candidates(afterEntryId);
    if (candidates.isEmpty()) {
      afterEntryId = "";
      return;
    }
    int changed = 0;
    int failed = 0;
    for (Candidate candidate : candidates) {
      try {
        if (ownerProofs.reconcile(candidate.warehouseId(), candidate.entryId())) changed++;
      } catch (RuntimeException exception) {
        failed++;
        log.warn(
            "Task-entry owner-proof reconciliation failed entryId={} errorType={}",
            candidate.entryId(),
            exception.getClass().getSimpleName(),
            exception);
      }
    }
    afterEntryId =
        candidates.size() < BATCH_SIZE
            ? ""
            : candidates.getLast().entryId().toString();
    if (changed > 0 || failed > 0) {
      log.info(
          "Task-entry owner-proof reconciliation scanned={} changed={} failed={}",
          candidates.size(),
          changed,
          failed);
    }
  }

  private List<Candidate> candidates(String cursor) {
    return jdbc.query(
        """
        select entry.id, task.warehouse_id
          from queue_entry entry
          join board_task task on task.id=entry.task_id
          left join event_stream_head head
            on head.aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
           and head.aggregate_id=entry.id::text
          left join domain_event event on event.event_id=head.last_event_id
         where entry.id::text>?
           and (
             head.aggregate_id is null
             or not coalesce(jsonb_exists(event.payload,'readerWorkerIds'), false)
             or (task.status='ACTIVE'
                 and entry.status in ('WAITING','IN_PROGRESS','PAUSED'))
           )
         order by entry.id::text
         limit ?
        """,
        (result, row) ->
            new Candidate(
                result.getObject("id", UUID.class),
                result.getObject("warehouse_id", UUID.class)),
        cursor,
        BATCH_SIZE);
  }

  /** Warehouse-scoped entry identity selected for one bounded repair attempt. */
  private record Candidate(UUID entryId, UUID warehouseId) {}
}
