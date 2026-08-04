package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MaintenanceEventStore {
  static final String LOCAL_EVENT_MODEL = "maintenance-full-state-v1";
  private static final String PRODUCER = "maintenance-service";
  private static final String PROJECTION = "maintenance-live-v1";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final MaintenanceEventPayloadPolicy payloadPolicy;
  private final MaintenanceCorrelationContextProvider correlations;
  private final MaintenanceActorReferenceProvider actors;

  public MaintenanceEventStore(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      MaintenanceEventPayloadPolicy payloadPolicy,
      MaintenanceCorrelationContextProvider correlations,
      MaintenanceActorReferenceProvider actors) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.payloadPolicy = payloadPolicy;
    this.correlations = correlations;
    this.actors = actors;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void initialize(
      MaintenanceAggregateType type,
      UUID id,
      long version,
      MaintenanceEventType eventType,
      Map<String, ?> localPayload,
      Map<String, ?> integrationPayload,
      Map<String, ?> snapshot) {
    if (version < 0) throw new IllegalArgumentException("Aggregate version must not be negative");
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recorded = databaseNow();
    try {
      jdbc.update(
          "insert into event_stream_head(aggregate_type,aggregate_id,current_version,last_event_id,updated_at) values (?, ?, ?, ?, ?)",
          type.name(), id.toString(), version, eventId, recorded);
    } catch (DuplicateKeyException exception) {
      throw new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", "Maintenance aggregate stream already exists");
    }
    persist(eventId, type, id, version, eventType, localPayload, integrationPayload, snapshot, recorded);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      MaintenanceAggregateType type,
      UUID id,
      long expectedVersion,
      MaintenanceEventType eventType,
      Map<String, ?> localPayload,
      Map<String, ?> integrationPayload,
      Map<String, ?> snapshot) {
    if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
    long next = Math.addExact(expectedVersion, 1);
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recorded = databaseNow();
    int changed = jdbc.update(
        "update event_stream_head set current_version=?,last_event_id=?,updated_at=? where aggregate_type=? and aggregate_id=? and current_version=?",
        next, eventId, recorded, type.name(), id.toString(), expectedVersion);
    if (changed != 1) {
      throw new MaintenanceConflictException("MAINTENANCE_VERSION_CONFLICT", "Maintenance aggregate stream version conflict");
    }
    persist(eventId, type, id, next, eventType, localPayload, integrationPayload, snapshot, recorded);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<StreamRef, Long> lockStreams(Collection<StreamRef> streams) {
    Map<StreamRef, Long> result = new LinkedHashMap<>();
    streams.stream()
        .distinct()
        .sorted(Comparator.comparing((StreamRef value) -> value.type().name())
            .thenComparing(value -> value.id().toString()))
        .forEach(value -> result.put(value, lockCurrentVersion(value.type(), value.id())));
    return Collections.unmodifiableMap(result);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long lockCurrentVersion(MaintenanceAggregateType type, UUID id) {
    Long version = jdbc.queryForObject(
        "select current_version from event_stream_head where aggregate_type=? and aggregate_id=? for update",
        Long.class, type.name(), id.toString());
    if (version == null) {
      throw new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", "Maintenance aggregate stream does not exist");
    }
    return version;
  }

  /**
   * Reads a persisted terminal fact while the caller already owns the aggregate's local lock.
   * This is deliberately event-store truth rather than a reconstructed projection timestamp.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<EventFact> latestFact(
      MaintenanceAggregateType type, UUID id, MaintenanceEventType eventType) {
    if (type == null || id == null || eventType == null) {
      throw new IllegalArgumentException("Maintenance event fact identity is required");
    }
    List<EventFact> facts =
        jdbc.query(
            """
            select event_id, occurred_at
              from domain_event
             where aggregate_type=? and aggregate_id=? and event_type=?
             order by aggregate_version desc
             limit 1
            """,
            (result, row) ->
                new EventFact(
                    result.getObject("event_id", UUID.class),
                    result.getObject("occurred_at", OffsetDateTime.class)),
            type.name(),
            id.toString(),
            eventType.value());
    return facts.stream().findFirst();
  }

  private void persist(
      UUID eventId,
      MaintenanceAggregateType type,
      UUID id,
      long version,
      MaintenanceEventType eventType,
      Map<String, ?> localPayload,
      Map<String, ?> integrationPayload,
      Map<String, ?> snapshot,
      OffsetDateTime recorded) {
    validateLocalEvent(type, id, version, localPayload, snapshot);
    Map<String, Object> authoritativeFact = new LinkedHashMap<>();
    authoritativeFact.put("model", LOCAL_EVENT_MODEL);
    authoritativeFact.put("event", localPayload);
    authoritativeFact.put("state", snapshot);
    String localJson = canonicalJson(write(authoritativeFact));
    String localHash = MaintenanceChecksum.sha256(localJson.getBytes(StandardCharsets.UTF_8));
    JsonNode safe = mapper.valueToTree(integrationPayload);
    payloadPolicy.validateNode(eventType.value(), type, id, safe);
    Map<String, Object> safePayload = mapper.convertValue(safe, new TypeReference<Map<String, Object>>() {});
    var correlation = correlations.current();
    var actor = actors.current();
    DomainEventEnvelopeV2<Map<String, ?>> envelope = new DomainEventEnvelopeV2<>(
        2, eventId, eventType.value(), 1, recorded.toInstant(), recorded.toInstant(), PRODUCER,
        type.name(), id.toString(), version, correlation, actor, safePayload);
    String envelopeJson = canonicalJson(write(envelope));
    String envelopeHash = MaintenanceChecksum.sha256(envelopeJson.getBytes(StandardCharsets.UTF_8));
    jdbc.update("""
        insert into domain_event(event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,causation_id,actor_ref,payload,payload_sha256,baseline)
        values (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, false)
        """, eventId, type.name(), id.toString(), version, eventType.value(), recorded, recorded,
        correlation.correlationId(), correlation.causationId(), actor == null ? null : write(actor), localJson, localHash);
    jdbc.update("""
        insert into outbox_event(event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body,
          envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
        """, eventId, type.name(), id.toString(), version, eventType.value(), type.topic(), envelopeJson,
        envelopeHash, recorded, recorded);
    String snapshotJson = canonicalJson(write(snapshot));
    String snapshotHash = MaintenanceChecksum.sha256(snapshotJson.getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        "insert into aggregate_snapshot(aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at) values (?, ?, ?, ?::jsonb, ?, ?)",
        type.name(), id.toString(), version, snapshotJson, snapshotHash, recorded);
    jdbc.update("""
        insert into projection_checkpoint(projection_name,aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at)
        values (?, ?, ?, ?, ?, ?)
        on conflict (projection_name,aggregate_type,aggregate_id) do update set aggregate_version=excluded.aggregate_version,
          projection_sha256=excluded.projection_sha256,updated_at=excluded.updated_at
        """, PROJECTION, type.name(), id.toString(), version, snapshotHash, recorded);
  }

  private static void validateLocalEvent(
      MaintenanceAggregateType type,
      UUID id,
      long version,
      Map<String, ?> localPayload,
      Map<String, ?> snapshot) {
    if (localPayload == null || snapshot == null) {
      throw new IllegalArgumentException(
          "Maintenance local event and full-state snapshot are required");
    }
    Object stateId = snapshot.get("id");
    Object stateVersion = snapshot.get("version");
    Object stateWarehouseId = snapshot.get("warehouseId");
    if (!id.toString().equals(String.valueOf(stateId))
        || !(stateVersion instanceof Number number)
        || number.longValue() != version
        || stateWarehouseId == null) {
      throw new IllegalArgumentException(
          "Maintenance full-state snapshot identity does not match " + type + ":" + id);
    }
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
  }

  private String canonicalJson(String json) {
    String value = jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
    if (value == null) throw new IllegalStateException("PostgreSQL did not canonicalize maintenance JSON");
    return value;
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Maintenance event cannot be serialized", exception);
    }
  }

  public record StreamRef(MaintenanceAggregateType type, UUID id) {
    public StreamRef {
      if (type == null || id == null) throw new IllegalArgumentException("Stream reference is required");
    }
  }

  public record EventFact(UUID eventId, OffsetDateTime occurredAt) {
    public EventFact {
      if (eventId == null || occurredAt == null) {
        throw new IllegalArgumentException("Maintenance event fact is incomplete");
      }
    }
  }
}
