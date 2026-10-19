package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministically rebuilds the sanitized logistics state from the local
 * authoritative event store and verifies it against snapshots and the live JPA
 * projection. Live domain rows are never rewritten.
 */
@Service
public class LogisticsReplayVerifier {
  public static final String SHADOW_PROJECTION = "logistics-replay-shadow-v1";
  private static final String LIVE_PROJECTION = "logistics-live-v1";
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of(
          "documentId",
          "documentType",
          "state",
          "warehouseId",
          "destinationWarehouseId",
          "lineCount",
          "resultCode");
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "envelopeVersion",
          "eventId",
          "eventType",
          "eventVersion",
          "occurredAt",
          "recordedAt",
          "producer",
          "aggregateType",
          "aggregateId",
          "aggregateVersion",
          "correlation",
          "actorRef",
          "payload");
  private static final Map<String, LogisticsEventType> EVENT_TYPES =
      Arrays.stream(LogisticsEventType.values())
          .collect(
              Collectors.toUnmodifiableMap(LogisticsEventType::value, Function.identity()));

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public LogisticsReplayVerifier(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public ReplayParityResult rebuildAndVerify() {
    Replay replay = replayEvents();
    verifyStoreSets(replay);
    verifyLiveProjection(replay.streams());
    replay.streams().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> writeShadowCheckpoint(entry.getKey(), entry.getValue()));
    String checksum =
        LogisticsEventStore.sha256(
            replay.streams().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(
                    entry ->
                        entry.getKey()
                            + ":"
                            + entry.getValue().version()
                            + ":"
                            + entry.getValue().payloadHash())
                .reduce("", (left, right) -> left + "\n" + right)
                .getBytes(StandardCharsets.UTF_8));
    return new ReplayParityResult(replay.streams().size(), replay.eventCount(), checksum);
  }

  private Replay replayEvents() {
    Map<StreamKey, ReplayState> streams = new LinkedHashMap<>();
    Counter counter = new Counter();
    jdbc.query(
        """
        select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,causation_id,actor_ref::text,
          payload::text,payload_sha256
        from domain_event order by aggregate_type,aggregate_id,aggregate_version,event_id
        """,
        resultSet -> {
          EventRow event =
              new EventRow(
                  resultSet.getObject("event_id", UUID.class),
                  aggregateType(resultSet.getString("aggregate_type")),
                  UUID.fromString(resultSet.getString("aggregate_id")),
                  resultSet.getLong("aggregate_version"),
                  eventType(resultSet.getString("event_type")),
                  resultSet.getInt("event_version"),
                  resultSet.getObject("occurred_at", OffsetDateTime.class),
                  resultSet.getObject("recorded_at", OffsetDateTime.class),
                  resultSet.getObject("correlation_id", UUID.class),
                  resultSet.getObject("causation_id", UUID.class),
                  resultSet.getString("actor_ref"),
                  resultSet.getString("payload"),
                  resultSet.getString("payload_sha256").trim());
          counter.events++;
          StreamKey key = new StreamKey(event.aggregateType(), event.aggregateId());
          ReplayState previous = streams.get(key);
          long expected = previous == null ? 0 : Math.addExact(previous.version(), 1);
          if (event.aggregateVersion() != expected) {
            throw new IllegalStateException(
                "Logistics replay version gap for " + key + ": expected " + expected);
          }
          JsonNode payload = read(event.payload());
          validatePayload(event, payload);
          String payloadHash = canonicalHash(payload);
          if (!payloadHash.equals(event.payloadHash())) {
            throw new IllegalStateException("Logistics replay event checksum mismatch for " + key);
          }
          verifySnapshot(event, payload, payloadHash);
          verifyOutbox(event, payload);
          streams.put(
              key,
              new ReplayState(
                  event.aggregateVersion(), payload, payloadHash, event.eventId()));
        });
    return new Replay(Map.copyOf(streams), counter.events);
  }

  private void validatePayload(EventRow event, JsonNode payload) {
    requireExactFields(payload, PAYLOAD_FIELDS, "logistics event payload");
    UUID documentId = requiredUuid(payload.get("documentId"), "documentId");
    UUID warehouseId = requiredUuid(payload.get("warehouseId"), "warehouseId");
    JsonNode destination = payload.get("destinationWarehouseId");
    JsonNode resultCode = payload.get("resultCode");
    if (event.eventVersion() != 1
        || event.eventType().aggregateType() != event.aggregateType()
        || !event.aggregateId().equals(documentId)
        || !event.aggregateType().name().equals(requiredText(payload, "documentType"))
        || !event.eventType().expectedState().equals(requiredText(payload, "state"))
        || !payload.required("lineCount").isInt()
        || payload.required("lineCount").intValue() < 1
        || payload.required("lineCount").intValue() > 100
        || (event.aggregateType() == LogisticsAggregateType.TRANSFER
            && (requiredUuid(destination, "destinationWarehouseId").equals(warehouseId)))
        || (event.aggregateType() != LogisticsAggregateType.TRANSFER
            && (destination == null || !destination.isNull()))
        || resultCode == null
        || (!resultCode.isNull()
            && (!resultCode.isTextual()
                || !resultCode.textValue().matches("[A-Z][A-Z0-9_]{0,63}")))) {
      throw new IllegalStateException(
          "Logistics replay payload identity mismatch for " + event.identity());
    }
  }

  private void verifySnapshot(EventRow event, JsonNode payload, String payloadHash) {
    SnapshotRow snapshot =
        jdbc.query(
                """
                select state::text,state_sha256 from aggregate_snapshot
                where aggregate_type=? and aggregate_id=? and aggregate_version=?
                """,
                (resultSet, row) ->
                    new SnapshotRow(
                        resultSet.getString("state"),
                        resultSet.getString("state_sha256").trim()),
                event.aggregateType().name(),
                event.aggregateId().toString(),
                event.aggregateVersion())
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Logistics aggregate snapshot is missing for " + event.identity()));
    JsonNode snapshotState = read(snapshot.state());
    if (!payloadHash.equals(snapshot.hash())
        || !payloadHash.equals(canonicalHash(snapshotState))
        || !canonicalJson(payload).equals(canonicalJson(snapshotState))) {
      throw new IllegalStateException(
          "Logistics aggregate snapshot parity mismatch for " + event.identity());
    }
  }

  private void verifyOutbox(EventRow event, JsonNode payload) {
    OutboxRow row =
        jdbc.query(
                """
                select aggregate_type,aggregate_id,aggregate_version,event_type,topic,
                  envelope_body::text,envelope_sha256
                from outbox_event where event_id=?
                """,
                (resultSet, index) ->
                    new OutboxRow(
                        resultSet.getString("aggregate_type"),
                        resultSet.getString("aggregate_id"),
                        resultSet.getLong("aggregate_version"),
                        resultSet.getString("event_type"),
                        resultSet.getString("topic"),
                        resultSet.getString("envelope_body"),
                        resultSet.getString("envelope_sha256").trim()),
                event.eventId())
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Logistics outbox row is missing for " + event.eventId()));
    JsonNode envelope = read(row.envelope());
    requireExactFields(envelope, ENVELOPE_FIELDS, "logistics outbox envelope");
    JsonNode correlation = envelope.get("correlation");
    requireExactFields(
        correlation, Set.of("correlationId", "causationId"), "logistics outbox correlation");
    UUID correlationId = requiredUuid(correlation.get("correlationId"), "correlationId");
    UUID causationId = nullableUuid(correlation.get("causationId"));
    new CorrelationContext(correlationId, causationId);
    JsonNode actorRef = envelope.get("actorRef");
    validateActorRef(actorRef);
    JsonNode storedActorRef = event.actorRef() == null ? null : read(event.actorRef());
    validateActorRef(storedActorRef);
    if (!row.aggregateType().equals(event.aggregateType().name())
        || !row.aggregateId().equals(event.aggregateId().toString())
        || row.aggregateVersion() != event.aggregateVersion()
        || !row.eventType().equals(event.eventType().value())
        || !row.topic().equals(event.aggregateType().topic())
        || !row.envelopeHash().equals(canonicalHash(envelope))
        || envelope.required("envelopeVersion").intValue() != 2
        || !event.eventId().toString().equals(envelope.required("eventId").stringValue())
        || !event.eventType().value().equals(envelope.required("eventType").stringValue())
        || envelope.required("eventVersion").intValue() != 1
        || !"logistics-service".equals(envelope.required("producer").stringValue())
        || !event.aggregateType().name().equals(envelope.required("aggregateType").stringValue())
        || !event.aggregateId().toString().equals(envelope.required("aggregateId").stringValue())
        || envelope.required("aggregateVersion").longValue() != event.aggregateVersion()
        || !java.util.Objects.equals(
            nullableInstant(envelope.get("occurredAt")), instant(event.occurredAt()))
        || !requiredInstant(envelope.get("recordedAt"), "recordedAt")
            .equals(instant(event.recordedAt()))
        || !event.correlationId().equals(correlationId)
        || !java.util.Objects.equals(event.causationId(), causationId)
        || !sameJson(storedActorRef, actorRef)
        || !canonicalJson(payload).equals(canonicalJson(envelope.required("payload")))) {
      throw new IllegalStateException(
          "Logistics outbox parity mismatch for " + event.eventId());
    }
  }

  private void verifyStoreSets(Replay replay) {
    Map<StreamKey, Head> heads = new LinkedHashMap<>();
    jdbc.query(
        "select aggregate_type,aggregate_id,current_version,last_event_id from event_stream_head",
        resultSet -> {
          StreamKey key =
              new StreamKey(
                  aggregateType(resultSet.getString("aggregate_type")),
                  UUID.fromString(resultSet.getString("aggregate_id")));
          heads.put(
              key,
              new Head(
                  resultSet.getLong("current_version"),
                  resultSet.getObject("last_event_id", UUID.class)));
        });
    if (!heads.keySet().equals(replay.streams().keySet())) {
      throw new IllegalStateException(
          "Logistics stream-head set differs from the authoritative event-stream set");
    }
    replay.streams().forEach(
        (key, state) -> {
          Head head = heads.get(key);
          if (head.version() != state.version() || !head.eventId().equals(state.eventId())) {
            throw new IllegalStateException(
                "Logistics stream head differs from replay for " + key);
          }
          verifyLiveCheckpoint(key, state);
        });
    if (count("aggregate_snapshot") != replay.eventCount()
        || count("outbox_event") != replay.eventCount()) {
      throw new IllegalStateException(
          "Logistics snapshot/outbox sets differ from the authoritative event set");
    }
  }

  private void verifyLiveCheckpoint(StreamKey key, ReplayState state) {
    Checkpoint checkpoint =
        jdbc.query(
                """
                select aggregate_version,projection_sha256 from projection_checkpoint
                where projection_name=? and aggregate_type=? and aggregate_id=?
                """,
                (resultSet, row) ->
                    new Checkpoint(
                        resultSet.getLong("aggregate_version"),
                        resultSet.getString("projection_sha256").trim()),
                LIVE_PROJECTION,
                key.type().name(),
                key.id().toString())
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Logistics live projection checkpoint is missing for " + key));
    if (checkpoint.version() != state.version()
        || !checkpoint.hash().equals(state.payloadHash())) {
      throw new IllegalStateException(
          "Logistics live projection checkpoint differs from replay for " + key);
    }
  }

  private void verifyLiveProjection(Map<StreamKey, ReplayState> replayed) {
    Map<StreamKey, LiveDocument> live = new LinkedHashMap<>();
    jdbc.query(
        """
        select document.id,document.version,document.document_type,document.state,
          document.warehouse_id,document.destination_warehouse_id,
          (select count(*) from logistics_document_line line where line.document_id=document.id) line_count
        from logistics_document document
        """,
        resultSet -> {
          UUID id = resultSet.getObject("id", UUID.class);
          live.put(
              new StreamKey(
                  aggregateType(resultSet.getString("document_type")), id),
              new LiveDocument(
                  resultSet.getLong("version"),
                  resultSet.getString("state"),
                  resultSet.getObject("warehouse_id", UUID.class),
                  resultSet.getObject("destination_warehouse_id", UUID.class),
                  resultSet.getInt("line_count")));
        });
    if (!live.keySet().equals(replayed.keySet())) {
      Set<StreamKey> liveWithoutStream = new HashSet<>(live.keySet());
      liveWithoutStream.removeAll(replayed.keySet());
      Set<StreamKey> streamWithoutLive = new HashSet<>(replayed.keySet());
      streamWithoutLive.removeAll(live.keySet());
      throw new IllegalStateException(
          "Logistics live JPA aggregate set differs from event streams: liveWithoutStream="
              + liveWithoutStream
              + ", streamWithoutLive="
              + streamWithoutLive);
    }
    replayed.forEach(
        (key, state) -> {
          JsonNode payload = state.payload();
          LiveDocument document = live.get(key);
          UUID destination = nullableUuid(payload.get("destinationWarehouseId"));
          if (document.version() != state.version()
              || !document.state().equals(payload.required("state").stringValue())
              || !document.warehouseId().equals(
                  UUID.fromString(payload.required("warehouseId").stringValue()))
              || !java.util.Objects.equals(document.destinationWarehouseId(), destination)
              || document.lineCount() != payload.required("lineCount").intValue()) {
            throw new IllegalStateException(
                "Logistics live JPA projection parity mismatch for " + key);
          }
        });
  }

  private void writeShadowCheckpoint(StreamKey key, ReplayState state) {
    jdbc.update(
        """
        insert into projection_checkpoint(projection_name,aggregate_type,aggregate_id,
          aggregate_version,projection_sha256,updated_at)
        values (?, ?, ?, ?, ?, clock_timestamp())
        on conflict (projection_name,aggregate_type,aggregate_id) do update
        set aggregate_version=excluded.aggregate_version,
          projection_sha256=excluded.projection_sha256,updated_at=excluded.updated_at
        """,
        SHADOW_PROJECTION,
        key.type().name(),
        key.id().toString(),
        state.version(),
        state.payloadHash());
  }

  private int count(String table) {
    Integer value = jdbc.queryForObject("select count(*) from " + table, Integer.class);
    return value == null ? 0 : value;
  }

  private JsonNode read(String json) {
    try {
      return mapper.readTree(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Logistics replay value is not JSON", exception);
    }
  }

  private String canonicalHash(JsonNode value) {
    return LogisticsEventStore.sha256(canonicalJson(value).getBytes(StandardCharsets.UTF_8));
  }

  private String canonicalJson(JsonNode value) {
    try {
      String canonical =
          jdbc.queryForObject(
              "select (?::jsonb)::text", String.class, mapper.writeValueAsString(value));
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize logistics replay JSON");
      }
      return canonical;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Logistics replay state is not serializable", exception);
    }
  }

  private static void requireExactFields(
      JsonNode value, Set<String> expected, String description) {
    if (value == null || !value.isObject()) {
      throw new IllegalStateException(description + " must be an object");
    }
    Set<String> actual = new HashSet<>(value.propertyNames());
    if (!actual.equals(expected)) {
      throw new IllegalStateException(description + " has a non-canonical shape");
    }
  }

  private static LogisticsAggregateType aggregateType(String value) {
    try {
      return LogisticsAggregateType.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Unknown logistics aggregate type " + value, exception);
    }
  }

  private static LogisticsEventType eventType(String value) {
    LogisticsEventType type = EVENT_TYPES.get(value);
    if (type == null) {
      throw new IllegalStateException("Unknown logistics event type " + value);
    }
    return type;
  }

  private static UUID nullableUuid(JsonNode value) {
    return value == null || value.isNull() ? null : requiredUuid(value, "UUID value");
  }

  private static UUID requiredUuid(JsonNode value, String field) {
    if (value == null || !value.isTextual()) {
      throw new IllegalStateException(field + " must be a UUID");
    }
    try {
      return UUID.fromString(value.textValue());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(field + " must be a UUID", exception);
    }
  }

  private static String requiredText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalStateException(field + " must be nonblank text");
    }
    return value.textValue();
  }

  private static void validateActorRef(JsonNode actorRef) {
    if (actorRef == null || actorRef.isNull()) return;
    requireExactFields(
        actorRef,
        Set.of("subjectId", "principalType", "profileRevision"),
        "logistics outbox actorRef");
    JsonNode profileRevision = actorRef.get("profileRevision");
    try {
      new OpaqueActorReference(
          requiredText(actorRef, "subjectId"),
          requiredText(actorRef, "principalType"),
          profileRevision == null || profileRevision.isNull()
              ? null
              : requiredText(actorRef, "profileRevision"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("logistics outbox actorRef is unsafe", exception);
    }
  }

  private boolean sameJson(JsonNode left, JsonNode right) {
    if (left == null || left.isNull()) return right == null || right.isNull();
    return right != null && !right.isNull() && canonicalJson(left).equals(canonicalJson(right));
  }

  private static Instant nullableInstant(JsonNode value) {
    return value == null || value.isNull() ? null : requiredInstant(value, "occurredAt");
  }

  private static Instant requiredInstant(JsonNode value, String field) {
    if (value == null || !value.isTextual()) {
      throw new IllegalStateException(field + " must be an instant");
    }
    try {
      return Instant.parse(value.textValue());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(field + " must be an instant", exception);
    }
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  public record ReplayParityResult(
      int aggregateCount, int eventCount, String canonicalChecksum) {}

  private record StreamKey(LogisticsAggregateType type, UUID id)
      implements Comparable<StreamKey> {
    @Override
    public int compareTo(StreamKey other) {
      int byType = type.name().compareTo(other.type.name());
      return byType == 0 ? id.toString().compareTo(other.id.toString()) : byType;
    }

    @Override
    public String toString() {
      return type.name() + ":" + id;
    }
  }

  private record ReplayState(
      long version, JsonNode payload, String payloadHash, UUID eventId) {}

  private record Replay(Map<StreamKey, ReplayState> streams, int eventCount) {}

  private record EventRow(
      UUID eventId,
      LogisticsAggregateType aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      LogisticsEventType eventType,
      int eventVersion,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      UUID correlationId,
      UUID causationId,
      String actorRef,
      String payload,
      String payloadHash) {
    private String identity() {
      return aggregateType + ":" + aggregateId + ":" + aggregateVersion;
    }
  }

  private record SnapshotRow(String state, String hash) {}

  private record OutboxRow(
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      String topic,
      String envelope,
      String envelopeHash) {}

  private record Head(long version, UUID eventId) {}

  private record Checkpoint(long version, String hash) {}

  private record LiveDocument(
      long version,
      String state,
      UUID warehouseId,
      UUID destinationWarehouseId,
      int lineCount) {}

  private static final class Counter {
    private int events;
  }
}
