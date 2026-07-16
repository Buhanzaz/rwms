package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskBoardInboxProcessor {
  private static final String SHADOW_PROJECTION = "task-board-kafka-shadow-v1";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final ObjectMapper strictObjectMapper;
  private final TaskBoardEventPayloadPolicy payloadPolicy;
  private final TaskBoardEventingMetrics metrics;

  public TaskBoardInboxProcessor(JdbcTemplate jdbc, ObjectMapper objectMapper,
      TaskBoardEventPayloadPolicy payloadPolicy, TaskBoardEventingMetrics metrics) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.strictObjectMapper = objectMapper.rebuild()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    this.payloadPolicy = payloadPolicy;
    this.metrics = metrics;
  }

  @Transactional
  public void process(byte[] serializedEnvelope, TaskBoardAggregateType expectedType) {
    var envelope = readAndValidate(serializedEnvelope, expectedType);
    String aggregateId = UUID.fromString(envelope.aggregateId()).toString();
    String canonicalEnvelope = canonicalJson(write(envelope));
    String messageHash = TaskBoardEventStore.sha256(canonicalEnvelope.getBytes(StandardCharsets.UTF_8));
    requireAuthoritativeEnvelope(envelope, expectedType, canonicalEnvelope, messageHash);
    int inserted = jdbc.update(
        """
        insert into inbox_message(
          consumer_group,event_id,aggregate_type,aggregate_id,aggregate_version,
          payload_sha256,status,attempt_count,received_at)
        values (?,?,?,?,?,?,'RECEIVED',0,clock_timestamp())
        on conflict (consumer_group,event_id) do nothing
        """,
        TaskBoardAggregateType.CONSUMER_GROUP, envelope.eventId(), envelope.aggregateType(),
        aggregateId, envelope.aggregateVersion(), messageHash);
    if (inserted == 0) {
      metrics.inboxDuplicate();
      return;
    }
    seedBaselineCheckpointIfRequired(envelope, aggregateId);
    Checkpoint checkpoint = lockCheckpoint(envelope.aggregateType(), aggregateId);
    if (checkpoint.blocked()) {
      quarantineInbox(envelope.eventId(), "AGGREGATE_BLOCKED");
      metrics.inboxQuarantined();
      return;
    }
    long expectedVersion = checkpoint.version() + 1;
    if (envelope.aggregateVersion() > expectedVersion) {
      quarantineGap(envelope, aggregateId, expectedVersion, messageHash);
      metrics.inboxQuarantined();
      return;
    }
    if (envelope.aggregateVersion() < expectedVersion) {
      markProcessed(envelope.eventId());
      metrics.inboxDuplicate();
      return;
    }
    String payloadJson = canonicalJson(write(envelope.payload()));
    String payloadHash = TaskBoardEventStore.sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at)
        values (?,?,?,?,?,clock_timestamp())
        on conflict (projection_name,aggregate_type,aggregate_id) do update
          set aggregate_version=excluded.aggregate_version,
              projection_sha256=excluded.projection_sha256,updated_at=excluded.updated_at
        """,
        SHADOW_PROJECTION, envelope.aggregateType(), aggregateId,
        envelope.aggregateVersion(), payloadHash);
    jdbc.update(
        """
        update consumer_aggregate_checkpoint
           set last_event_id=?,last_aggregate_version=?,updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type=? and aggregate_id=?
        """,
        envelope.eventId(), envelope.aggregateVersion(), TaskBoardAggregateType.CONSUMER_GROUP,
        envelope.aggregateType(), aggregateId);
    markProcessed(envelope.eventId());
  }

  private void requireAuthoritativeEnvelope(
      DomainEventEnvelopeV2<Map<String, Object>> envelope,
      TaskBoardAggregateType aggregateType,
      String canonicalEnvelope,
      String envelopeHash) {
    Integer matching = jdbc.queryForObject(
        """
        select count(*) from outbox_event outbox
          join domain_event event on event.event_id=outbox.event_id
         where outbox.event_id=? and outbox.aggregate_type=? and outbox.aggregate_id=?
           and outbox.aggregate_version=? and outbox.event_type=? and outbox.topic=?
           and outbox.envelope_body=?::jsonb and outbox.envelope_sha256=?
           and outbox.envelope_sha256=encode(sha256(convert_to(outbox.envelope_body::text,'UTF8')),'hex')
           and event.aggregate_type=outbox.aggregate_type
           and event.aggregate_id=outbox.aggregate_id
           and event.aggregate_version=outbox.aggregate_version
           and event.event_type=outbox.event_type
           and event.payload_sha256=encode(sha256(convert_to(event.payload::text,'UTF8')),'hex')
           and case
             when jsonb_exists_all(outbox.envelope_body, array[
                    'envelopeVersion','eventId','eventType','eventVersion','occurredAt',
                    'recordedAt','producer','aggregateType','aggregateId','aggregateVersion',
                    'correlation','actorRef','payload'])
              and outbox.envelope_body - array[
                    'envelopeVersion','eventId','eventType','eventVersion','occurredAt',
                    'recordedAt','producer','aggregateType','aggregateId','aggregateVersion',
                    'correlation','actorRef','payload'] = '{}'::jsonb
              and jsonb_typeof(outbox.envelope_body->'correlation')='object'
             then outbox.envelope_body->>'envelopeVersion'='2'
              and outbox.envelope_body->>'eventId'=event.event_id::text
              and outbox.envelope_body->>'eventType'=event.event_type
              and outbox.envelope_body->>'eventVersion'=event.event_version::text
              and (outbox.envelope_body->>'occurredAt')::timestamptz
                    is not distinct from event.occurred_at
              and (outbox.envelope_body->>'recordedAt')::timestamptz=event.recorded_at
              and outbox.envelope_body->>'producer'='task-board-service'
              and outbox.envelope_body->>'aggregateType'=event.aggregate_type
              and outbox.envelope_body->>'aggregateId'=event.aggregate_id
              and outbox.envelope_body->>'aggregateVersion'=event.aggregate_version::text
              and jsonb_exists_all(
                    outbox.envelope_body->'correlation',array['correlationId','causationId'])
              and (outbox.envelope_body->'correlation')
                    - array['correlationId','causationId']='{}'::jsonb
              and outbox.envelope_body->'correlation'->>'correlationId'=event.correlation_id::text
              and outbox.envelope_body->'correlation'->>'causationId'
                    is not distinct from event.causation_id::text
              and outbox.envelope_body->'actorRef'=coalesce(event.actor_ref,'null'::jsonb)
              and outbox.envelope_body->'payload'=event.payload
             else false
           end
        """,
        Integer.class, envelope.eventId(), envelope.aggregateType(), envelope.aggregateId(),
        envelope.aggregateVersion(), envelope.eventType(), aggregateType.topic(),
        canonicalEnvelope, envelopeHash);
    if (matching == null || matching != 1) throw new TaskBoardEventValidationException();
  }

  private void seedBaselineCheckpointIfRequired(
      DomainEventEnvelopeV2<Map<String, Object>> envelope, String aggregateId) {
    Integer existing = jdbc.queryForObject(
        "select count(*) from consumer_aggregate_checkpoint where consumer_group=? and aggregate_type=? and aggregate_id=?",
        Integer.class, TaskBoardAggregateType.CONSUMER_GROUP, envelope.aggregateType(), aggregateId);
    if (existing != null && existing > 0) return;
    var baseline = jdbc.query(
        """
        select event_id,aggregate_version from domain_event
         where aggregate_type=? and aggregate_id=? and baseline
         order by aggregate_version desc limit 1
        """,
        (rs, row) -> new Baseline(rs.getObject("event_id", UUID.class), rs.getLong("aggregate_version")),
        envelope.aggregateType(), aggregateId).stream().findFirst();
    jdbc.update(
        """
        insert into consumer_aggregate_checkpoint(
          consumer_group,aggregate_type,aggregate_id,last_event_id,last_aggregate_version,blocked,updated_at)
        values (?,?,?,?,?,false,clock_timestamp()) on conflict do nothing
        """,
        TaskBoardAggregateType.CONSUMER_GROUP, envelope.aggregateType(), aggregateId,
        baseline.map(Baseline::eventId).orElse(null), baseline.map(Baseline::version).orElse(-1L));
  }

  private Checkpoint lockCheckpoint(String aggregateType, String aggregateId) {
    return jdbc.queryForObject(
        """
        select last_aggregate_version,blocked from consumer_aggregate_checkpoint
         where consumer_group=? and aggregate_type=? and aggregate_id=? for update
        """,
        (rs, row) -> new Checkpoint(rs.getLong(1), rs.getBoolean(2)),
        TaskBoardAggregateType.CONSUMER_GROUP, aggregateType, aggregateId);
  }

  private void quarantineGap(DomainEventEnvelopeV2<Map<String, Object>> envelope,
      String aggregateId, long expectedVersion, String messageHash) {
    jdbc.update(
        """
        update consumer_aggregate_checkpoint set blocked=true,
          quarantine_reason='AGGREGATE_VERSION_GAP',updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type=? and aggregate_id=?
        """, TaskBoardAggregateType.CONSUMER_GROUP, envelope.aggregateType(), aggregateId);
    jdbc.update(
        """
        insert into version_gap_quarantine(
          quarantine_id,consumer_group,aggregate_type,aggregate_id,expected_version,
          received_version,received_event_id,payload_sha256,reason_code,status,detected_at)
        values (?,?,?,?,?,?,?,?,'AGGREGATE_VERSION_GAP','OPEN',clock_timestamp())
        on conflict (consumer_group,received_event_id) do nothing
        """,
        UUID.randomUUID(), TaskBoardAggregateType.CONSUMER_GROUP, envelope.aggregateType(),
        aggregateId, expectedVersion, envelope.aggregateVersion(), envelope.eventId(), messageHash);
    quarantineInbox(envelope.eventId(), "AGGREGATE_VERSION_GAP");
  }

  private void quarantineInbox(UUID eventId, String reason) {
    jdbc.update(
        """
        update inbox_message set status='QUARANTINED',quarantine_reason=?,processed_at=clock_timestamp()
         where consumer_group=? and event_id=?
        """, reason, TaskBoardAggregateType.CONSUMER_GROUP, eventId);
  }

  private void markProcessed(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='PROCESSED',processed_at=clock_timestamp()
         where consumer_group=? and event_id=?
        """, TaskBoardAggregateType.CONSUMER_GROUP, eventId);
  }

  private DomainEventEnvelopeV2<Map<String, Object>> readAndValidate(
      byte[] value, TaskBoardAggregateType expectedType) {
    try {
      DomainEventEnvelopeV2<Map<String, Object>> envelope = strictObjectMapper
          .readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, Object>>>() {})
          .readValue(value);
      if (!expectedType.name().equals(envelope.aggregateType())
          || !"task-board-service".equals(envelope.producer())) throw new IllegalArgumentException();
      UUID aggregateId = UUID.fromString(envelope.aggregateId());
      JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsBytes(envelope.payload()));
      payloadPolicy.validateNode(envelope.eventType(), expectedType, aggregateId, payload);
      return envelope;
    } catch (RuntimeException exception) {
      throw new TaskBoardEventValidationException();
    }
  }

  private String write(Object value) {
    try { return objectMapper.writeValueAsString(value); }
    catch (tools.jackson.core.JacksonException exception) { throw new TaskBoardEventValidationException(); }
  }

  private String canonicalJson(String value) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (canonical == null) throw new IllegalStateException("PostgreSQL did not canonicalize task-board consumer JSON");
    return canonical;
  }

  private record Baseline(UUID eventId, long version) {}
  private record Checkpoint(long version, boolean blocked) {}
}
