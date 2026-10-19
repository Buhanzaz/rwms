package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.service.TaskBoardEntryOwnerProofService.ReconciliationAudience;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>Legacy proofs without the additive reader audience are always eligible. Open entries are
 * visited once at startup and again only after the latest worker, group, or queue event changes.
 * Pages remain bounded and a pass reuses one immutable workforce snapshot per warehouse, avoiding
 * an idle full-board/workforce N+1 scan every thirty seconds. The transactional proof owner appends
 * an event only when the calculated payload differs, so repair remains idempotent and Kafka
 * ordering remains aggregate-local.
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
  private AudienceRevision reconciledAudienceRevision;
  private AudienceRevision targetAudienceRevision;
  private boolean includeOpenEntries;
  private boolean passFailed;

  public TaskBoardEntryOwnerProofReconciler(
      JdbcTemplate jdbc, TaskBoardEntryOwnerProofService ownerProofs) {
    this.jdbc = jdbc;
    this.ownerProofs = ownerProofs;
  }

  /** Reconciles one bounded page and advances a process-local wraparound cursor. */
  @Scheduled(
      fixedDelayString = "${rwms.task-board.owner-proof-reconciliation-delay:PT30S}",
      initialDelayString = "${rwms.task-board.owner-proof-reconciliation-initial-delay:PT5S}")
  public synchronized void reconcile() {
    if (afterEntryId.isEmpty()) beginPass();
    List<Candidate> candidates = candidates(afterEntryId, includeOpenEntries);
    if (candidates.isEmpty()) {
      finishPass();
      return;
    }
    int changed = 0;
    int failed = 0;
    Map<UUID, ReconciliationAudience> audiencesByWarehouse = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      try {
        ReconciliationAudience audience =
            audiencesByWarehouse.computeIfAbsent(
                candidate.warehouseId(), ownerProofs::captureReconciliationAudience);
        if (ownerProofs.reconcile(
            candidate.warehouseId(), candidate.entryId(), audience)) changed++;
      } catch (RuntimeException exception) {
        failed++;
        log.warn(
            "Task-entry owner-proof reconciliation failed entryId={} errorType={}",
            candidate.entryId(),
            exception.getClass().getSimpleName(),
            exception);
      }
    }
    passFailed |= failed > 0;
    if (candidates.size() < BATCH_SIZE) {
      finishPass();
    } else {
      afterEntryId = candidates.getLast().entryId().toString();
    }
    if (changed > 0 || failed > 0) {
      log.info(
          "Task-entry owner-proof reconciliation scanned={} changed={} failed={}",
          candidates.size(),
          changed,
          failed);
    }
  }

  private void beginPass() {
    targetAudienceRevision = audienceRevision();
    includeOpenEntries =
        reconciledAudienceRevision == null
            || !Objects.equals(reconciledAudienceRevision, targetAudienceRevision);
  }

  private void finishPass() {
    if (includeOpenEntries && !passFailed) reconciledAudienceRevision = targetAudienceRevision;
    afterEntryId = "";
    targetAudienceRevision = null;
    includeOpenEntries = false;
    passFailed = false;
  }

  private AudienceRevision audienceRevision() {
    List<AudienceRevision> revisions =
        jdbc.query(
            """
            select event.recorded_at,event.event_id
              from event_stream_head head
              join domain_event event on event.event_id=head.last_event_id
             where head.aggregate_type in ('WORKER','WORKER_GROUP','WORK_QUEUE')
             order by event.recorded_at desc,event.event_id desc
             limit 1
            """,
            (result, row) ->
                new AudienceRevision(
                    result.getObject("recorded_at", OffsetDateTime.class),
                    result.getObject("event_id", UUID.class)));
    return revisions.isEmpty() ? AudienceRevision.EMPTY : revisions.getFirst();
  }

  private List<Candidate> candidates(String cursor, boolean includeOpen) {
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
             or (? and task.status='ACTIVE'
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
        includeOpen,
        BATCH_SIZE);
  }

  /** Warehouse-scoped entry identity selected for one bounded repair attempt. */
  private record Candidate(UUID entryId, UUID warehouseId) {}

  /** Latest audience-affecting event observed when a bounded pass began. */
  private record AudienceRevision(OffsetDateTime recordedAt, UUID eventId) {
    private static final AudienceRevision EMPTY = new AudienceRevision(null, null);
  }
}
