package dev.buhanzaz.rwms.warehouse.eventing;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.warehouse.service.WarehouseChecksum;
import dev.buhanzaz.rwms.warehouse.service.WarehouseConflictException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Validates the frozen warehouse event contract before an administrator may requeue it. */
@Component
final class WarehouseOutboxEnvelopeValidator {
  private static final Set<String> ROOT_FIELDS =
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
  private static final Set<String> CORRELATION_FIELDS = Set.of("correlationId", "causationId");
  private static final Set<String> ACTOR_FIELDS =
      Set.of("subjectId", "principalType", "profileRevision");

  private final ObjectMapper strictObjectMapper;
  private final WarehouseEventPayloadPolicy payloadPolicy;

  WarehouseOutboxEnvelopeValidator(
      ObjectMapper objectMapper, WarehouseEventPayloadPolicy payloadPolicy) {
    strictObjectMapper =
        objectMapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    this.payloadPolicy = payloadPolicy;
  }

  void validateOrThrow(ImmutableOutboxEnvelope stored) {
    if (stored == null
        || stored.eventId() == null
        || stored.aggregateType() == null
        || stored.aggregateId() == null
        || stored.eventType() == null
        || stored.topic() == null
        || stored.envelopeBody() == null
        || stored.envelopeSha256() == null
        || stored.occurredAt() == null
        || stored.recordedAt() == null) {
      throw rejected();
    }
    String computedChecksum =
        WarehouseChecksum.sha256(stored.envelopeBody().getBytes(StandardCharsets.UTF_8));
    if (!computedChecksum.equals(stored.envelopeSha256().trim())) throw rejected();

    JsonNode root;
    DomainEventEnvelopeV2<Map<String, Object>> envelope;
    try {
      root = strictObjectMapper.readTree(stored.envelopeBody());
      requireCanonicalShape(root);
      envelope =
          strictObjectMapper
              .readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, Object>>>() {})
              .readValue(root);
    } catch (RuntimeException exception) {
      throw rejected();
    }

    try {
      WarehouseEventType eventType = WarehouseEventType.require(envelope.eventType());
      WarehouseAggregateType.requireTopic(stored.topic());
      if (!stored.eventId().equals(envelope.eventId())
          || !stored.aggregateType().equals(envelope.aggregateType())
          || !stored.aggregateId().equals(envelope.aggregateId())
          || stored.aggregateVersion() != envelope.aggregateVersion()
          || !stored.eventType().equals(envelope.eventType())
          || stored.eventVersion() != envelope.eventVersion()
          || !"warehouse-service".equals(envelope.producer())
          || !WarehouseAggregateType.WAREHOUSE.topic().equals(stored.topic())
          || !sameInstant(stored.occurredAt(), envelope.occurredAt())
          || !sameInstant(stored.recordedAt(), envelope.recordedAt())
          || !payloadWarehouseMatchesAggregate(root.get("payload"), stored.aggregateId())) {
        throw rejected();
      }
      payloadPolicy.validate(eventType.value(), root.get("payload"));
    } catch (WarehouseConflictException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw rejected();
    }
  }

  private static void requireCanonicalShape(JsonNode root) {
    if (root == null || !root.isObject() || !ROOT_FIELDS.equals(fieldNames(root))) throw rejected();
    if (!root.get("envelopeVersion").isInt()
        || !root.get("eventId").isTextual()
        || !root.get("eventType").isTextual()
        || !root.get("eventVersion").isInt()
        || !root.get("occurredAt").isTextual()
        || !root.get("recordedAt").isTextual()
        || !root.get("producer").isTextual()
        || !root.get("aggregateType").isTextual()
        || !root.get("aggregateId").isTextual()
        || !root.get("aggregateVersion").isIntegralNumber()
        || !root.get("aggregateVersion").canConvertToLong()
        || !root.get("payload").isObject()) {
      throw rejected();
    }
    JsonNode correlation = root.get("correlation");
    if (correlation == null
        || !correlation.isObject()
        || !CORRELATION_FIELDS.equals(fieldNames(correlation))
        || !correlation.get("correlationId").isTextual()
        || (!correlation.get("causationId").isNull()
            && !correlation.get("causationId").isTextual())) {
      throw rejected();
    }
    JsonNode actor = root.get("actorRef");
    if (actor != null && !actor.isNull()) {
      if (!actor.isObject()
          || !ACTOR_FIELDS.equals(fieldNames(actor))
          || !actor.get("subjectId").isTextual()
          || !actor.get("principalType").isTextual()
          || (!actor.get("profileRevision").isNull()
              && !actor.get("profileRevision").isTextual())) {
        throw rejected();
      }
    }
  }

  private static boolean payloadWarehouseMatchesAggregate(JsonNode payload, String aggregateId) {
    if (payload == null || !payload.isObject()) return false;
    JsonNode warehouseId = payload.get("warehouseId");
    if (warehouseId == null || !warehouseId.isTextual()) return false;
    try {
      return UUID.fromString(aggregateId).equals(UUID.fromString(warehouseId.stringValue()));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean sameInstant(OffsetDateTime stored, Instant envelope) {
    return stored != null && envelope != null && stored.toInstant().equals(envelope);
  }

  private static Set<String> fieldNames(JsonNode node) {
    return new HashSet<>(node.propertyNames());
  }

  private static WarehouseConflictException rejected() {
    return new WarehouseConflictException(
        "Warehouse outbox event has no valid immutable envelope for reviewed recovery");
  }

  record ImmutableOutboxEnvelope(
      UUID eventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      int eventVersion,
      String topic,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      String envelopeBody,
      String envelopeSha256) {}
}
