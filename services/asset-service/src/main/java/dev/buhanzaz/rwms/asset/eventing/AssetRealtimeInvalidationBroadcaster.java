package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.service.AssetInvalidationHub;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts committed asset facts consumed by every runtime instance into local SSE fan-out.
 *
 * <p>The Kafka binding for this component deliberately uses a group derived from the runtime
 * instance identity. Consequently every replica observes every committed fact while the browser
 * stream itself remains a lightweight, non-replayable invalidation channel.
 */
@Component
public class AssetRealtimeInvalidationBroadcaster {
  private static final String PRODUCER = "asset-service";

  private final ObjectMapper mapper;
  private final AssetEventPayloadPolicy payloadPolicy;
  private final AssetInvalidationHub invalidations;

  public AssetRealtimeInvalidationBroadcaster(
      ObjectMapper mapper,
      AssetEventPayloadPolicy payloadPolicy,
      AssetInvalidationHub invalidations) {
    this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    this.payloadPolicy = payloadPolicy;
    this.invalidations = invalidations;
  }

  public void broadcast(byte[] raw, AssetAggregateType expectedType) {
    Parsed parsed = parse(raw, expectedType);
    String scope = scope(parsed.aggregateType());
    if (scope == null) return;

    AssetInvalidationHub.AssetInvalidationEvent event =
        new AssetInvalidationHub.AssetInvalidationEvent(
            parsed.eventId(),
            null,
            scope,
            parsed.eventType().value(),
            parsed.aggregateType(),
            parsed.aggregateId(),
            parsed.aggregateVersion(),
            parsed.occurredAt());

    Set<UUID> warehouseIds = warehouseIds(parsed);
    if (warehouseIds.isEmpty()) {
      // Catalog facts and legacy movement facts have no exact warehouse scope.
      // A bounded global invalidation is safer than leaving another replica stale.
      invalidations.publishGlobal(event);
      return;
    }
    warehouseIds.forEach(
        warehouseId ->
            invalidations.publish(
                new AssetInvalidationHub.AssetInvalidationEvent(
                    event.eventId(),
                    warehouseId,
                    event.scope(),
                    event.changeType(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.revision(),
                    event.occurredAt())));
  }

  private Parsed parse(byte[] raw, AssetAggregateType expectedType) {
    try {
      JsonNode node = mapper.readTree(raw);
      DomainEventEnvelopeV2<Map<String, Object>> envelope =
          mapper
              .readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, Object>>>() {})
              .readValue(node);
      AssetAggregateType aggregateType = AssetAggregateType.valueOf(envelope.aggregateType());
      if (!PRODUCER.equals(envelope.producer()) || aggregateType != expectedType) {
        throw new AssetEventValidationException(
            "Realtime asset fact does not match its producer or topic");
      }
      UUID aggregateId = UUID.fromString(envelope.aggregateId());
      AssetEventType eventType = AssetEventType.require(envelope.eventType());
      JsonNode payload = node.get("payload");
      payloadPolicy.validateNode(envelope.eventType(), aggregateType, aggregateId, payload);
      Instant occurredAt =
          envelope.occurredAt() == null ? envelope.recordedAt() : envelope.occurredAt();
      return new Parsed(
          envelope.eventId(),
          eventType,
          aggregateType,
          aggregateId,
          envelope.aggregateVersion(),
          occurredAt,
          payload);
    } catch (AssetEventValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AssetEventValidationException("Realtime asset fact is invalid", exception);
    }
  }

  private static Set<UUID> warehouseIds(Parsed parsed) {
    if (parsed.aggregateType() == AssetAggregateType.EQUIPMENT_CATALOG) return Set.of();
    if (parsed.eventType() == AssetEventType.RENTAL_ITEM_WAREHOUSE_CHANGED) {
      // The fact has the destination only. Global invalidation also refreshes the source warehouse.
      return Set.of();
    }
    LinkedHashSet<UUID> result = new LinkedHashSet<>();
    addUuid(parsed.payload(), "warehouseId", result);
    addUuid(parsed.payload(), "sourceWarehouseId", result);
    addUuid(parsed.payload(), "targetWarehouseId", result);
    return Set.copyOf(result);
  }

  private static void addUuid(JsonNode payload, String field, Set<UUID> result) {
    JsonNode value = payload == null ? null : payload.get(field);
    if (value == null || !value.isTextual()) return;
    try {
      result.add(UUID.fromString(value.stringValue()));
    } catch (IllegalArgumentException ignored) {
      // Payload policy validates required fields. Optional legacy context stays globally invalidated.
    }
  }

  private static String scope(AssetAggregateType aggregateType) {
    return switch (aggregateType) {
      case RENTAL_ITEM -> "RENTAL_ITEMS_CHANGED";
      case EQUIPMENT_CATALOG -> "EQUIPMENT_CATALOG_CHANGED";
      case EQUIPMENT_BALANCE, EQUIPMENT_MOVEMENT, EQUIPMENT_ALLOCATION_HOLD ->
          "EQUIPMENT_CHANGED";
      default -> null;
    };
  }

  private record Parsed(
      UUID eventId,
      AssetEventType eventType,
      AssetAggregateType aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      Instant occurredAt,
      JsonNode payload) {}
}
