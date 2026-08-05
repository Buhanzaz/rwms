package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rebuilds the non-secret asset projection from ordered domain facts and
 * compares it with the live relational projection. It never rewrites a live
 * aggregate: the only durable output is the separately named shadow
 * checkpoint, so a corrupt stream cannot silently change production state.
 */
@Service
public class AssetReplayVerifier {
  public static final String SHADOW_PROJECTION = "asset-replay-shadow-v1";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AssetEventPayloadPolicy payloads;
  private final OperationLeaseRepository operationLeases;

  public AssetReplayVerifier(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      AssetEventPayloadPolicy payloads,
      OperationLeaseRepository operationLeases) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.payloads = payloads;
    this.operationLeases = operationLeases;
  }

  @Transactional
  public ReplayParityResult rebuildAndVerify() {
    Map<StreamKey, ReplayState> replayed = replay();
    verifyLiveProjection(replayed);
    replayed.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> checkpoint(entry.getKey(), entry.getValue()));
    String checksum = AssetChecksum.sha256(replayed.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey().type().name() + ':' + entry.getKey().id() + ':'
            + entry.getValue().version() + ':' + canonicalHash(entry.getValue().fact()))
        .reduce("", (left, right) -> left + '\n' + right)
        .getBytes(StandardCharsets.UTF_8));
    return new ReplayParityResult(replayed.size(), checksum);
  }

  private Map<StreamKey, ReplayState> replay() {
    Map<StreamKey, ReplayState> state = new LinkedHashMap<>();
    List<EventRow> events = jdbc.query("""
        select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload::text,payload_sha256
        from domain_event order by aggregate_type,aggregate_id,aggregate_version
        """, (rs, row) -> new EventRow(
        rs.getObject("event_id", UUID.class),
        AssetAggregateType.valueOf(rs.getString("aggregate_type")),
        UUID.fromString(rs.getString("aggregate_id")),
        rs.getLong("aggregate_version"),
        AssetEventType.require(rs.getString("event_type")),
        rs.getString("payload"),
        rs.getString("payload_sha256").trim()));
    for (EventRow event : events) {
      StreamKey key = new StreamKey(event.type(), event.aggregateId());
      ReplayState previous = state.get(key);
      long expected = previous == null ? 0 : Math.addExact(previous.version(), 1);
      if (event.aggregateVersion() != expected) {
        throw new IllegalStateException("Asset replay version gap for " + key + ": expected " + expected);
      }
      JsonNode payload = read(event.payload());
      payloads.validateNode(event.eventType().value(), event.type(), event.aggregateId(), payload);
      if (!canonicalHash(payload).equals(event.payloadSha256())) {
        throw new IllegalStateException("Asset replay checksum mismatch for " + key);
      }
      if (outboxCount(event.eventId()) != 1) {
        throw new IllegalStateException("Asset replay corruption: canonical outbox row is missing for " + event.eventId());
      }
      state.put(key, new ReplayState(event.aggregateVersion(), apply(previous == null ? null : previous.fact(), event), event.eventId()));
    }
    state.forEach(this::verifyHead);
    return Map.copyOf(state);
  }

  private JsonNode apply(JsonNode previous, EventRow event) {
    if (event.type() == AssetAggregateType.RENTAL_ITEM
        && (event.eventType() == AssetEventType.RENTAL_ITEM_GENERAL_COMMENT_CHANGED
        || event.eventType() == AssetEventType.RENTAL_ITEM_MANUAL_NOTE_ADDED)) {
      if (previous == null) throw new IllegalStateException("Asset replay rental stream lacks its creation fact");
      return previous;
    }
    return read(event.payload());
  }

  private void verifyHead(StreamKey key, ReplayState replayed) {
    StreamHead head = jdbc.query("""
        select current_version,last_event_id from event_stream_head
        where aggregate_type=? and aggregate_id=?
        """, (rs, row) -> new StreamHead(rs.getLong("current_version"), rs.getObject("last_event_id", UUID.class)),
        key.type().name(), key.id().toString()).stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("Asset replay stream head is missing for " + key));
    if (head.version() != replayed.version() || !head.eventId().equals(replayed.eventId())) {
      throw new IllegalStateException("Asset replay stream head does not match facts for " + key);
    }
  }

  private void verifyLiveProjection(Map<StreamKey, ReplayState> replayed) {
    Map<StreamKey, JsonNode> live = liveProjection();
    if (!live.keySet().equals(replayed.keySet())) {
      throw new IllegalStateException("Asset replay stream set does not match the live projection");
    }
    replayed.forEach((key, state) -> {
      JsonNode projected = live.get(key);
      if (!canonicalHash(state.fact()).equals(canonicalHash(projected))) {
        throw new IllegalStateException(
            "Asset replay parity mismatch for " + key
                + ": replayed=" + state.fact() + ", live=" + projected);
      }
    });
  }

  private Map<StreamKey, JsonNode> liveProjection() {
    Map<StreamKey, JsonNode> result = new LinkedHashMap<>();
    jdbc.query(
        """
        select id,warehouse_id,status,transfer_origin_status,
          display_canonical_number as number
        from rental_item
        """,
        rs -> {
      UUID id = rs.getObject("id", UUID.class);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("rentalItemId", id.toString());
      value.put(
          "warehouseId",
          rs.getObject("warehouse_id", UUID.class).toString());
      value.put("status", rs.getString("status"));
      value.put(
          "numberSha256",
          AssetChecksum.sha256(
              rs.getString("number").getBytes(StandardCharsets.UTF_8)));
      String transferOriginStatus = rs.getString("transfer_origin_status");
      if (transferOriginStatus != null) {
        value.put("transferAssetStatus", transferOriginStatus);
      }
      result.put(new StreamKey(AssetAggregateType.RENTAL_ITEM, id), node(value));
    });
    jdbc.query("select id,category,active from equipment_catalog_item", rs -> {
      UUID id = rs.getObject("id", UUID.class);
      result.put(new StreamKey(AssetAggregateType.EQUIPMENT_CATALOG, id), node(Map.of(
          "equipmentId", id.toString(), "category", rs.getString("category"), "active", rs.getBoolean("active"))));
    });
    jdbc.query("select id,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance", rs -> {
      UUID id = rs.getObject("id", UUID.class);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("balanceId", id.toString());
      value.put("equipmentId", rs.getObject("equipment_id", UUID.class).toString());
      value.put("warehouseId", rs.getObject("warehouse_id", UUID.class).toString());
      UUID rentalItemId = rs.getObject("rental_item_id", UUID.class);
      value.put("rentalItemId", rentalItemId == null ? null : rentalItemId.toString());
      value.put("locationKind", rs.getString("location_kind"));
      value.put("quantity", rs.getLong("quantity"));
      result.put(new StreamKey(AssetAggregateType.EQUIPMENT_BALANCE, id), node(value));
    });
    jdbc.query("""
        select movement.id,movement.equipment_id,movement.source_balance_id,
          movement.target_balance_id,movement.quantity,movement.movement_kind,
          context.equipment_category_snapshot,
          context.source_warehouse_id,context.source_rental_item_id,
          context.source_location_kind,context.target_warehouse_id,
          context.target_rental_item_id,context.target_location_kind,
          context.capture_origin
        from equipment_movement movement
        join equipment_movement_context context on context.movement_id=movement.id
        """, rs -> {
      UUID id = rs.getObject("id", UUID.class);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("movementId", id.toString());
      value.put("equipmentId", rs.getObject("equipment_id", UUID.class).toString());
      value.put("sourceBalanceId", rs.getObject("source_balance_id", UUID.class).toString());
      value.put("targetBalanceId", rs.getObject("target_balance_id", UUID.class).toString());
      value.put("quantity", rs.getLong("quantity"));
      value.put("movementKind", rs.getString("movement_kind"));
      if ("AT_MOVEMENT".equals(rs.getString("capture_origin"))) {
        value.put("equipmentCategory", rs.getString("equipment_category_snapshot"));
        value.put("sourceWarehouseId", rs.getObject("source_warehouse_id", UUID.class).toString());
        UUID sourceRentalItemId = rs.getObject("source_rental_item_id", UUID.class);
        value.put(
            "sourceRentalItemId",
            sourceRentalItemId == null ? null : sourceRentalItemId.toString());
        value.put("sourceLocationKind", rs.getString("source_location_kind"));
        value.put("targetWarehouseId", rs.getObject("target_warehouse_id", UUID.class).toString());
        UUID targetRentalItemId = rs.getObject("target_rental_item_id", UUID.class);
        value.put(
            "targetRentalItemId",
            targetRentalItemId == null ? null : targetRentalItemId.toString());
        value.put("targetLocationKind", rs.getString("target_location_kind"));
      }
      result.put(new StreamKey(AssetAggregateType.EQUIPMENT_MOVEMENT, id), node(value));
    });
    jdbc.query("select id,equipment_id,warehouse_id,quantity,state from equipment_allocation_hold", rs -> {
      UUID id = rs.getObject("id", UUID.class);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("holdId", id.toString());
      value.put("equipmentId", rs.getObject("equipment_id", UUID.class).toString());
      value.put("warehouseId", rs.getObject("warehouse_id", UUID.class).toString());
      value.put("quantity", rs.getLong("quantity"));
      value.put("state", rs.getString("state"));
      result.put(new StreamKey(AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD, id), node(value));
    });
    operationLeases.findAll().forEach(lease -> {
      UUID id = lease.getId();
      result.put(new StreamKey(AssetAggregateType.OPERATION_LEASE, id), node(Map.of(
          "leaseId", id.toString(), "rentalItemId", lease.getRentalItemId().toString(),
          "fencingToken", lease.getFencingToken(), "state", lease.getState().name())));
    });
    jdbc.query("select id,classifier_type,parent_id,name,active,sort_order from asset_classifier", rs -> {
      UUID id = rs.getObject("id", UUID.class);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("classifierId", id.toString());
      value.put("type", rs.getString("classifier_type"));
      UUID parentId = rs.getObject("parent_id", UUID.class);
      value.put("parentId", parentId == null ? null : parentId.toString());
      value.put("label", rs.getString("name"));
      value.put("active", rs.getBoolean("active"));
      value.put("sortOrder", rs.getObject("sort_order", Integer.class));
      result.put(new StreamKey(AssetAggregateType.CLASSIFIER, id), node(value));
    });
    return Map.copyOf(result);
  }

  private void checkpoint(StreamKey key, ReplayState state) {
    jdbc.update("""
        insert into projection_checkpoint(projection_name,aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at)
        values (?, ?, ?, ?, ?, clock_timestamp())
        on conflict (projection_name,aggregate_type,aggregate_id) do update
        set aggregate_version=excluded.aggregate_version,projection_sha256=excluded.projection_sha256,updated_at=excluded.updated_at
        """, SHADOW_PROJECTION, key.type().name(), key.id().toString(), state.version(), canonicalHash(state.fact()));
  }

  private int outboxCount(UUID eventId) {
    Integer count = jdbc.queryForObject("select count(*) from outbox_event where event_id=?", Integer.class, eventId);
    return count == null ? 0 : count;
  }

  private JsonNode node(Map<String, ?> value) { return mapper.valueToTree(value); }
  private JsonNode read(String value) {
    try { return mapper.readTree(value); }
    catch (JacksonException exception) { throw new IllegalStateException("Asset replay event is not JSON", exception); }
  }
  private String canonicalHash(JsonNode value) {
    try {
      String json = mapper.writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
      if (canonical == null) throw new IllegalStateException("PostgreSQL did not canonicalize replay JSON");
      return AssetChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Asset replay state cannot be serialized", exception);
    }
  }

  public record ReplayParityResult(int aggregateCount, String canonicalChecksum) {}
  private record StreamKey(AssetAggregateType type, UUID id) implements Comparable<StreamKey> {
    @Override public int compareTo(StreamKey other) {
      int typeOrder = type.name().compareTo(other.type.name());
      return typeOrder == 0 ? id.toString().compareTo(other.id.toString()) : typeOrder;
    }
  }
  private record ReplayState(long version, JsonNode fact, UUID eventId) {}
  private record EventRow(UUID eventId, AssetAggregateType type, UUID aggregateId, long aggregateVersion,
                          AssetEventType eventType, String payload, String payloadSha256) {}
  private record StreamHead(long version, UUID eventId) {}
}
