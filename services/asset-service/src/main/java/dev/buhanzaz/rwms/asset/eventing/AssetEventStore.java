package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetInvalidationHub;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AssetEventStore {
  private static final String PRODUCER = "asset-service";
  private static final String PROJECTION = "asset-live-v1";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AssetEventPayloadPolicy payloads;
  private final AssetCorrelationContextProvider correlations;
  private final AssetActorReferenceProvider actors;
  private final AssetInvalidationHub invalidations;

  public AssetEventStore(JdbcTemplate jdbc, ObjectMapper mapper, AssetEventPayloadPolicy payloads,
      AssetCorrelationContextProvider correlations, AssetActorReferenceProvider actors,
      AssetInvalidationHub invalidations) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.payloads = payloads;
    this.correlations = correlations;
    this.actors = actors;
    this.invalidations = invalidations;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void initialize(AssetAggregateType type, UUID id, long version, AssetEventType eventType,
      Map<String, ?> payload, Map<String, ?> snapshot) {
    if (version < 0) throw new IllegalArgumentException("Aggregate version must not be negative");
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recorded = databaseNow();
    try {
      jdbc.update("insert into event_stream_head(aggregate_type,aggregate_id,current_version,last_event_id,updated_at) values (?, ?, ?, ?, ?)",
          type.name(), id.toString(), version, eventId, recorded);
    } catch (DuplicateKeyException exception) {
      throw new AssetConflictException("Asset aggregate stream already exists");
    }
    persist(eventId, type, id, version, eventType, payload, snapshot, recorded);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(AssetAggregateType type, UUID id, long expectedVersion, AssetEventType eventType,
      Map<String, ?> payload, Map<String, ?> snapshot) {
    if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
    long next = Math.addExact(expectedVersion, 1);
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recorded = databaseNow();
    int changed = jdbc.update(
        "update event_stream_head set current_version=?,last_event_id=?,updated_at=? where aggregate_type=? and aggregate_id=? and current_version=?",
        next, eventId, recorded, type.name(), id.toString(), expectedVersion);
    if (changed != 1) throw new AssetConflictException("Asset aggregate stream version conflict");
    persist(eventId, type, id, next, eventType, payload, snapshot, recorded);
  }

  /** Locks every stream in a stable order before a multi-balance transfer is changed. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Map<StreamRef, Long> lockStreams(Collection<StreamRef> streams) {
    Map<StreamRef, Long> result = new LinkedHashMap<>();
    streams.stream().distinct().sorted(Comparator.comparing((StreamRef ref) -> ref.type().name()).thenComparing(ref -> ref.id().toString()))
        .forEach(ref -> result.put(ref, lockCurrentVersion(ref.type(), ref.id())));
    return Map.copyOf(result);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long lockCurrentVersion(AssetAggregateType type, UUID id) {
    Long version = jdbc.queryForObject(
        "select current_version from event_stream_head where aggregate_type=? and aggregate_id=? for update",
        Long.class, type.name(), id.toString());
    if (version == null) throw new AssetConflictException("Asset aggregate stream does not exist");
    return version;
  }

  private void persist(UUID eventId, AssetAggregateType type, UUID id, long version, AssetEventType eventType,
      Map<String, ?> rawPayload, Map<String, ?> rawSnapshot, OffsetDateTime recorded) {
    JsonNode payload = payloads.validateAndConvert(eventType, type, id, rawPayload);
    String payloadJson = canonicalJson(write(payload));
    String payloadHash = AssetChecksum.sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
    Map<String, Object> sanitizedPayload = mapper.convertValue(
        payload, new TypeReference<Map<String, Object>>() {});
    var correlation = correlations.current();
    var actor = actors.current();
    DomainEventEnvelopeV2<Map<String, ?>> envelope = new DomainEventEnvelopeV2<>(
        2, eventId, eventType.value(), 1, recorded.toInstant(), recorded.toInstant(), PRODUCER,
        type.name(), id.toString(), version, correlation, actor, sanitizedPayload);
    String envelopeJson = canonicalJson(write(envelope));
    String envelopeHash = AssetChecksum.sha256(envelopeJson.getBytes(StandardCharsets.UTF_8));
    jdbc.update("""
        insert into domain_event(event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,causation_id,actor_ref,payload,payload_sha256,baseline)
        values (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, false)
        """, eventId, type.name(), id.toString(), version, eventType.value(), recorded, recorded,
        correlation.correlationId(), correlation.causationId(), actor == null ? null : write(actor), payloadJson, payloadHash);
    jdbc.update("""
        insert into outbox_event(event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body,
          envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
        """, eventId, type.name(), id.toString(), version, eventType.value(), type.topic(), envelopeJson, envelopeHash, recorded, recorded);
    String snapshotJson = canonicalJson(write(rawSnapshot == null ? Map.of() : rawSnapshot));
    String snapshotHash = AssetChecksum.sha256(snapshotJson.getBytes(StandardCharsets.UTF_8));
    jdbc.update("insert into aggregate_snapshot(aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at) values (?, ?, ?, ?::jsonb, ?, ?)",
        type.name(), id.toString(), version, snapshotJson, snapshotHash, recorded);
    jdbc.update("""
        insert into projection_checkpoint(projection_name,aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at)
        values (?, ?, ?, ?, ?, ?)
        on conflict (projection_name,aggregate_type,aggregate_id) do update set aggregate_version=excluded.aggregate_version,
          projection_sha256=excluded.projection_sha256,updated_at=excluded.updated_at
        """, PROJECTION, type.name(), id.toString(), version, snapshotHash, recorded);
    registerInvalidation(eventId, type, id, version, eventType, rawPayload, rawSnapshot, recorded);
  }

  void registerInvalidation(
      UUID eventId,
      AssetAggregateType aggregateType,
      UUID aggregateId,
      long revision,
      AssetEventType eventType,
      Map<String, ?> payload,
      Map<String, ?> snapshot,
      OffsetDateTime recorded) {
    String scope = scope(aggregateType);
    if (scope == null || invalidations == null) return;
    Set<UUID> warehouses = new HashSet<>();
    collectWarehouseIds(payload, warehouses);
    collectWarehouseIds(snapshot, warehouses);
    if (eventType == AssetEventType.RENTAL_ITEM_WAREHOUSE_CHANGED && revision > 0) {
      jdbc.query(
              "select state ->> 'warehouseId' from aggregate_snapshot where aggregate_type=? and aggregate_id=? and aggregate_version=?",
              (resultSet, rowNumber) -> resultSet.getString(1),
              aggregateType.name(),
              aggregateId.toString(),
              revision - 1)
          .forEach(previousWarehouseId -> addWarehouse(previousWarehouseId, warehouses));
    }
    if (warehouses.isEmpty()) {
      if (aggregateType == AssetAggregateType.EQUIPMENT_CATALOG) {
        var event = new AssetInvalidationHub.AssetInvalidationEvent(
            eventId,
            null,
            scope,
            eventType.value(),
            aggregateType,
            aggregateId,
            revision,
            recorded.toInstant());
        afterCommit(() -> invalidations.publishGlobal(event));
      }
      return;
    }
    List<AssetInvalidationHub.AssetInvalidationEvent> events = warehouses.stream()
        .map(warehouseId -> new AssetInvalidationHub.AssetInvalidationEvent(
            eventId,
            warehouseId,
            scope,
            eventType.value(),
            aggregateType,
            aggregateId,
            revision,
            recorded.toInstant()))
        .toList();
    afterCommit(() -> events.forEach(invalidations::publish));
  }

  private void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          action.run();
        }
      });
    } else {
      action.run();
    }
  }

  private static String scope(AssetAggregateType aggregateType) {
    return switch (aggregateType) {
      case RENTAL_ITEM -> "RENTAL_ITEMS_CHANGED";
      case EQUIPMENT_CATALOG -> "EQUIPMENT_CATALOG_CHANGED";
      case EQUIPMENT_BALANCE, EQUIPMENT_MOVEMENT, EQUIPMENT_ALLOCATION_HOLD -> "EQUIPMENT_CHANGED";
      default -> null;
    };
  }

  private static void collectWarehouseIds(Object value, Set<UUID> result) {
    if (!(value instanceof Map<?, ?> map)) return;
    // Facts and snapshots carry warehouse scope at their top level. Do not
    // recurse into user-controlled passport/equipment JSON: a nested field
    // named warehouseId must never grant an unrelated warehouse an update
    // signal.
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
      if (key.equals("warehouseid")
          || key.equals("sourcewarehouseid")
          || key.equals("targetwarehouseid")
          || key.equals("originwarehouseid")
          || key.equals("destinationwarehouseid")) {
        addWarehouse(entry.getValue(), result);
      }
    }
  }

  private static void addWarehouse(Object value, Set<UUID> result) {
    if (value instanceof UUID uuid && !uuid.equals(UUID_NIL)) {
      result.add(uuid);
    } else if (value instanceof String text) {
      try {
        UUID uuid = UUID.fromString(text);
        if (!uuid.equals(UUID_NIL)) result.add(uuid);
      } catch (IllegalArgumentException ignored) {
        // A non-UUID warehouse field is not an access scope.
      }
    }
  }

  private static final UUID UUID_NIL = new UUID(0L, 0L);

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
  }
  private String canonicalJson(String json) {
    String value = jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
    if (value == null) throw new IllegalStateException("PostgreSQL did not canonicalize asset event JSON");
    return value;
  }
  private String write(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JacksonException exception) { throw new IllegalArgumentException("Asset event cannot be serialized", exception); }
  }

  public record StreamRef(AssetAggregateType type, UUID id) {
    public StreamRef { if (type == null || id == null) throw new IllegalArgumentException("Stream reference is required"); }
  }
}
