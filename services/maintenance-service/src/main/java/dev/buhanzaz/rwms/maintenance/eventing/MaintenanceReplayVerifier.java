package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Deterministically rebuilds shadow state exclusively from ordered domain_event facts. */
@Service
public class MaintenanceReplayVerifier {
  public static final String SHADOW_PROJECTION = "maintenance-replay-shadow-v1";
  private static final String LIVE_PROJECTION = "maintenance-live-v1";
  private static final Set<String> LOCAL_FACT_FIELDS = Set.of("model", "event", "state");
  private static final Set<String> ENVELOPE_FIELDS = Set.of(
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
  private static final Map<String, MaintenanceEventType> EVENT_TYPES =
      Arrays.stream(MaintenanceEventType.values())
          .collect(Collectors.toUnmodifiableMap(MaintenanceEventType::value, Function.identity()));

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceEventPayloadPolicy payloadPolicy;
  private final boolean includeStateInErrors;

  public MaintenanceReplayVerifier(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceEventPayloadPolicy payloadPolicy,
      @Value("${rwms.maintenance.replay.include-state-in-errors:false}")
      boolean includeStateInErrors) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.projectionSnapshots = projectionSnapshots;
    this.payloadPolicy = payloadPolicy;
    this.includeStateInErrors = includeStateInErrors;
  }

