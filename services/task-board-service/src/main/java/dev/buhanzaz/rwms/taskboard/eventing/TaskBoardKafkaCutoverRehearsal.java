package dev.buhanzaz.rwms.taskboard.eventing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performs a bounded, non-destructive Rabbit-to-Kafka cutover rehearsal.
 *
 * <p>The operator must stop task-board writers before invoking this verifier. The transaction also
 * takes PostgreSQL share locks on every command projection so that a falsely asserted freeze cannot
 * produce a moving inventory while evidence is collected. Historical Rabbit rows are never updated
 * or deleted.
 */
@Service
@RequiredArgsConstructor
public class TaskBoardKafkaCutoverRehearsal {
  private static final int MAX_ALLOWED_CANDIDATES = 10_000;

  private final JdbcTemplate jdbc;

  @Transactional
  public Report rehearse(Request request) {
    requireRequest(request);
    jdbc.execute("set local lock_timeout = '5s'");
    jdbc.execute("set local statement_timeout = '30s'");
    lockCommandProjections();

    long legacyTotal = count("select count(*) from task_board_outbox");
    long legacyUnpublished =
        count("select count(*) from task_board_outbox where status <> 'PUBLISHED'");
    long legacyUnresolved =
        count(
            """
            select count(*) from task_board_outbox legacy
             where legacy.status <> 'PUBLISHED'
               and not exists (
                 select 1 from task_board_kafka_cutover_map mapping
                  where mapping.legacy_event_id=legacy.event_id)
            """);
    if (legacyUnresolved > request.maxLegacyCandidates()) {
      throw new IllegalStateException(
          "Legacy task-board outbox inventory exceeds the bounded rehearsal limit");
    }

    List<LegacyCandidate> candidates = loadLegacyCandidates(request.maxLegacyCandidates());
    int mirrored = 0;
    int retargeted = 0;
    int unmappable = 0;
    for (LegacyCandidate candidate : candidates) {
      String kafkaEventType = kafkaEventType(candidate.eventType());
      if (kafkaEventType == null) {
        unmappable++;
        continue;
      }
      Optional<UUID> existing = canonicalKafkaOutbox(candidate, kafkaEventType);
      if (existing.isPresent()) {
        recordMapping(candidate.legacyEventId(), existing.orElseThrow());
        mirrored++;
        continue;
      }
      Optional<UUID> inserted = retargetFromAuthoritativeEvent(candidate, kafkaEventType);
      if (inserted.isPresent()) {
        recordMapping(candidate.legacyEventId(), inserted.orElseThrow());
        retargeted++;
      } else {
        unmappable++;
      }
    }

    legacyUnresolved =
        count(
            """
            select count(*) from task_board_outbox legacy
             where legacy.status <> 'PUBLISHED'
               and not exists (
                 select 1 from task_board_kafka_cutover_map mapping
                  where mapping.legacy_event_id=legacy.event_id)
            """);

    long kafkaBacklog =
        count("select count(*) from outbox_event where status in ('PENDING','IN_FLIGHT')");
    long kafkaTerminalFailures =
        count("select count(*) from outbox_event where status in ('DLT','QUARANTINED')");
    long publishedWithoutInbox =
        count(
            """
            select count(*) from outbox_event outbox
             where outbox.status='PUBLISHED'
               and not exists (
                 select 1 from inbox_message inbox
                  where inbox.consumer_group=? and inbox.event_id=outbox.event_id
                    and inbox.status='PROCESSED')
            """,
            TaskBoardAggregateType.CONSUMER_GROUP);
    long inboxWithoutPublished =
        count(
            """
            select count(*) from inbox_message inbox
             where inbox.consumer_group=? and inbox.status='PROCESSED'
               and not exists (
                 select 1 from outbox_event outbox
                  where outbox.event_id=inbox.event_id and outbox.status='PUBLISHED')
            """,
            TaskBoardAggregateType.CONSUMER_GROUP);
    long aggregateLag =
        count(
            """
            select count(*) from (
              select outbox.aggregate_type,outbox.aggregate_id,max(outbox.aggregate_version) published_version
                from outbox_event outbox where outbox.status='PUBLISHED'
               group by outbox.aggregate_type,outbox.aggregate_id
            ) published
            left join consumer_aggregate_checkpoint checkpoint
              on checkpoint.consumer_group=?
             and checkpoint.aggregate_type=published.aggregate_type
             and checkpoint.aggregate_id=published.aggregate_id
             and not checkpoint.blocked
           where checkpoint.last_aggregate_version is null
              or checkpoint.last_aggregate_version < published.published_version
            """,
            TaskBoardAggregateType.CONSUMER_GROUP);
    long openVersionGaps =
        count("select count(*) from version_gap_quarantine where status='OPEN'");

    boolean ready =
        legacyUnresolved == 0
            && unmappable == 0
            && kafkaBacklog == 0
            && kafkaTerminalFailures == 0
            && publishedWithoutInbox == 0
            && inboxWithoutPublished == 0
            && aggregateLag == 0
            && openVersionGaps == 0;
    return new Report(
        request.writeFreezeConfirmed(),
        legacyTotal,
        legacyUnpublished,
        legacyUnresolved,
        mirrored,
        retargeted,
        unmappable,
        kafkaBacklog,
        kafkaTerminalFailures,
        publishedWithoutInbox,
        inboxWithoutPublished,
        aggregateLag,
        openVersionGaps,
        ready);
  }

