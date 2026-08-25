package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceTransportTopics;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replays completed task outcomes that an older consumer acknowledged before it could correlate
 * the queue entry with its maintenance repair.
 *
 * <p>The maintenance-owned {@code repair_stage.external_queue_entry_id} mapping is authoritative.
 * Processed inbox rows remain unchanged as audit history; each candidate is applied through the
 * ordinary idempotent inbound use case in an independent transaction. The pass is safe across
 * restarts and concurrent instances because an already completed stage is a no-op under the
 * repair stream lock.
 */
@Service
public class MaintenanceProcessedTaskOutcomeRecovery {
  private static final Logger log =
      LoggerFactory.getLogger(MaintenanceProcessedTaskOutcomeRecovery.class);

  private final JdbcTemplate jdbc;
  private final MaintenanceApplicationService service;
  private final TransactionTemplate transactions;

  public MaintenanceProcessedTaskOutcomeRecovery(
      JdbcTemplate jdbc,
      MaintenanceApplicationService service,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.service = service;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Applies every processed completion whose persisted repair stage is still queued.
   *
   * <p>Independent failures do not prevent other repairs from recovering. Startup reports a
   * failure after the full pass so the unresolved rows remain visible to operations and are
   * retried on the next application start.
   */
  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public void recoverProcessedTaskOutcomes() {
    List<ProcessedTaskOutcome> candidates = jdbc.query(
        """
        select inbox.event_id,inbox.event_type,inbox.aggregate_version,
               repair.external_task_id,stage.external_queue_entry_id,
               inbox.envelope_body->'payload'->>'doneAt' as done_at
          from inbox_message inbox
          join repair_stage stage on stage.external_queue_entry_id::text=inbox.aggregate_id
          join maintenance_repair repair on repair.id=stage.repair_id
         where inbox.consumer_group=?
           and inbox.status='PROCESSED'
           and inbox.aggregate_type='QUEUE_ENTRY'
           and inbox.event_type='task-board.queue-entry.completed.v1'
           and stage.state='QUEUED'
           and repair.external_task_id is not null
           and repair.historical_shipment_document_id is null
         order by inbox.received_at,inbox.event_id
        """,
        (result, row) -> {
          String doneAt = result.getString("done_at");
          return new ProcessedTaskOutcome(
              result.getObject("event_id", UUID.class),
              result.getString("event_type"),
              result.getObject("external_task_id", UUID.class),
              result.getObject("external_queue_entry_id", UUID.class),
              result.getLong("aggregate_version"),
              doneAt == null ? null : OffsetDateTime.parse(doneAt));
        },
        MaintenanceTransportTopics.CONSUMER_GROUP);

    RuntimeException firstFailure = null;
    int recovered = 0;
    int failures = 0;
    for (ProcessedTaskOutcome candidate : candidates) {
      try {
        transactions.executeWithoutResult(status -> service.applyInboundTaskOutcome(
            candidate.eventId(),
            candidate.eventType(),
            candidate.externalTaskId(),
            candidate.queueEntryId(),
            candidate.aggregateVersion(),
            candidate.doneAt()));
        recovered++;
      } catch (RuntimeException failure) {
        failures++;
        if (firstFailure == null) firstFailure = failure;
        log.error(
            "Processed task outcome recovery failed for event {} and queue entry {}",
            candidate.eventId(),
            candidate.queueEntryId(),
            failure);
      }
    }
    log.info(
        "Processed task outcome recovery inspected {} candidates, applied {}, and failed {}",
        candidates.size(),
        recovered,
        failures);
    if (firstFailure != null) {
      throw new IllegalStateException(
          "Processed task outcome recovery retained " + failures + " unresolved candidate(s)",
          firstFailure);
    }
  }

  /** Immutable processed completion required by the owner-local recovery pass. */
  private record ProcessedTaskOutcome(
      UUID eventId,
      String eventType,
      UUID externalTaskId,
      UUID queueEntryId,
      long aggregateVersion,
      OffsetDateTime doneAt) {}
}
