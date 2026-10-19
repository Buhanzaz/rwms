package dev.buhanzaz.rwms.taskboard.push;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists, leases and resolves at-least-once WorkerApp push invalidations.
 *
 * <p>Enqueue participates in the caller's driver-TAKE transaction. Delivery claims use
 * {@code SKIP LOCKED}, making concurrent service instances safe without holding a database
 * transaction across the external FCM request.
 */
@Component
public class WorkerPushOutbox {
  private final JdbcTemplate jdbc;

  public WorkerPushOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Enqueues one join signal per eligible worker in the current transaction. */
  public void enqueueJoinAvailable(
      Set<UUID> workerIds, UUID warehouseId, UUID entryId, long revision) {
    for (UUID workerId : workerIds) {
      jdbc.update(
          """
          insert into worker_push_outbox(
              event_id,worker_id,warehouse_id,entry_id,revision,event_type,status,
              attempt_count,available_at,lease_until,last_error,created_at,updated_at)
          values (?,?,?,?,?,'TASK_JOIN_AVAILABLE','PENDING',0,
                  clock_timestamp(),null,null,clock_timestamp(),clock_timestamp())
          """,
          UUID.randomUUID(),
          workerId,
          warehouseId,
          entryId,
          revision);
    }
  }

  /** Claims due events for one bounded dispatcher pass. */
  @Transactional
  public List<PendingPush> claim(int limit, Duration leaseDuration) {
    return jdbc.query(
        """
        with candidates as (
          select event_id
            from worker_push_outbox
           where (status='PENDING' and available_at <= clock_timestamp())
              or (status='SENDING' and lease_until <= clock_timestamp())
           order by available_at,created_at,event_id
           for update skip locked
           limit ?
        )
        update worker_push_outbox outbox
           set status='SENDING',
               attempt_count=outbox.attempt_count + 1,
               lease_until=clock_timestamp() + (? * interval '1 millisecond'),
               updated_at=clock_timestamp()
          from candidates
         where outbox.event_id=candidates.event_id
        returning outbox.event_id,outbox.worker_id,outbox.warehouse_id,outbox.entry_id,
                  outbox.revision,outbox.event_type,outbox.attempt_count
        """,
        (result, row) ->
            new PendingPush(
                result.getObject("event_id", UUID.class),
                result.getObject("worker_id", UUID.class),
                result.getObject("warehouse_id", UUID.class),
                result.getObject("entry_id", UUID.class),
                result.getLong("revision"),
                result.getString("event_type"),
                result.getInt("attempt_count")),
        limit,
        leaseDuration.toMillis());
  }

  /** Returns active WorkerApp targets only; DriverApp registrations never receive slinger calls. */
  public List<WorkerPushClient.Target> targets(UUID workerId, UUID warehouseId) {
    return jdbc.query(
        """
        select installation_id,target_kind,provider_token
          from worker_device_registration
         where worker_id=? and warehouse_id=? and app_surface='WORKER' and status='ACTIVE'
         order by installation_id
        """,
        (result, row) ->
            new WorkerPushClient.Target(
                result.getString("installation_id"),
                result.getString("target_kind"),
                result.getString("provider_token")),
        workerId,
        warehouseId);
  }

  /** Marks an event delivered after every currently active target accepted it. */
  public void markSent(UUID eventId) {
    jdbc.update(
        """
        update worker_push_outbox
           set status='SENT',lease_until=null,last_error=null,updated_at=clock_timestamp()
         where event_id=? and status='SENDING'
        """,
        eventId);
  }

  /** Returns a transiently failed event to the queue after its bounded delay. */
  public void markRetry(UUID eventId, Duration delay, String error) {
    jdbc.update(
        """
        update worker_push_outbox
           set status='PENDING',available_at=?,lease_until=null,last_error=?,
               updated_at=clock_timestamp()
         where event_id=? and status='SENDING'
        """,
        OffsetDateTime.now(ZoneOffset.UTC).plus(delay),
        sanitized(error),
        eventId);
  }

  /** Moves an exhausted or permanent failure to terminal operator-visible state. */
  public void markDead(UUID eventId, String error) {
    jdbc.update(
        """
        update worker_push_outbox
           set status='DEAD',lease_until=null,last_error=?,updated_at=clock_timestamp()
         where event_id=? and status='SENDING'
        """,
        sanitized(error),
        eventId);
  }

  /** Revokes one provider-rejected installation without affecting another app on the device. */
  public void revokeTarget(UUID workerId, WorkerPushClient.Target target) {
    jdbc.update(
        """
        update worker_device_registration
           set status='REVOKED',updated_at=clock_timestamp()
         where installation_id=? and worker_id=? and target_kind=? and provider_token=?
        """,
        target.installationId(),
        workerId,
        target.targetKind(),
        target.value());
  }

  private String sanitized(String value) {
    if (value == null || value.isBlank()) return "Push delivery failed";
    String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
    return normalized.substring(0, Math.min(normalized.length(), 1000));
  }

  /**
   * One leased outbox event.
   *
   * @param eventId stable client deduplication identity
   * @param workerId notification recipient
   * @param warehouseId recipient warehouse fence
   * @param entryId task offered for secondary participation
   * @param revision feed revision after the primary TAKE
   * @param eventType invalidation type
   * @param attemptCount one-based delivery attempt number
   */
  public record PendingPush(
      UUID eventId,
      UUID workerId,
      UUID warehouseId,
      UUID entryId,
      long revision,
      String eventType,
      int attemptCount) {}
}