  private void lockCommandProjections() {
    jdbc.execute(
        """
        lock table worker_class, worker, worker_group, work_queue,
          queue_usage_reference, board_task, queue_entry in share mode
        """);
    jdbc.execute("lock table task_board_outbox, outbox_event in share row exclusive mode");
  }

  private List<LegacyCandidate> loadLegacyCandidates(int limit) {
    return jdbc.query(
        """
        select legacy.event_id,legacy.event_type,legacy.aggregate_id,legacy.aggregate_version
          from task_board_outbox legacy
         where legacy.status <> 'PUBLISHED'
           and not exists (
             select 1 from task_board_kafka_cutover_map mapping
              where mapping.legacy_event_id=legacy.event_id)
         order by legacy.occurred_at,legacy.event_id
         limit ?
        """,
        (result, row) ->
            new LegacyCandidate(
                result.getObject("event_id", UUID.class),
                result.getString("event_type"),
                result.getString("aggregate_id"),
                result.getLong("aggregate_version")),
        limit);
  }

  private Optional<UUID> canonicalKafkaOutbox(
      LegacyCandidate candidate, String kafkaEventType) {
    return jdbc.query(
            """
            select outbox.event_id from outbox_event outbox
              join domain_event event on event.event_id=outbox.event_id
             where outbox.aggregate_type='BOARD_TASK' and outbox.aggregate_id=?
               and outbox.aggregate_version=? and outbox.event_type=?
               and outbox.topic=? and event.baseline=false
               and event.aggregate_type=outbox.aggregate_type
               and event.aggregate_id=outbox.aggregate_id
               and event.aggregate_version=outbox.aggregate_version
               and event.event_type=outbox.event_type
            """,
            (result, row) -> result.getObject("event_id", UUID.class),
            candidate.aggregateId(),
            candidate.aggregateVersion(),
            kafkaEventType,
            TaskBoardAggregateType.BOARD_TASK.topic())
        .stream()
        .findFirst();
  }

