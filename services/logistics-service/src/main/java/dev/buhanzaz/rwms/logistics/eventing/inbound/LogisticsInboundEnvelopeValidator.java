package dev.buhanzaz.rwms.logistics.eventing.inbound;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strictly validates only source-owned schemas declared in the logistics contract. */
@Component
public class LogisticsInboundEnvelopeValidator {
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
  private static final Set<String> CORRELATION_FIELDS = Set.of("correlationId", "causationId");
  private static final Set<String> ACTOR_FIELDS =
      Set.of("subjectId", "principalType", "profileRevision");
  private static final Set<String> PROHIBITED_FIELDS =
      Set.of(
          "password",
          "secret",
          "credential",
          "accessToken",
          "refreshToken",
          "idToken",
          "login",
          "displayName",
          "email",
          "comment",
          "reason",
          "sourceParty",
          "mediaUrl",
          "objectKey",
          "objectPath",
          "signedUrl",
          "token",
          "jwt");
  private static final Pattern ACTOR_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
  private static final Pattern ACTOR_REVISION =
      Pattern.compile(
          "^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})$");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final Set<String> LOGISTICS_MEDIA_OWNER_TYPES =
      Set.of("LOGISTICS_RETURN", "LOGISTICS_TRANSFER");
  private static final Set<String> LOGISTICS_MEDIA_FIELDS =
      Set.of(
          "mediaId",
          "folderId",
          "ownerType",
          "ownerId",
          "warehouseId",
          "kind",
          "status",
          "generation",
          "rotationDegrees");

  private final ObjectMapper mapper;

  public LogisticsInboundEnvelopeValidator(ObjectMapper mapper) {
    this.mapper =
        mapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
  }

  public ValidatedInboundEvent validate(String topic, Object kafkaKey, byte[] raw) {
    if (raw == null || raw.length == 0) {
      throw invalid("Inbound event body is required");
    }
    LogisticsInboundTransportTopics.TopicPolicy policy =
        LogisticsInboundTransportTopics.requireInput(topic);
    try {
      JsonNode root = mapper.readTree(raw);
      requireExactObject(root, ENVELOPE_FIELDS, "event envelope");
      requireInt(root, "envelopeVersion", 2, 2);
      UUID eventId = requireUuid(root, "eventId");
      String eventType = requireText(root, "eventType");
      require(policy.acceptedEventTypes().contains(eventType), "Event type does not belong to input topic");
      requireInt(root, "eventVersion", 1, 1);
      requireNullableTimestamp(root, "occurredAt");
      OffsetDateTime recordedAt = requireTimestamp(root, "recordedAt");
      require(policy.producer().equals(requireText(root, "producer")), "Unexpected event producer");
      String aggregateType = requireText(root, "aggregateType");
      require(policy.aggregateType().equals(aggregateType), "Aggregate type does not belong to input topic");
      String aggregateId = requireUuid(root, "aggregateId").toString();
      long aggregateVersion =
          requireLong(
              root,
              "aggregateVersion",
              policy.payloadKind() == LogisticsInboundTransportTopics.PayloadKind.MEDIA ? 1 : 0);
      validateCorrelation(root.required("correlation"));
      validateActor(root.required("actorRef"));
      JsonNode payload = root.required("payload");
      rejectProhibitedFields(payload);
      validatePayload(policy.payloadKind(), aggregateId, eventType, payload);
      requireKafkaKey(kafkaKey, aggregateId);
      return new ValidatedInboundEvent(
          topic,
          eventId,
          eventType,
          aggregateType,
          aggregateId,
          aggregateVersion,
          recordedAt,
          LogisticsEventStore.sha256(raw),
          mapper.writeValueAsString(root),
          payload.deepCopy());
    } catch (LogisticsInboundValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid("Inbound event does not match its canonical schema", exception);
    }
  }

  private void validateCorrelation(JsonNode value) {
    requireExactObject(value, CORRELATION_FIELDS, "correlation");
    requireUuid(value, "correlationId");
    requireNullableUuid(value, "causationId");
  }

