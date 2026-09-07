package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict, sanitized facts only: local comments, manual note text, tenant and media data stay out. */
@Component
public class AssetEventPayloadPolicy {
  private static final Set<String> FORBIDDEN = Set.of(
      "comment", "generalcomment", "notetext", "note", "text", "tenant", "media", "url", "photo",
      "name", "displayname", "email", "login", "password", "secret", "token", "reason", "actor",
      "code", "equipmentcode", "classifiercode");
  private static final Set<String> MOVEMENT_CONTEXT_FIELDS = Set.of(
      "equipmentCategory",
      "sourceWarehouseId",
      "sourceRentalItemId",
      "sourceLocationKind",
      "targetWarehouseId",
      "targetRentalItemId",
      "targetLocationKind");
  private static final Set<String> LOCATION_KINDS = Set.of(
      "STOCK", "CABIN_NON_RENTED", "CABIN_RENTED", "WRITTEN_OFF", "LOST");
  private static final Map<AssetAggregateType, String> ID_FIELDS = Map.of(
      AssetAggregateType.RENTAL_ITEM, "rentalItemId",
      AssetAggregateType.EQUIPMENT_CATALOG, "equipmentId",
      AssetAggregateType.EQUIPMENT_BALANCE, "balanceId",
      AssetAggregateType.EQUIPMENT_MOVEMENT, "movementId",
      AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD, "holdId",
      AssetAggregateType.OPERATION_LEASE, "leaseId",
      AssetAggregateType.CLASSIFIER, "classifierId");
  private final ObjectMapper mapper;

  public AssetEventPayloadPolicy(ObjectMapper mapper) { this.mapper = mapper; }

  public JsonNode validateAndConvert(AssetEventType type, AssetAggregateType aggregateType, UUID aggregateId, Map<String, ?> payload) {
    if (payload == null) throw new IllegalArgumentException("Asset event payload is required");
    JsonNode node = mapper.valueToTree(payload);
    validateNode(type.value(), aggregateType, aggregateId, node);
    return node;
  }

  public void validateNode(String eventType, AssetAggregateType aggregateType, UUID aggregateId, JsonNode payload) {
    AssetEventType type = AssetEventType.require(eventType);
    if (!matches(type, aggregateType)) throw new IllegalArgumentException("Asset event type does not match aggregate family");
    if (payload == null || !payload.isObject()) throw new IllegalArgumentException("Asset event payload must be an object");
    String idField = ID_FIELDS.get(aggregateType);
    JsonNode id = payload.get(idField);
    if (id == null || !id.isTextual() || !aggregateId.toString().equals(id.stringValue())) {
      throw new IllegalArgumentException("Asset event payload aggregate identity mismatch");
    }
    validateSafety(payload);
    if (type == AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED) {
      Set<String> fields = Set.of(
          "rentalItemId", "warehouseId", "status", "numberSha256", "inventoryId", "isolated");
      if (payload.size() != fields.size() || !fields.stream().allMatch(payload::has)) {
        throw new IllegalArgumentException("Inventory visibility fact must have exactly six fields");
      }
      requireUuid(payload, "warehouseId", false);
      requireUuid(payload, "inventoryId", false);
      if (!payload.get("isolated").isBoolean()
          || !payload.get("status").isTextual()
          || !payload.get("numberSha256").isTextual()
          || !payload.get("numberSha256").stringValue().matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Invalid inventory visibility fact");
      }
    }
    if (aggregateType == AssetAggregateType.EQUIPMENT_MOVEMENT) {
      validateMovementContext(payload);
    }
  }

  private static void validateMovementContext(JsonNode payload) {
    long present = MOVEMENT_CONTEXT_FIELDS.stream().filter(payload::has).count();
    if (present == 0) {
      // Facts committed before V30 remain valid and replayable. New producers
      // always emit the complete exact-at-movement context below.
      return;
    }
    if (present != MOVEMENT_CONTEXT_FIELDS.size()) {
      throw new IllegalArgumentException(
          "Equipment movement context must be absent or complete");
    }
    requireEnum(payload, "equipmentCategory", Set.of("FURNITURE", "ELECTRICAL", "OTHER"));
    requireUuid(payload, "sourceWarehouseId", false);
    requireUuid(payload, "sourceRentalItemId", true);
    requireEnum(payload, "sourceLocationKind", LOCATION_KINDS);
    requireUuid(payload, "targetWarehouseId", false);
    requireUuid(payload, "targetRentalItemId", true);
    requireEnum(payload, "targetLocationKind", LOCATION_KINDS);
  }

  private static void requireEnum(JsonNode payload, String field, Set<String> allowed) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual() || !allowed.contains(value.stringValue())) {
      throw new IllegalArgumentException("Asset event payload has invalid " + field);
    }
  }

  private static void requireUuid(JsonNode payload, String field, boolean nullable) {
    JsonNode value = payload.get(field);
    if (nullable && value != null && value.isNull()) return;
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Asset event payload has invalid " + field);
    }
    try {
      UUID.fromString(value.stringValue());
    } catch (IllegalArgumentException invalidUuid) {
      throw new IllegalArgumentException("Asset event payload has invalid " + field, invalidUuid);
    }
  }

  private static boolean matches(AssetEventType type, AssetAggregateType aggregate) {
    return switch (aggregate) {
      case RENTAL_ITEM -> type.name().startsWith("RENTAL_ITEM_");
      case EQUIPMENT_CATALOG -> type.name().startsWith("EQUIPMENT_CATALOG_");
      case EQUIPMENT_BALANCE -> type == AssetEventType.EQUIPMENT_BALANCE_CHANGED;
      case EQUIPMENT_MOVEMENT -> type == AssetEventType.EQUIPMENT_TRANSFERRED
          || type == AssetEventType.EQUIPMENT_WRITTEN_OFF || type == AssetEventType.EQUIPMENT_LOST;
      case EQUIPMENT_ALLOCATION_HOLD -> type.name().startsWith("EQUIPMENT_HOLD_");
      case OPERATION_LEASE -> type.name().startsWith("OPERATION_LEASE_");
      case CLASSIFIER -> type.name().startsWith("CLASSIFIER_");
    };
  }

  private static void validateSafety(JsonNode node) {
    if (node.isObject()) {
      node.properties().forEach(entry -> {
        if (FORBIDDEN.contains(normalize(entry.getKey()))) throw new IllegalArgumentException("Asset event payload contains a forbidden field");
        validateSafety(entry.getValue());
      });
    } else if (node.isArray()) {
      node.forEach(AssetEventPayloadPolicy::validateSafety);
    } else if (node.isTextual() && DomainEventEnvelopeV2.containsSensitiveTechnicalValue(node.stringValue())) {
      throw new IllegalArgumentException("Asset event payload contains a sensitive value");
    }
  }

  private static String normalize(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
}