  private Optional<UUID> retargetFromAuthoritativeEvent(
      LegacyCandidate candidate, String kafkaEventType) {
    return jdbc.query(
        """
        with authoritative as (
          select event.event_id,event.aggregate_type,event.aggregate_id,event.aggregate_version,
                 event.event_type,event.event_version,event.occurred_at,event.recorded_at,
                 event.correlation_id,event.causation_id,event.actor_ref,event.payload
            from domain_event event
           where event.aggregate_type='BOARD_TASK' and event.aggregate_id=?
             and event.aggregate_version=? and event.event_type=? and not event.baseline
        ), serialized as (
          select authoritative.*,
                 jsonb_build_object(
                   'envelopeVersion',2,
                   'eventId',event_id,
                   'eventType',event_type,
                   'eventVersion',event_version,
                   'occurredAt',occurred_at,
                   'recordedAt',recorded_at,
                   'producer','task-board-service',
                   'aggregateType',aggregate_type,
                   'aggregateId',aggregate_id,
                   'aggregateVersion',aggregate_version,
                   'correlation',jsonb_build_object(
                     'correlationId',correlation_id,'causationId',causation_id),
                   'actorRef',actor_ref,
                   'payload',payload) envelope_body
            from authoritative
        )
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
          envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,?,
               envelope_body,
               encode(sha256(convert_to(envelope_body::text,'UTF8')),'hex'),
               'PENDING',0,clock_timestamp(),clock_timestamp()
          from serialized
        on conflict (event_id) do nothing
        returning event_id
        """,
        (result, row) -> result.getObject("event_id", UUID.class),
        candidate.aggregateId(),
        candidate.aggregateVersion(),
        kafkaEventType,
        TaskBoardAggregateType.BOARD_TASK.topic())
        .stream()
        .findFirst();
  }

  private void recordMapping(UUID legacyEventId, UUID kafkaEventId) {
    jdbc.update(
        """
        insert into task_board_kafka_cutover_map(
          legacy_event_id,kafka_event_id,mapping_type,mapped_at)
        values (?,?,'AUTHORITATIVE_EVENT_V2',clock_timestamp())
        on conflict (legacy_event_id) do nothing
        """,
        legacyEventId,
        kafkaEventId);
    long matching =
        count(
            """
            select count(*) from task_board_kafka_cutover_map
             where legacy_event_id=? and kafka_event_id=?
               and mapping_type='AUTHORITATIVE_EVENT_V2'
            """,
            legacyEventId,
            kafkaEventId);
    if (matching != 1) {
      throw new IllegalStateException("Legacy task-board event has a conflicting Kafka mapping");
    }
  }

  private String kafkaEventType(String legacyEventType) {
    return switch (legacyEventType) {
      case "task-board.board-task.created" -> TaskBoardEventTypes.BOARD_TASK_CREATED;
      case "task-board.board-task.cancelled" -> TaskBoardEventTypes.BOARD_TASK_CANCELLED;
      default -> null;
    };
  }

  private long count(String sql, Object... arguments) {
    Long value = jdbc.queryForObject(sql, Long.class, arguments);
    return value == null ? 0 : value;
  }

  private static void requireRequest(Request request) {
    if (request == null || !request.writeFreezeConfirmed()) {
      throw new IllegalArgumentException(
          "An explicit task-board write-freeze confirmation is required");
    }
    if (request.maxLegacyCandidates() < 1
        || request.maxLegacyCandidates() > MAX_ALLOWED_CANDIDATES) {
      throw new IllegalArgumentException("Invalid bounded legacy candidate limit");
    }
  }

  private record LegacyCandidate(
      UUID legacyEventId, String eventType, String aggregateId, long aggregateVersion) {}

  public record Request(boolean writeFreezeConfirmed, int maxLegacyCandidates) {}

  public record Report(
      boolean writeFreezeConfirmed,
      long legacyTotal,
      long legacyUnpublished,
      long legacyUnresolved,
      int legacyAlreadyMirrored,
      int legacyRetargeted,
      int legacyUnmappable,
      long kafkaBacklog,
      long kafkaTerminalFailures,
      long publishedWithoutInbox,
      long inboxWithoutPublished,
      long laggingAggregates,
      long openVersionGaps,
      boolean ready) {}
}