  private void validateActor(JsonNode value) {
    if (value.isNull()) {
      return;
    }
    requireExactObject(value, ACTOR_FIELDS, "actorRef");
    requireUuid(value, "subjectId");
    require(ACTOR_TYPE.matcher(requireText(value, "principalType")).matches(), "Actor type is invalid");
    JsonNode revision = value.required("profileRevision");
    require(
        revision.isNull()
            || (revision.isTextual() && ACTOR_REVISION.matcher(revision.stringValue()).matches()),
        "Actor profile revision is invalid");
  }

  private void validatePayload(
      LogisticsInboundTransportTopics.PayloadKind kind,
      String aggregateId,
      String eventType,
      JsonNode payload) {
    switch (kind) {
      case RENTAL_ITEM -> validateRentalItem(aggregateId, eventType, payload);
      case OPERATION_LEASE -> validateOperationLease(aggregateId, payload);
      case EQUIPMENT_ALLOCATION_HOLD -> validateEquipmentAllocationHold(aggregateId, payload);
      case BOARD_TASK -> validateBoardTask(aggregateId, payload);
      case MAINTENANCE_ESTIMATE -> validateMaintenanceEstimate(aggregateId, payload);
      case MEDIA -> validateMedia(aggregateId, eventType, payload);
    }
  }

  private void validateRentalItem(String aggregateId, String eventType, JsonNode payload) {
    if ("asset.rental-item.inventory-visibility-changed.v1".equals(eventType)) {
      validateInventoryVisibilityChanged(aggregateId, payload);
      return;
    }
    requireExactObject(
        payload,
        Set.of("rentalItemId", "warehouseId", "status", "numberSha256"),
        "rental-item payload");
    requireIdentity(payload, "rentalItemId", aggregateId);
    requireUuid(payload, "warehouseId");
    requireNonBlank(payload, "status", 64);
    require(SHA256.matcher(requireText(payload, "numberSha256")).matches(), "Rental item hash is invalid");
  }

  private void validateInventoryVisibilityChanged(String aggregateId, JsonNode payload) {
    requireExactObject(
        payload,
        Set.of("rentalItemId", "warehouseId", "status", "numberSha256", "inventoryId", "isolated"),
        "inventory visibility payload");
    requireIdentity(payload, "rentalItemId", aggregateId);
    requireUuid(payload, "warehouseId");
    requireNonBlank(payload, "status", 64);
    require(SHA256.matcher(requireText(payload, "numberSha256")).matches(), "Rental item hash is invalid");
    requireUuid(payload, "inventoryId");
    requireBoolean(payload, "isolated");
  }

  private void validateOperationLease(String aggregateId, JsonNode payload) {
    requireExactObject(
        payload,
        Set.of("leaseId", "rentalItemId", "fencingToken", "state"),
        "operation-lease payload");
    requireIdentity(payload, "leaseId", aggregateId);
    requireUuid(payload, "rentalItemId");
    requireLong(payload, "fencingToken", 1);
    requireEnum(payload, "state", Set.of("ACTIVE", "RELEASED", "EXPIRED"));
  }

  private void validateEquipmentAllocationHold(String aggregateId, JsonNode payload) {
    requireExactObject(
        payload,
        Set.of("holdId", "equipmentId", "warehouseId", "quantity", "state"),
        "equipment-allocation-hold payload");
    requireIdentity(payload, "holdId", aggregateId);
    requireUuid(payload, "equipmentId");
    requireUuid(payload, "warehouseId");
    requireLong(payload, "quantity", 1);
    requireEnum(payload, "state", Set.of("ACTIVE", "COMMITTED", "RELEASED", "EXPIRED"));
  }

  private void validateBoardTask(String aggregateId, JsonNode payload) {
    requireExactObject(
        payload,
        Set.of(
            "boardTaskId",
            "warehouseId",
            "externalTaskId",
            "status",
            "plannedDurationMinutes",
            "deadlineAt",
            "doneAt",
            "deleted"),
        "board-task payload");
    requireIdentity(payload, "boardTaskId", aggregateId);
    requireUuid(payload, "warehouseId");
    requireNullableUuid(payload, "externalTaskId");
    requireEnum(payload, "status", Set.of("ACTIVE", "DONE", "CANCELLED"));
    requireNullableLong(payload, "plannedDurationMinutes", 0);
    requireNullableTimestamp(payload, "deadlineAt");
    requireNullableTimestamp(payload, "doneAt");
    requireBoolean(payload, "deleted");
  }

