package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class MaintenanceInboxProcessor {
  private final JdbcTemplate jdbc;
  private final MaintenanceInboundEffects effects;
  private final MaintenanceInboundStagingStore staging;

  public MaintenanceInboxProcessor(
      JdbcTemplate jdbc,
      MaintenanceInboundEffects effects,
      MaintenanceInboundStagingStore staging) {
    this.jdbc = jdbc;
    this.effects = effects;
    this.staging = staging;
  }

  @Transactional
  public Outcome process(MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    int inserted =
        jdbc.update(
            """
            insert into inbox_message(
              consumer_group,event_id,source_topic,aggregate_type,aggregate_id,aggregate_version,event_type,
              payload_sha256,envelope_body,status,attempt_count,received_at)
            values (?,?,?,?,?,?,?,?,?::jsonb,'RECEIVED',0,clock_timestamp())
            on conflict (consumer_group,event_id) do nothing
            """,
            MaintenanceTransportTopics.CONSUMER_GROUP,
            event.eventId(),
            event.sourceTopic(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.eventType(),
            event.rawMessageSha256(),
            event.envelopeJson());
    if (inserted == 0) {
      requireSameEvent(event);
      if (!prepareApprovedReplay(event.eventId())) {
        return Outcome.DUPLICATE;
      }
    }

    jdbc.update(
        """
        insert into consumer_aggregate_checkpoint(
          consumer_group,aggregate_type,aggregate_id,last_event_id,last_aggregate_version,blocked,updated_at)
        values (?,?,?,null,-1,false,clock_timestamp()) on conflict do nothing
        """,
        MaintenanceTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
    Checkpoint checkpoint = lockCheckpoint(event);
    if (checkpoint.blocked()) {
      quarantineInbox(event.eventId(), "AGGREGATE_BLOCKED");
      return Outcome.BLOCKED;
    }
    long expectedVersion = Math.addExact(checkpoint.version(), 1);
    if (event.aggregateVersion() < expectedVersion) {
      markProcessed(event.eventId());
      staging.markApplied(event.eventId());
      return Outcome.DUPLICATE;
    }
    if (event.aggregateVersion() > expectedVersion
        && !MaintenanceTransportTopics.MEDIA.equals(event.sourceTopic())) {
      quarantineGap(event, expectedVersion);
      return Outcome.VERSION_GAP;
    }

    applyEffects(event);
    jdbc.update(
        """
        update consumer_aggregate_checkpoint
           set last_event_id=?,last_aggregate_version=?,updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type=? and aggregate_id=?
        """,
        event.eventId(),
        event.aggregateVersion(),
        MaintenanceTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
    markProcessed(event.eventId());
    markCorrelationApplied(event.eventId());
    staging.markApplied(event.eventId());
    return Outcome.PROCESSED;
  }

  @Transactional
  public Outcome replay(UUID eventId) {
    return process(
        staging
            .load(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Reviewed maintenance replay event does not exist")));
  }

  @Transactional
  void applyMissingDuringReconciliation(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event,
      long quarantinedVersion) {
    Checkpoint checkpoint = lockCheckpoint(event);
    if (!checkpoint.blocked()
        || event.aggregateVersion() != Math.addExact(checkpoint.version(), 1)
        || event.aggregateVersion() >= quarantinedVersion) {
      throw new IllegalArgumentException(
          "Gap reconciliation events must be contiguous and precede the quarantined version");
    }
    upsertReconciliationInbox(event);
    applyEffects(event);
    jdbc.update(
        """
        update consumer_aggregate_checkpoint
           set last_event_id=?,last_aggregate_version=?,updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type=? and aggregate_id=? and blocked
        """,
        event.eventId(),
        event.aggregateVersion(),
        MaintenanceTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
    markProcessed(event.eventId());
    markCorrelationApplied(event.eventId());
    staging.markApplied(event.eventId());
  }

  private boolean prepareApprovedReplay(UUID eventId) {
    return jdbc.update(
            """
            update inbox_message inbox set status='RECEIVED',processed_at=null,quarantine_reason=null
             where inbox.consumer_group=? and inbox.event_id=? and inbox.status='QUARANTINED'
               and exists (
                 select 1 from maintenance_inbound_replay_message replay
                  where replay.event_id=inbox.event_id and replay.state='REPLAY_APPROVED')
            """,
            MaintenanceTransportTopics.CONSUMER_GROUP,
            eventId)
        == 1;
  }

  private void upsertReconciliationInbox(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    int inserted =
        jdbc.update(
            """
            insert into inbox_message(
              consumer_group,event_id,source_topic,aggregate_type,aggregate_id,aggregate_version,event_type,
              payload_sha256,envelope_body,status,attempt_count,received_at)
            values (?,?,?,?,?,?,?,?,?::jsonb,'RECEIVED',0,clock_timestamp())
            on conflict (consumer_group,event_id) do nothing
            """,
            MaintenanceTransportTopics.CONSUMER_GROUP,
            event.eventId(),
            event.sourceTopic(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.eventType(),
            event.rawMessageSha256(),
            event.envelopeJson());
    if (inserted == 0) {
      requireSameEvent(event);
      int reset =
          jdbc.update(
              """
              update inbox_message set status='RECEIVED',processed_at=null,quarantine_reason=null
               where consumer_group=? and event_id=? and status='QUARANTINED'
              """,
              MaintenanceTransportTopics.CONSUMER_GROUP,
              event.eventId());
      if (reset != 1) {
        throw new IllegalStateException("Gap reconciliation event is not quarantined");
      }
    }
  }

  private void requireSameEvent(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    Integer matches =
        jdbc.queryForObject(
            """
            select count(*) from inbox_message
             where consumer_group=? and event_id=? and source_topic=? and aggregate_type=? and aggregate_id=?
               and aggregate_version=? and event_type=? and payload_sha256=? and envelope_body=?::jsonb
            """,
            Integer.class,
            MaintenanceTransportTopics.CONSUMER_GROUP,
            event.eventId(),
            event.sourceTopic(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.eventType(),
            event.rawMessageSha256(),
            event.envelopeJson());
    if (matches == null || matches != 1) {
      throw new MaintenanceEventIdentityConflictException();
    }
  }

  private Checkpoint lockCheckpoint(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    return jdbc.queryForObject(
        """
        select last_aggregate_version,blocked from consumer_aggregate_checkpoint
         where consumer_group=? and aggregate_type=? and aggregate_id=? for update
        """,
        (result, row) -> new Checkpoint(result.getLong(1), result.getBoolean(2)),
        MaintenanceTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId());
  }

  private void quarantineGap(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event, long expectedVersion) {
    jdbc.update(
        """
        update consumer_aggregate_checkpoint
           set blocked=true,quarantine_reason='AGGREGATE_VERSION_GAP',updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type=? and aggregate_id=?
        """,
        MaintenanceTransportTopics.CONSUMER_GROUP,
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
        MaintenanceTransportTopics.CONSUMER_GROUP,
        event.aggregateType(),
        event.aggregateId(),
        expectedVersion,
        event.aggregateVersion(),
        event.eventId(),
        event.rawMessageSha256());
  }

  private void applyEffects(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    if (MaintenanceTransportTopics.BOARD_TASK.equals(event.sourceTopic())) {
      List<MaintenanceInboundEffects.TaskCorrelation> correlations = correlateBoardTask(event);
      if ("task-board.board-task.changed.v1".equals(event.eventType())) {
        UUID externalTaskId = nullableUuid(event.payload(), "externalTaskId");
        if (externalTaskId != null) {
          effects.apply(
              event.effectEvent(),
              new MaintenanceInboundEffects.TaskCorrelation(
                  externalTaskId,
                  UUID.fromString(event.aggregateId()),
                  null,
                  event.eventId(),
                  null));
        }
        return;
      }
      for (MaintenanceInboundEffects.TaskCorrelation correlation : correlations) {
        effects.apply(event.effectEvent(), correlation);
      }
      return;
    }
    if (MaintenanceTransportTopics.QUEUE_ENTRY.equals(event.sourceTopic())) {
      if (event.actionable()) {
        effects.apply(event.effectEvent(), correlateQueueEntry(event));
      }
      return;
    }
    if (event.actionable()) {
      effects.apply(event.effectEvent(), MaintenanceInboundEffects.TaskCorrelation.none());
    }
  }

  private List<MaintenanceInboundEffects.TaskCorrelation> correlateBoardTask(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    UUID boardTaskId = UUID.fromString(event.aggregateId());
    UUID externalTaskId = nullableUuid(event.payload(), "externalTaskId");
    if (externalTaskId == null) {
      return List.of();
    }
    lockCorrelation(boardTaskId);
    jdbc.update(
        """
        insert into maintenance_inbound_correlation(
          source_event_id,source_topic,board_task_id,external_task_id,state,recorded_at,updated_at)
        values (?,?,?,?,'PENDING',clock_timestamp(),clock_timestamp())
        on conflict (source_event_id) do nothing
        """,
        event.eventId(),
        event.sourceTopic(),
        boardTaskId,
        externalTaskId);
    List<CorrelationPeer> queues = findQueuePeers(boardTaskId);
    for (CorrelationPeer queue : queues) {
      link(event.eventId(), queue.eventId());
    }
    return queues.stream()
        .map(
            queue ->
                new MaintenanceInboundEffects.TaskCorrelation(
                    externalTaskId,
                    boardTaskId,
                    queue.queueEntryId(),
                    event.eventId(),
                    queue.eventId()))
        .toList();
  }

  private MaintenanceInboundEffects.TaskCorrelation correlateQueueEntry(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    UUID queueEntryId = UUID.fromString(event.aggregateId());
    UUID boardTaskId = UUID.fromString(event.payload().required("taskId").stringValue());
    lockCorrelation(boardTaskId);
    jdbc.update(
        """
        insert into maintenance_inbound_correlation(
          source_event_id,source_topic,board_task_id,queue_entry_id,state,recorded_at,updated_at)
        values (?,?,?,?,'PENDING',clock_timestamp(),clock_timestamp())
        on conflict (source_event_id) do nothing
        """,
        event.eventId(),
        event.sourceTopic(),
        boardTaskId,
        queueEntryId);
    CorrelationPeer board = findBoardPeer(boardTaskId);
    if (board == null) {
      return new MaintenanceInboundEffects.TaskCorrelation(
          null, boardTaskId, queueEntryId, null, event.eventId());
    }
    link(event.eventId(), board.eventId());
    return new MaintenanceInboundEffects.TaskCorrelation(
        board.externalTaskId(), boardTaskId, queueEntryId, board.eventId(), event.eventId());
  }

  private List<CorrelationPeer> findQueuePeers(UUID boardTaskId) {
    return jdbc.query(
        """
        select correlation.source_event_id,correlation.external_task_id,correlation.queue_entry_id
          from maintenance_inbound_correlation correlation
          join maintenance_inbound_replay_message replay on replay.event_id=correlation.source_event_id
         where correlation.board_task_id=? and correlation.state='PENDING'
           and correlation.queue_entry_id is not null
           and replay.event_type in (
             'task-board.queue-entry.completed.v1','task-board.queue-entry.cancelled.v1')
         order by (replay.envelope_body->'payload'->>'routeIndex')::integer,
                  replay.staged_at,correlation.source_event_id
         for update of correlation
        """,
        (result, row) ->
            new CorrelationPeer(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getObject(3, UUID.class)),
        boardTaskId);
  }

  private CorrelationPeer findBoardPeer(UUID boardTaskId) {
    return jdbc.query(
            """
            select correlation.source_event_id,correlation.external_task_id,correlation.queue_entry_id
              from maintenance_inbound_correlation correlation
              join maintenance_inbound_replay_message replay on replay.event_id=correlation.source_event_id
             where correlation.board_task_id=? and correlation.external_task_id is not null
             order by replay.aggregate_version desc,correlation.source_event_id limit 1 for update of correlation
            """,
            (result, row) ->
                new CorrelationPeer(
                    result.getObject(1, UUID.class),
                    result.getObject(2, UUID.class),
                    result.getObject(3, UUID.class)),
            boardTaskId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void lockCorrelation(UUID boardTaskId) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?::text,0))",
        result -> {
          result.next();
          return Boolean.TRUE;
        },
        boardTaskId.toString());
  }

  private void link(UUID first, UUID second) {
    jdbc.update(
        """
        update maintenance_inbound_correlation
           set counterpart_event_id=coalesce(
                 counterpart_event_id,case when source_event_id=? then ? else ? end),
               state=case when state='APPLIED' then state else 'CORRELATED' end,
               updated_at=clock_timestamp()
         where source_event_id in (?,?)
        """,
        first,
        second,
        first,
        first,
        second);
  }

  private void markCorrelationApplied(UUID eventId) {
    jdbc.update(
        """
        update maintenance_inbound_correlation set state='APPLIED',updated_at=clock_timestamp()
         where (source_event_id=? or counterpart_event_id=?) and counterpart_event_id is not null
        """,
        eventId,
        eventId);
  }

  private void markProcessed(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='PROCESSED',processed_at=clock_timestamp()
         where consumer_group=? and event_id=?
        """,
        MaintenanceTransportTopics.CONSUMER_GROUP,
        eventId);
  }

  private void quarantineInbox(UUID eventId, String reason) {
    jdbc.update(
        """
        update inbox_message set status='QUARANTINED',processed_at=clock_timestamp(),quarantine_reason=?
         where consumer_group=? and event_id=?
        """,
        reason,
        MaintenanceTransportTopics.CONSUMER_GROUP,
        eventId);
  }

  private static UUID nullableUuid(JsonNode payload, String field) {
    JsonNode value = payload.required(field);
    return value.isNull() ? null : UUID.fromString(value.stringValue());
  }

  private record Checkpoint(long version, boolean blocked) {}

  private record CorrelationPeer(UUID eventId, UUID externalTaskId, UUID queueEntryId) {}

  public enum Outcome {
    PROCESSED,
    DUPLICATE,
    VERSION_GAP,
    BLOCKED
  }
}
