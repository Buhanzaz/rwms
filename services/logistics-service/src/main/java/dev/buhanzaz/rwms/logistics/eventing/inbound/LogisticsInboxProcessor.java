package dev.buhanzaz.rwms.logistics.eventing.inbound;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a source fact and its consumer checkpoint in one local PostgreSQL
 * transaction. The projection is evidence only; authoritative transitions
 * continue through the source-owned private APIs and durable saga attempts.
 */
@Service
@RequiredArgsConstructor
public class LogisticsInboxProcessor {
  private final JdbcTemplate jdbc;
  private final LogisticsInboundObservationStore observations;
  private final LogisticsInboundStagingStore staging;

  @Transactional
  public Outcome process(LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event) {
    int inserted =
        jdbc.update(
            """
            insert into inbox_message(
              consumer_group,event_id,aggregate_type,aggregate_id,aggregate_version,payload_sha256,
              source_topic,event_type,envelope_body,status,attempt_count,received_at)
            values (?,?,?,?,?,?,?, ?,?::jsonb,'RECEIVED',0,clock_timestamp())
            on conflict (consumer_group,event_id) do nothing
            """,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            event.eventId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.rawMessageSha256(),
            event.sourceTopic(),
            event.eventType(),
            event.envelopeJson());
    if (inserted == 0) {
      requireSameEvent(event);
      if (!prepareResumableEvent(event.eventId())) {
        return Outcome.DUPLICATE;
      }
    }

    if (!event.appliesToLogistics()) {
      // The shared media stream contains facts owned by other bounded contexts. Persist the
      // acknowledgement, but do not create a logistics observation or aggregate checkpoint.
      markProcessed(event.eventId());
      staging.markApplied(event.eventId());
      return Outcome.PROCESSED;
    }

    jdbc.update(
        """
        insert into consumer_aggregate_checkpoint(
          consumer_group,aggregate_type,aggregate_id,last_event_id,last_aggregate_version,blocked,updated_at)
        values (?,?,?,null,-1,false,clock_timestamp())
        on conflict do nothing
        """,
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
    Checkpoint checkpoint = lockCheckpoint(event);
    if (checkpoint.blocked()) {
      quarantineInbox(event.eventId(), "AGGREGATE_BLOCKED");
      return Outcome.BLOCKED;
    }

    if (event.isPublicMediaFact()) {
      // Private upload and processing mutations advance MEDIA between public facts. Public
      // snapshots therefore only promise a strictly increasing source version.
      if (event.aggregateVersion() <= checkpoint.version()) {
        observations.apply(event);
        markProcessed(event.eventId());
        staging.markApplied(event.eventId());
        return Outcome.DUPLICATE;
      }
    } else {
      long expectedVersion = Math.addExact(checkpoint.version(), 1);
      if (event.aggregateVersion() < expectedVersion) {
        observations.apply(event);
        markProcessed(event.eventId());
        staging.markApplied(event.eventId());
        return Outcome.DUPLICATE;
      }
      if (event.aggregateVersion() > expectedVersion) {
        quarantineGap(event, expectedVersion);
        return Outcome.VERSION_GAP;
      }
    }

    observations.apply(event);
    jdbc.update(
        """
        update consumer_aggregate_checkpoint
           set last_event_id=?,last_aggregate_version=?,updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type=? and aggregate_id=?
        """,
        event.eventId(),
        event.aggregateVersion(),
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
    markProcessed(event.eventId());
    staging.markApplied(event.eventId());
    return Outcome.PROCESSED;
  }

  @Transactional
  public Outcome replay(UUID eventId) {
    return process(
        staging
            .load(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Reviewed logistics replay event does not exist")));
  }

  @Transactional
  void applyMissingDuringReconciliation(
      LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event, long quarantinedVersion) {
    Checkpoint checkpoint = lockCheckpoint(event);
    if (!checkpoint.blocked()
        || event.aggregateVersion() != Math.addExact(checkpoint.version(), 1)
        || event.aggregateVersion() >= quarantinedVersion) {
      throw new IllegalArgumentException(
          "Gap reconciliation events must be contiguous and precede the quarantined version");
    }
    upsertReconciliationInbox(event);
    observations.apply(event);
    jdbc.update(
        """
        update consumer_aggregate_checkpoint
           set last_event_id=?,last_aggregate_version=?,updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type=? and aggregate_id=? and blocked
        """,
        event.eventId(),
        event.aggregateVersion(),
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
    markProcessed(event.eventId());
    staging.markApplied(event.eventId());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordTransientFailure(
      LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event, int attemptCount) {
    if (attemptCount < 1 || attemptCount > 3) {
      throw new IllegalArgumentException("Inbound retry attempt count is invalid");
    }
    int inserted =
        jdbc.update(
            """
            insert into inbox_message(
              consumer_group,event_id,aggregate_type,aggregate_id,aggregate_version,payload_sha256,
              source_topic,event_type,envelope_body,status,attempt_count,received_at,next_attempt_at)
            values (?,?,?,?,?,?,?, ?,?::jsonb,'RETRY',?,clock_timestamp(),clock_timestamp())
            on conflict (consumer_group,event_id) do nothing
            """,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            event.eventId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.rawMessageSha256(),
            event.sourceTopic(),
            event.eventType(),
            event.envelopeJson(),
            attemptCount);
    if (inserted == 1) {
      return;
    }
    requireSameEvent(event);
    int updated =
        jdbc.update(
            """
            update inbox_message
               set status='RETRY',attempt_count=?,next_attempt_at=clock_timestamp(),processed_at=null
             where consumer_group=? and event_id=? and status in ('RECEIVED','RETRY')
            """,
            attemptCount,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            event.eventId());
    if (updated != 1) {
      throw new IllegalStateException("Inbound retry cannot replace a terminal inbox state");
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markDeadLetter(UUID eventId, String reason) {
    jdbc.update(
        """
        update inbox_message
           set status='DLT',dlt_at=clock_timestamp(),next_attempt_at=null,quarantine_reason=?
         where consumer_group=? and event_id=? and status in ('RECEIVED','RETRY')
        """,
        reason,
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        eventId);
  }

  private boolean prepareResumableEvent(UUID eventId) {
    return jdbc.update(
            """
            update inbox_message inbox
               set status='RECEIVED',processed_at=null,next_attempt_at=null,quarantine_reason=null
             where inbox.consumer_group=? and inbox.event_id=?
               and (
                 inbox.status='RETRY'
                 or (
                   inbox.status='QUARANTINED'
                   and exists (
                     select 1 from logistics_inbound_replay_message replay
                      where replay.event_id=inbox.event_id and replay.state='REPLAY_APPROVED'
                   )
                 )
               )
            """,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            eventId)
        == 1;
  }

  private void upsertReconciliationInbox(
      LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event) {
    int inserted =
        jdbc.update(
            """
            insert into inbox_message(
              consumer_group,event_id,aggregate_type,aggregate_id,aggregate_version,payload_sha256,
              source_topic,event_type,envelope_body,status,attempt_count,received_at)
            values (?,?,?,?,?,?,?, ?,?::jsonb,'RECEIVED',0,clock_timestamp())
            on conflict (consumer_group,event_id) do nothing
            """,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            event.eventId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.rawMessageSha256(),
            event.sourceTopic(),
            event.eventType(),
            event.envelopeJson());
    if (inserted == 1) {
      return;
    }
    requireSameEvent(event);
    int reset =
        jdbc.update(
            """
            update inbox_message
               set status='RECEIVED',processed_at=null,next_attempt_at=null,quarantine_reason=null
             where consumer_group=? and event_id=? and status='QUARANTINED'
            """,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            event.eventId());
    if (reset != 1) {
      throw new IllegalStateException("Gap reconciliation event is not quarantined");
    }
  }

  private void requireSameEvent(LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event) {
    Integer matches =
        jdbc.queryForObject(
            """
            select count(*)
              from inbox_message
             where consumer_group=? and event_id=? and aggregate_type=? and aggregate_id=?
               and aggregate_version=? and payload_sha256=? and source_topic=? and event_type=?
               and envelope_body=?::jsonb
            """,
            Integer.class,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            event.eventId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.rawMessageSha256(),
            event.sourceTopic(),
            event.eventType(),
            event.envelopeJson());
    if (matches == null || matches != 1) {
      throw new LogisticsInboundEventIdentityConflictException();
    }
  }

  private Checkpoint lockCheckpoint(LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event) {
    return jdbc.queryForObject(
        """
        select last_aggregate_version,blocked
          from consumer_aggregate_checkpoint
         where consumer_group=? and aggregate_type=? and aggregate_id=?
         for update
        """,
        (result, row) -> new Checkpoint(result.getLong(1), result.getBoolean(2)),
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
  }

  private void quarantineGap(
      LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event, long expectedVersion) {
    jdbc.update(
        """
        update consumer_aggregate_checkpoint
           set blocked=true,quarantine_reason='AGGREGATE_VERSION_GAP',updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type=? and aggregate_id=?
        """,
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
    quarantineInbox(event.eventId(), "AGGREGATE_VERSION_GAP");
    jdbc.update(
        """
        insert into version_gap_quarantine(
          quarantine_id,consumer_group,aggregate_type,aggregate_id,expected_version,received_version,
          received_event_id,payload_sha256,reason_code,status,detected_at)
        values (?,?,?,?,?,?,?,?,'AGGREGATE_VERSION_GAP','OPEN',clock_timestamp())
        on conflict (consumer_group,received_event_id) do nothing
        """,
        UUID.randomUUID(),
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId(),
        expectedVersion,
        event.aggregateVersion(),
        event.eventId(),
        event.rawMessageSha256());
  }

  private void markProcessed(UUID eventId) {
    jdbc.update(
        """
        update inbox_message
           set status='PROCESSED',processed_at=clock_timestamp(),next_attempt_at=null,quarantine_reason=null
         where consumer_group=? and event_id=?
        """,
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        eventId);
  }

  private void quarantineInbox(UUID eventId, String reason) {
    jdbc.update(
        """
        update inbox_message
           set status='QUARANTINED',processed_at=clock_timestamp(),next_attempt_at=null,quarantine_reason=?
         where consumer_group=? and event_id=?
        """,
        reason,
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        eventId);
  }

  public enum Outcome {
    PROCESSED,
    DUPLICATE,
    VERSION_GAP,
    BLOCKED
  }

  private record Checkpoint(long version, boolean blocked) {}
}
