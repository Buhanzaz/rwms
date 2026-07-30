package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class TaskBoardEventStore {
  private static final String PRODUCER = "task-board-service";
  private static final String PROJECTION = "task-board-live-v1";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final TaskBoardEventPayloadPolicy payloadPolicy;
  private final TaskBoardCorrelationContextProvider correlations;
  private final TaskBoardActorReferenceProvider actors;

  @Transactional(propagation = Propagation.MANDATORY)
  public long initialize(
      TaskBoardAggregateType aggregateType,
      UUID aggregateId,
      long projectionVersion,
      String eventType,
      Object payload) {
    return initialize(
        aggregateType, aggregateId, projectionVersion, eventType, payload, null, null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long initialize(
      TaskBoardAggregateType aggregateType,
      UUID aggregateId,
      long projectionVersion,
      String eventType,
      Object payload,
      OpaqueActorReference actor,
      CorrelationContext correlation) {
    if (projectionVersion < 0) throw new IllegalArgumentException("projectionVersion must not be negative");
    JsonNode safePayload = payloadPolicy.validateAndConvert(eventType, aggregateType, aggregateId, payload);
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recordedAt = databaseNow();
    try {
      jdbc.update(
          """
          insert into event_stream_head(
              aggregate_type, aggregate_id, current_version, last_event_id, updated_at)
          values (?, ?, ?, ?, ?)
          """,
          aggregateType.name(), aggregateId.toString(), projectionVersion, eventId, recordedAt);
    } catch (DuplicateKeyException exception) {
      throw new OptimisticLockingFailureException("Task-board aggregate stream already exists", exception);
    }
    persistFact(
        eventId,
        aggregateType,
        aggregateId,
        projectionVersion,
        eventType,
        safePayload,
        recordedAt,
        actor,
        correlation);
    return projectionVersion;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long append(
      TaskBoardAggregateType aggregateType,
      UUID aggregateId,
      long expectedVersion,
      String eventType,
      Object payload) {
    JsonNode safePayload = payloadPolicy.validateAndConvert(eventType, aggregateType, aggregateId, payload);
    long nextVersion = Math.addExact(expectedVersion, 1);
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recordedAt = databaseNow();
    int changed = jdbc.update(
        """
        update event_stream_head
           set current_version=?, last_event_id=?, updated_at=?
         where aggregate_type=? and aggregate_id=? and current_version=?
        """,
        nextVersion,
        eventId,
        recordedAt,
        aggregateType.name(),
        aggregateId.toString(),
        expectedVersion);
    if (changed != 1) throw new OptimisticLockingFailureException("Task-board aggregate version conflict");
    persistFact(
        eventId,
        aggregateType,
        aggregateId,
        nextVersion,
        eventType,
        safePayload,
        recordedAt,
        null,
        null);
    return nextVersion;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<StreamRef, Long> lockStreams(Collection<StreamRef> streams) {
    var result = new java.util.LinkedHashMap<StreamRef, Long>();
    streams.stream()
        .distinct()
        .sorted(Comparator.comparing((StreamRef ref) -> ref.aggregateType().name())
            .thenComparing(ref -> ref.aggregateId().toString()))
        .forEach(ref -> result.put(ref, lockCurrentVersion(ref.aggregateType(), ref.aggregateId())));
    return Map.copyOf(result);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long lockCurrentVersion(TaskBoardAggregateType aggregateType, UUID aggregateId) {
    Long version = jdbc.queryForObject(
        """
        select current_version
          from event_stream_head
         where aggregate_type=? and aggregate_id=?
         for update
        """,
        Long.class,
        aggregateType.name(),
        aggregateId.toString());
    if (version == null) throw new OptimisticLockingFailureException("Task-board aggregate stream does not exist");
    return version;
  }

  private void persistFact(
      UUID eventId,
      TaskBoardAggregateType aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      String eventType,
      JsonNode payload,
      OffsetDateTime recordedAt,
      OpaqueActorReference suppliedActor,
      CorrelationContext suppliedCorrelation) {
    String payloadJson = canonicalJson(write(payload));
    String payloadHash = sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
    var correlation = suppliedCorrelation == null ? correlations.current() : suppliedCorrelation;
    var actor = suppliedActor == null ? actors.current() : suppliedActor;
    Object envelope =
        envelope(
            eventId,
            eventType,
            aggregateType,
            aggregateId,
            aggregateVersion,
            recordedAt,
            correlation,
            actor,
            objectPayload(payload));
    String envelopeJson = canonicalJson(write(envelope));
    String envelopeHash = sha256(envelopeJson.getBytes(StandardCharsets.UTF_8));

    jdbc.update(
        """
        insert into domain_event(
            event_id, aggregate_type, aggregate_id, aggregate_version,
            event_type, event_version, occurred_at, recorded_at,
            correlation_id, causation_id, actor_ref, payload, payload_sha256, baseline)
        values (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, false)
        """,
        eventId,
        aggregateType.name(),
        aggregateId.toString(),
        aggregateVersion,
        eventType,
        recordedAt,
        recordedAt,
        correlation.correlationId(),
        correlation.causationId(),
        actor == null ? null : write(actor),
        payloadJson,
        payloadHash);
    jdbc.update(
        """
        insert into outbox_event(
            event_id, aggregate_type, aggregate_id, aggregate_version,
            event_type, topic, envelope_body, envelope_sha256,
            status, attempt_count, next_attempt_at, created_at)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
        """,
        eventId,
        aggregateType.name(),
        aggregateId.toString(),
        aggregateVersion,
        eventType,
        aggregateType.topic(),
        envelopeJson,
        envelopeHash,
        recordedAt,
        recordedAt);
    jdbc.update(
        """
        insert into projection_checkpoint(
            projection_name, aggregate_type, aggregate_id,
            aggregate_version, projection_sha256, updated_at)
        values (?, ?, ?, ?, ?, ?)
        on conflict (projection_name, aggregate_type, aggregate_id)
        do update set aggregate_version=excluded.aggregate_version,
                      projection_sha256=excluded.projection_sha256,
                      updated_at=excluded.updated_at
        """,
        PROJECTION,
        aggregateType.name(),
        aggregateId.toString(),
        aggregateVersion,
        payloadHash,
        recordedAt);
    if (eventsSinceSnapshotAnchor(aggregateType, aggregateId) >= 100) {
      jdbc.update(
          """
          insert into aggregate_snapshot(
              aggregate_type, aggregate_id, aggregate_version,
              state, state_sha256, recorded_at)
          values (?, ?, ?, ?::jsonb, ?, ?)
          on conflict do nothing
          """,
          aggregateType.name(),
          aggregateId.toString(),
          aggregateVersion,
          payloadJson,
          payloadHash,
          recordedAt);
    }
  }

  private long eventsSinceSnapshotAnchor(TaskBoardAggregateType aggregateType, UUID aggregateId) {
    Long count = jdbc.queryForObject(
        """
        select count(*)
          from domain_event event
         where event.aggregate_type=?
           and event.aggregate_id=?
           and not event.baseline
           and event.aggregate_version > greatest(
               coalesce((select max(snapshot.aggregate_version)
                           from aggregate_snapshot snapshot
                          where snapshot.aggregate_type=event.aggregate_type
                            and snapshot.aggregate_id=event.aggregate_id), -1),
               coalesce((select max(baseline.aggregate_version)
                           from domain_event baseline
                          where baseline.aggregate_type=event.aggregate_type
                            and baseline.aggregate_id=event.aggregate_id
                            and baseline.baseline), -1))
        """,
        Long.class,
        aggregateType.name(),
        aggregateId.toString());
    return count == null ? 0 : count;
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Task-board event value cannot be serialized", exception);
    }
  }

  private Map<String, Object> objectPayload(JsonNode payload) {
    try {
      return objectMapper.readValue(objectMapper.writeValueAsBytes(payload), new TypeReference<>() {});
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Task-board event payload must be a serializable object", exception);
    }
  }

  private Object envelope(
      UUID eventId,
      String eventType,
      TaskBoardAggregateType aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      OffsetDateTime recordedAt,
      CorrelationContext correlation,
      OpaqueActorReference actor,
      Map<String, Object> payload) {
    if (aggregateType != TaskBoardAggregateType.TASK_BOARD_ENTRY_OWNER_PROOF) {
      return new DomainEventEnvelopeV2<>(
          2,
          eventId,
          eventType,
          1,
          recordedAt.toInstant(),
          recordedAt.toInstant(),
          PRODUCER,
          aggregateType.name(),
          aggregateId.toString(),
          aggregateVersion,
          correlation,
          actor,
          payload);
    }
    // The frozen cross-service contract deliberately shortens only this event
    // segment to "entry-owner-proof" while retaining the unambiguous aggregate
    // type TASK_BOARD_ENTRY_OWNER_PROOF expected by media-service.
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("envelopeVersion", 2);
    value.put("eventId", eventId);
    value.put("eventType", eventType);
    value.put("eventVersion", 1);
    value.put("occurredAt", recordedAt.toInstant());
    value.put("recordedAt", recordedAt.toInstant());
    value.put("producer", PRODUCER);
    value.put("aggregateType", aggregateType.name());
    value.put("aggregateId", aggregateId.toString());
    value.put("aggregateVersion", aggregateVersion);
    value.put("correlation", correlation);
    value.put("actorRef", actor);
    value.put("payload", payload);
    return value;
  }

  private String canonicalJson(String value) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (canonical == null) throw new IllegalStateException("PostgreSQL did not canonicalize task-board event JSON");
    return canonical;
  }

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }

  public record StreamRef(TaskBoardAggregateType aggregateType, UUID aggregateId) {}
}