  @Transactional
  public ReplayResult rebuildAndVerify() {
    Replay replay = replayEvents();
    verifyStoreSets(replay);
    verifyLiveJpaProjection(replay.streams());
    replay.streams().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> writeShadowCheckpoint(entry.getKey(), entry.getValue()));
    String checksum = MaintenanceChecksum.sha256(
        replay.streams().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + ":" + entry.getValue().version() + ":"
                + entry.getValue().stateHash())
            .reduce("", (left, right) -> left + "\n" + right)
            .getBytes(StandardCharsets.UTF_8));
    return new ReplayResult(replay.streams().size(), checksum);
  }

  private Replay replayEvents() {
    Map<StreamKey, ReplayState> replayed = new LinkedHashMap<>();
    Counter counter = new Counter();
    jdbc.query("""
        select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,causation_id,actor_ref::text,payload::text,
          payload_sha256,baseline
        from domain_event order by aggregate_type,aggregate_id,aggregate_version,event_id
        """, resultSet -> {
      EventRow event = new EventRow(
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
          resultSet.getString("payload_sha256").trim(),
          resultSet.getBoolean("baseline"));
      counter.events++;
      if (!event.baseline()) {
        counter.integrationEvents++;
      }
      StreamKey key = new StreamKey(event.aggregateType(), event.aggregateId());
      ReplayState previous = replayed.get(key);
      long expected = previous == null ? 0 : Math.addExact(previous.version(), 1);
      if (event.aggregateVersion() != expected) {
        throw new IllegalStateException(
            "Maintenance replay version gap for " + key + ": expected " + expected);
      }
      requireEventFamily(event);
      JsonNode authoritativeFact = read(event.payload());
      if (!canonicalHash(authoritativeFact).equals(event.payloadHash())) {
        throw new IllegalStateException("Maintenance replay event checksum mismatch for " + key);
      }
      JsonNode state = requireAuthoritativeFact(event, authoritativeFact);
      verifySnapshot(event, state);
      verifyOutbox(event);
      replayed.put(
          key,
          new ReplayState(
              event.aggregateVersion(), state, event.eventId(), canonicalHash(state)));
    });
    return new Replay(Map.copyOf(replayed), counter.events, counter.integrationEvents);
  }

  private JsonNode requireAuthoritativeFact(EventRow event, JsonNode fact) {
    requireExactFields(fact, LOCAL_FACT_FIELDS, "maintenance local event");
    if (!MaintenanceEventStore.LOCAL_EVENT_MODEL.equals(fact.required("model").stringValue())
        || !fact.required("event").isObject()
        || !fact.required("state").isObject()) {
      throw new IllegalStateException("Maintenance event does not use the authoritative full-state model");
    }
    JsonNode state = fact.required("state");
    if (!event.aggregateId().toString().equals(state.required("id").stringValue())
        || !state.required("version").isIntegralNumber()
        || state.required("version").longValue() != event.aggregateVersion()
        || !state.required("warehouseId").isTextual()) {
      throw new IllegalStateException(
          "Maintenance event full-state identity mismatch for "
              + new StreamKey(event.aggregateType(), event.aggregateId()));
    }
    return state;
  }

  private void verifySnapshot(EventRow event, JsonNode state) {
    SnapshotRow snapshot = jdbc.query("""
        select aggregate_version,state::text,state_sha256 from aggregate_snapshot
        where aggregate_type=? and aggregate_id=? and aggregate_version=?
        """, (resultSet, row) -> new SnapshotRow(
        resultSet.getLong("aggregate_version"),
        resultSet.getString("state"),
        resultSet.getString("state_sha256").trim()),
        event.aggregateType().name(), event.aggregateId().toString(), event.aggregateVersion())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Maintenance aggregate snapshot is missing for " + event.identity()));
    JsonNode snapshotState = read(snapshot.state());
    String snapshotHash = canonicalHash(snapshotState);
    if (snapshot.version() != event.aggregateVersion()
        || !snapshotHash.equals(snapshot.hash())
        || !canonicalJson(snapshotState).equals(canonicalJson(state))) {
      throw new IllegalStateException(
          "Maintenance aggregate snapshot parity mismatch for " + event.identity());
    }
  }

  private void verifyOutbox(EventRow event) {
    var rows = jdbc.query("""
        select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
          envelope_body::text,envelope_sha256
        from outbox_event where event_id=?
        """, (resultSet, row) -> new OutboxRow(
        resultSet.getObject("event_id", UUID.class),
        resultSet.getString("aggregate_type"),
        resultSet.getString("aggregate_id"),
        resultSet.getLong("aggregate_version"),
        resultSet.getString("event_type"),
        resultSet.getString("topic"),
        resultSet.getString("envelope_body"),
        resultSet.getString("envelope_sha256").trim()),
        event.eventId());
    if (event.baseline()) {
      if (!rows.isEmpty()) {
        throw new IllegalStateException(
            "Maintenance baseline event must not have an outbox row: " + event.eventId());
      }
      return;
    }
    if (rows.size() != 1) {
      throw new IllegalStateException(
          "Maintenance integration event must have exactly one outbox row: " + event.eventId());
    }
    OutboxRow row = rows.getFirst();
    if (!row.eventId().equals(event.eventId())
        || !row.aggregateType().equals(event.aggregateType().name())
        || !row.aggregateId().equals(event.aggregateId().toString())
        || row.aggregateVersion() != event.aggregateVersion()
        || !row.eventType().equals(event.eventType().value())
        || !row.topic().equals(event.aggregateType().topic())) {
      throw new IllegalStateException(
          "Maintenance outbox identity mismatch for " + event.eventId());
    }
    JsonNode envelope = read(row.envelope());
    requireExactFields(envelope, ENVELOPE_FIELDS, "maintenance outbox envelope");
    if (!canonicalHash(envelope).equals(row.envelopeHash())) {
      throw new IllegalStateException(
          "Maintenance outbox envelope checksum mismatch for " + event.eventId());
    }
    verifyEnvelopeIdentity(event, envelope);
    payloadPolicy.validateNode(
        event.eventType().value(),
        event.aggregateType(),
        event.aggregateId(),
        envelope.required("payload"));
  }

  private void verifyEnvelopeIdentity(EventRow event, JsonNode envelope) {
    JsonNode correlation = envelope.required("correlation");
    JsonNode actor = envelope.get("actorRef");
    boolean actorMatches = event.actorJson() == null
        ? actor == null || actor.isNull()
        : actor != null && read(event.actorJson()).equals(actor);
    boolean causationMatches = event.causationId() == null
        ? correlation.required("causationId").isNull()
        : event.causationId().toString().equals(
            correlation.required("causationId").stringValue());
    boolean occurredMatches = event.baseline()
        ? envelope.required("occurredAt").isNull()
        : event.occurredAt() != null
            && event.occurredAt().toInstant().equals(
                Instant.parse(envelope.required("occurredAt").stringValue()));
    if (envelope.required("envelopeVersion").intValue() != 2
        || !event.eventId().toString().equals(envelope.required("eventId").stringValue())
        || !event.eventType().value().equals(envelope.required("eventType").stringValue())
        || envelope.required("eventVersion").intValue() != event.eventVersion()
        || !occurredMatches
        || !event.recordedAt().toInstant().equals(
            Instant.parse(envelope.required("recordedAt").stringValue()))
        || !"maintenance-service".equals(envelope.required("producer").stringValue())
        || !event.aggregateType().name().equals(envelope.required("aggregateType").stringValue())
        || !event.aggregateId().toString().equals(envelope.required("aggregateId").stringValue())
        || envelope.required("aggregateVersion").longValue() != event.aggregateVersion()
        || !event.correlationId().toString().equals(
            correlation.required("correlationId").stringValue())
        || !causationMatches
        || !actorMatches) {
      throw new IllegalStateException(
          "Maintenance outbox envelope identity mismatch for " + event.eventId());
    }
  }

  private void verifyStoreSets(Replay replay) {
    Map<StreamKey, Head> heads = new LinkedHashMap<>();
    jdbc.query("select aggregate_type,aggregate_id,current_version,last_event_id from event_stream_head",
        resultSet -> {
          StreamKey key = new StreamKey(
              aggregateType(resultSet.getString("aggregate_type")),
              UUID.fromString(resultSet.getString("aggregate_id")));
          heads.put(key, new Head(
              resultSet.getLong("current_version"),
              resultSet.getObject("last_event_id", UUID.class)));
        });
    if (!heads.keySet().equals(replay.streams().keySet())) {
      throw new IllegalStateException(
          "Maintenance stream-head set differs from the authoritative event-stream set");
    }
    replay.streams().forEach((key, state) -> {
      Head head = heads.get(key);
      if (head.version() != state.version() || !head.eventId().equals(state.eventId())) {
        throw new IllegalStateException("Maintenance stream head differs from replay for " + key);
      }
      verifyLiveCheckpoint(key, state);
    });
    if (count("aggregate_snapshot") != replay.eventCount()) {
      throw new IllegalStateException(
          "Maintenance aggregate-snapshot set differs from the authoritative event set");
    }
    if (count("outbox_event") != replay.integrationEventCount()) {
      throw new IllegalStateException(
          "Maintenance outbox set differs from the non-baseline integration event set");
    }
  }

  private void verifyLiveCheckpoint(StreamKey key, ReplayState state) {
    Checkpoint checkpoint = jdbc.query("""
        select aggregate_version,projection_sha256 from projection_checkpoint
        where projection_name=? and aggregate_type=? and aggregate_id=?
        """, (resultSet, row) -> new Checkpoint(
        resultSet.getLong("aggregate_version"),
        resultSet.getString("projection_sha256").trim()),
        LIVE_PROJECTION, key.type().name(), key.id().toString())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Maintenance live projection checkpoint is missing for " + key));
    if (checkpoint.version() != state.version()
        || !checkpoint.hash().equals(state.stateHash())) {
      throw new IllegalStateException("Maintenance live projection parity mismatch for " + key);
    }
  }

  private void verifyLiveJpaProjection(Map<StreamKey, ReplayState> replayed) {
    Map<StreamKey, JsonNode> live = new LinkedHashMap<>();
    projectionSnapshots.allSnapshots().forEach((key, value) -> live.put(
        new StreamKey(key.type(), key.id()), mapper.valueToTree(value)));
    if (!live.keySet().equals(replayed.keySet())) {
      Set<StreamKey> liveWithoutStream = new HashSet<>(live.keySet());
      liveWithoutStream.removeAll(replayed.keySet());
      Set<StreamKey> streamWithoutLive = new HashSet<>(replayed.keySet());
      streamWithoutLive.removeAll(live.keySet());
      throw new IllegalStateException(
          "Maintenance live JPA aggregate set differs from event streams: liveWithoutStream="
              + liveWithoutStream + ", streamWithoutLive=" + streamWithoutLive);
    }
    replayed.forEach((key, state) -> {
      JsonNode projected = live.get(key);
      String liveHash = canonicalHash(projected);
      if (!state.stateHash().equals(liveHash)) {
        throw new IllegalStateException(
            "Maintenance JPA projection parity mismatch for " + key
                + ": replayHash=" + state.stateHash() + ", liveHash=" + liveHash
                + (includeStateInErrors
                    ? ", replay=" + canonicalJson(state.state())
                        + ", live=" + canonicalJson(projected)
                    : ""));
      }
    });
  }

  private void writeShadowCheckpoint(StreamKey key, ReplayState state) {
    jdbc.update("""
        insert into projection_checkpoint(projection_name,aggregate_type,aggregate_id,
          aggregate_version,projection_sha256,updated_at)
        values (?, ?, ?, ?, ?, clock_timestamp())
        on conflict (projection_name,aggregate_type,aggregate_id) do update
        set aggregate_version=excluded.aggregate_version,
          projection_sha256=excluded.projection_sha256,updated_at=excluded.updated_at
        """, SHADOW_PROJECTION, key.type().name(), key.id().toString(),
        state.version(), state.stateHash());
  }

  private void requireEventFamily(EventRow event) {
    if (event.eventVersion() != 1
        || payloadPolicy.aggregateFor(event.eventType()) != event.aggregateType()) {
      throw new IllegalStateException(
          "Maintenance event type does not match its aggregate family for " + event.identity());
    }
  }

  private static MaintenanceAggregateType aggregateType(String value) {
    try {
      return MaintenanceAggregateType.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Unknown maintenance aggregate type " + value, exception);
    }
  }

  private static MaintenanceEventType eventType(String value) {
    MaintenanceEventType result = EVENT_TYPES.get(value);
    if (result == null) {
      throw new IllegalStateException("Unknown maintenance event type " + value);
    }
    return result;
  }

  private static void requireExactFields(JsonNode value, Set<String> expected, String description) {
    if (value == null || !value.isObject()) {
      throw new IllegalStateException(description + " must be an object");
    }
    Set<String> actual = new HashSet<>();
    value.properties().forEach(entry -> actual.add(entry.getKey()));
    if (!actual.equals(expected)) {
      throw new IllegalStateException(description + " has a non-canonical shape");
    }
  }

  private int count(String table) {
    Integer value = jdbc.queryForObject("select count(*) from " + table, Integer.class);
    return value == null ? 0 : value;
  }

  private JsonNode read(String json) {
    try {
      return mapper.readTree(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Maintenance replay fact is not JSON", exception);
    }
  }

  private String canonicalHash(JsonNode value) {
    return MaintenanceChecksum.sha256(canonicalJson(value).getBytes(StandardCharsets.UTF_8));
  }

  private String canonicalJson(JsonNode value) {
    try {
      String canonical = jdbc.queryForObject(
          "select (?::jsonb)::text", String.class, mapper.writeValueAsString(value));
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize replay JSON");
      }
      return canonical;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Maintenance replay state is not serializable", exception);
    }
  }

  private record StreamKey(MaintenanceAggregateType type, UUID id)
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

  private record ReplayState(long version, JsonNode state, UUID eventId, String stateHash) {}

  private record Replay(
      Map<StreamKey, ReplayState> streams,
      int eventCount,
      int integrationEventCount) {}

  private record EventRow(
      UUID eventId,
      MaintenanceAggregateType aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      MaintenanceEventType eventType,
      int eventVersion,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      UUID correlationId,
      UUID causationId,
      String actorJson,
      String payload,
      String payloadHash,
      boolean baseline) {
    String identity() {
      return aggregateType + ":" + aggregateId + "@" + aggregateVersion;
    }
  }

  private record SnapshotRow(long version, String state, String hash) {}

  private record OutboxRow(
      UUID eventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      String topic,
      String envelope,
      String envelopeHash) {}

  private record Head(long version, UUID eventId) {}

  private record Checkpoint(long version, String hash) {}

  private static final class Counter {
    private int events;
    private int integrationEvents;
  }

  public record ReplayResult(int streamCount, String checksum) {}
}