  private void validateMaintenanceEstimate(String aggregateId, JsonNode payload) {
    requireExactObject(
        payload,
        Set.of(
            "estimateId",
            "warehouseId",
            "rentalItemId",
            "lifecycle",
            "revision",
            "dispatchDate",
            "lineCount",
            "completionKind",
            "repairId"),
        "maintenance estimate payload");
    requireIdentity(payload, "estimateId", aggregateId);
    requireUuid(payload, "warehouseId");
    requireUuid(payload, "rentalItemId");
    requireEnum(payload, "lifecycle", Set.of("DRAFT", "COMPLETED"));
    requireLong(payload, "revision", 1);
    requireDate(payload, "dispatchDate");
    requireLong(payload, "lineCount", 0);
    requireEnum(payload, "completionKind", Set.of("NOT_COMPLETED", "EMPTY", "NON_EMPTY"));
    requireNullableUuid(payload, "repairId");
  }

  private void validateMedia(String aggregateId, String eventType, JsonNode payload) {
    // The media topic is shared by every bounded context. A fact for another owner is only
    // transport evidence here: acknowledge it without applying logistics-specific owner rules.
    // Relevant return/transfer facts below still require the full canonical media shape.
    if (isForeignMediaFact(payload)) {
      return;
    }
    requireExactObject(
        payload,
        LOGISTICS_MEDIA_FIELDS,
        "media payload");
    requireIdentity(payload, "mediaId", aggregateId);
    require(
        LOGISTICS_MEDIA_OWNER_TYPES.contains(requireText(payload, "ownerType")),
        "Unexpected media owner type");
    requireLogisticsMediaOwnerId(payload, "ownerId");
    requireUuid(payload, "folderId");
    requireUuid(payload, "warehouseId");
    requireEnum(payload, "kind", Set.of("IMAGE", "VIDEO"));
    String status = requireText(payload, "status");
    requireEnumValue(status, Set.of("PROCESSING", "READY", "FAILED", "DELETED"), "status");
    require(mediaEventMatchesStatus(eventType, status), "Media event status is invalid");
    requireLong(payload, "generation", 0);
    requireIntEnum(payload, "rotationDegrees", Set.of(0, 90, 180, 270));
  }

  private static boolean isForeignMediaFact(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      return false;
    }
    JsonNode ownerType = payload.path("ownerType");
    return ownerType.isTextual() && !LOGISTICS_MEDIA_OWNER_TYPES.contains(ownerType.stringValue());
  }

  private static void requireLogisticsMediaOwnerId(JsonNode parent, String name) {
    String value = requireText(parent, name);
    String[] parts = value.split(":", -1);
    require(parts.length == 2, name + " must identify a document line");
    try {
      UUID.fromString(parts[0]);
      UUID.fromString(parts[1]);
    } catch (IllegalArgumentException exception) {
      throw invalid(name + " must identify a document line", exception);
    }
  }

  private static boolean mediaEventMatchesStatus(String eventType, String status) {
    return switch (eventType) {
      case "media.media.uploaded.v1" -> "PROCESSING".equals(status);
      case "media.media.ready.v1", "media.media.rotated.v1" -> "READY".equals(status);
      case "media.media.failed.v1" -> "FAILED".equals(status);
      case "media.media.deleted.v1" -> "DELETED".equals(status);
      default -> false;
    };
  }

  private void rejectProhibitedFields(JsonNode value) {
    if (value.isObject()) {
      value
          .properties()
          .forEach(
              entry -> {
                require(
                    !PROHIBITED_FIELDS.contains(entry.getKey()),
                    "Inbound payload contains a prohibited field");
                rejectProhibitedFields(entry.getValue());
              });
    } else if (value.isArray()) {
      value.forEach(this::rejectProhibitedFields);
    }
  }

  private void requireKafkaKey(Object kafkaKey, String aggregateId) {
    String value;
    if (kafkaKey instanceof byte[] bytes) {
      value = new String(bytes, StandardCharsets.UTF_8);
    } else if (kafkaKey instanceof String text) {
      value = text;
    } else {
      value = kafkaKey == null ? null : kafkaKey.toString();
    }
    require(aggregateId.equals(value), "Kafka key must equal aggregateId");
  }

  private static void requireExactObject(JsonNode value, Set<String> fields, String name) {
    require(value != null && value.isObject(), name + " must be an object");
    Set<String> actual = new HashSet<>();
    value.properties().forEach(entry -> actual.add(entry.getKey()));
    require(actual.equals(fields), name + " fields are invalid");
  }

  private static UUID requireUuid(JsonNode parent, String name) {
    String value = requireText(parent, name);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw invalid(name + " must be a UUID", exception);
    }
  }

  private static void requireNullableUuid(JsonNode parent, String name) {
    JsonNode value = parent.required(name);
    if (!value.isNull()) {
      requireUuid(parent, name);
    }
  }

  private static String requireText(JsonNode parent, String name) {
    JsonNode value = parent.required(name);
    require(value.isTextual() && !value.stringValue().isBlank(), name + " must be nonblank text");
    return value.stringValue();
  }

  private static void requireNonBlank(JsonNode parent, String name, int maxLength) {
    String value = requireText(parent, name);
    require(value.length() <= maxLength, name + " is too long");
  }

  private static long requireLong(JsonNode parent, String name, long minimum) {
    JsonNode value = parent.required(name);
    require(value.isIntegralNumber() && value.longValue() >= minimum, name + " is invalid");
    return value.longValue();
  }

  private static void requireNullableLong(JsonNode parent, String name, long minimum) {
    JsonNode value = parent.required(name);
    if (!value.isNull()) {
      require(value.isIntegralNumber() && value.longValue() >= minimum, name + " is invalid");
    }
  }

  private static void requireInt(JsonNode parent, String name, int minimum, int maximum) {
    JsonNode value = parent.required(name);
    require(
        value.isIntegralNumber()
            && value.longValue() >= minimum
            && value.longValue() <= maximum,
        name + " is invalid");
  }

  private static void requireIntEnum(JsonNode parent, String name, Set<Integer> allowed) {
    JsonNode value = parent.required(name);
    require(value.isIntegralNumber() && allowed.contains((int) value.longValue()), name + " is invalid");
  }

  private static void requireEnum(JsonNode parent, String name, Set<String> allowed) {
    requireEnumValue(requireText(parent, name), allowed, name);
  }

  private static void requireEnumValue(String value, Set<String> allowed, String name) {
    require(allowed.contains(value), name + " is invalid");
  }

  private static OffsetDateTime requireTimestamp(JsonNode parent, String name) {
    try {
      return OffsetDateTime.parse(requireText(parent, name));
    } catch (RuntimeException exception) {
      throw invalid(name + " must be an offset timestamp", exception);
    }
  }

  private static void requireNullableTimestamp(JsonNode parent, String name) {
    JsonNode value = parent.required(name);
    if (!value.isNull()) {
      requireTimestamp(parent, name);
    }
  }

  private static void requireDate(JsonNode parent, String name) {
    try {
      LocalDate.parse(requireText(parent, name));
    } catch (RuntimeException exception) {
      throw invalid(name + " must be an ISO date", exception);
    }
  }

  private static void requireBoolean(JsonNode parent, String name) {
    require(parent.required(name).isBoolean(), name + " must be a boolean");
  }

  private static void requireIdentity(JsonNode parent, String name, String aggregateId) {
    require(aggregateId.equals(requireUuid(parent, name).toString()), name + " must equal aggregateId");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw invalid(message);
    }
  }

  private static LogisticsInboundValidationException invalid(String message) {
    return new LogisticsInboundValidationException(message);
  }

  private static LogisticsInboundValidationException invalid(String message, Throwable cause) {
    return new LogisticsInboundValidationException(message, cause);
  }

  public record ValidatedInboundEvent(
      String sourceTopic,
      UUID eventId,
      String eventType,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      OffsetDateTime recordedAt,
      String rawMessageSha256,
      String envelopeJson,
      JsonNode payload) {
    /** True when this fact belongs to a logistics owner that this service currently consumes. */
    public boolean appliesToLogistics() {
      return !LogisticsInboundTransportTopics.MEDIA.equals(sourceTopic)
          || !isForeignMediaFact(payload);
    }

    /** Media facts are public snapshots, so their aggregate versions are monotonic, not contiguous. */
    public boolean isPublicMediaFact() {
      return LogisticsInboundTransportTopics.MEDIA.equals(sourceTopic);
    }
  }
}
