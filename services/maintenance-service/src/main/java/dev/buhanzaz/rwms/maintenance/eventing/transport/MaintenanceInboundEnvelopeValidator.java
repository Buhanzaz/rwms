package dev.buhanzaz.rwms.maintenance.eventing.transport;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
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

/** Strict validation for the source-owned schemas consumed by maintenance-service. */
@Component
public class MaintenanceInboundEnvelopeValidator {
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
  private static final Set<String> BOARD_TASK_BASE_FIELDS =
      Set.of(
          "boardTaskId",
          "warehouseId",
          "externalTaskId",
          "status",
          "plannedDurationMinutes",
          "deadlineAt",
          "doneAt",
          "deleted");
  private static final Set<String> BOARD_TASK_CURRENT_REQUIRED_FIELDS =
      java.util.stream.Stream.concat(
              BOARD_TASK_BASE_FIELDS.stream(), java.util.stream.Stream.of("lane"))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
  private static final Set<String> BOARD_TASK_CURRENT_OPTIONAL_FIELDS =
      Set.of(
          "scheduledDate",
          "priority",
          "pinned",
          "driverAudience",
          "plannedDriverWorkerId");
  private static final Set<String> QUEUE_ENTRY_BASE_FIELDS =
      Set.of(
          "queueEntryId",
          "taskId",
          "queueId",
          "routeIndex",
          "queuePosition",
          "entryType",
          "status",
          "plannedDurationMinutes",
          "activeStartedAt",
          "pausedAt",
          "doneAt",
          "activeWorkSeconds",
          "pauseOrigin",
          "assignments",
          "timeEvents",
          "interruptions",
          "deleted");
  private static final Set<String> QUEUE_ENTRY_CURRENT_FIELDS =
      java.util.stream.Stream.concat(
              QUEUE_ENTRY_BASE_FIELDS.stream(),
              java.util.stream.Stream.of("originalBudgetSeconds", "currentBudgetSeconds"))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
  private static final Set<String> QUEUE_ENTRY_HISTORICAL_FIELDS =
      java.util.stream.Stream.concat(
              QUEUE_ENTRY_BASE_FIELDS.stream(), java.util.stream.Stream.of("queueName"))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
          "objectKey");
  private static final Pattern ACTOR_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
  private static final Pattern ACTOR_REVISION =
      Pattern.compile(
          "^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})$");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

  private final ObjectMapper mapper;

  public MaintenanceInboundEnvelopeValidator(ObjectMapper mapper) {
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
    MaintenanceTransportTopics.TopicPolicy policy = MaintenanceTransportTopics.requireInput(topic);
    try {
      JsonNode root = mapper.readTree(raw);
      requireExactObject(root, ENVELOPE_FIELDS, "event envelope");
      requireInt(root, "envelopeVersion", 2, 2);
      UUID eventId = requireUuid(root, "eventId");
      String eventType = requireText(root, "eventType");
      require(policy.acceptedEventTypes().contains(eventType), "Event type does not belong to input topic");
      requireInt(root, "eventVersion", 1, 1);
      requireNullableTimestamp(root, "occurredAt");
      requireTimestamp(root, "recordedAt");
      require(policy.producer().equals(requireText(root, "producer")), "Unexpected event producer");
      String aggregateType = requireText(root, "aggregateType");
      require(policy.aggregateType().equals(aggregateType), "Aggregate type does not belong to input topic");
      String aggregateId = requireUuid(root, "aggregateId").toString();
      long aggregateVersion = requireLong(root, "aggregateVersion", 0);
      validateCorrelation(root.required("correlation"));
      validateActor(root.required("actorRef"));
      JsonNode payload = root.required("payload");
      rejectProhibitedFields(payload);
      validatePayload(topic, eventType, aggregateId, payload);
      requireKafkaKey(kafkaKey, aggregateId);
      return new ValidatedInboundEvent(
          topic,
          eventId,
          eventType,
          aggregateType,
          aggregateId,
          aggregateVersion,
          policy.actionableEventTypes().contains(eventType),
          MaintenanceChecksum.sha256(raw),
          mapper.writeValueAsString(root),
          payload.deepCopy());
    } catch (MaintenanceInboundValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid("Inbound event does not match its canonical schema", exception);
    }
  }

  private void validateCorrelation(JsonNode node) {
    requireExactObject(node, CORRELATION_FIELDS, "correlation");
    requireUuid(node, "correlationId");
    requireNullableUuid(node, "causationId");
  }

  private void validateActor(JsonNode node) {
    if (node.isNull()) {
      return;
    }
    requireExactObject(node, ACTOR_FIELDS, "actorRef");
    requireUuid(node, "subjectId");
    require(
        ACTOR_TYPE.matcher(requireText(node, "principalType")).matches(),
        "Actor principalType is invalid");
    JsonNode revision = node.required("profileRevision");
    require(
        revision.isNull()
            || (revision.isTextual() && ACTOR_REVISION.matcher(revision.stringValue()).matches()),
        "Actor profileRevision is invalid");
  }

  private void validatePayload(
      String topic, String eventType, String aggregateId, JsonNode payload) {
    switch (topic) {
      case MaintenanceTransportTopics.BOARD_TASK -> validateBoardTask(aggregateId, payload);
      case MaintenanceTransportTopics.QUEUE_ENTRY -> validateQueueEntry(aggregateId, payload);
      case MaintenanceTransportTopics.TASK_EVIDENCE -> validateTaskEvidence(aggregateId, payload);
      case MaintenanceTransportTopics.MEDIA -> validateMedia(aggregateId, payload);
      case MaintenanceTransportTopics.RENTAL_ITEM ->
        validateRentalItem(eventType, aggregateId, payload);
      case MaintenanceTransportTopics.OPERATION_LEASE -> validateLease(aggregateId, payload);
      default -> throw invalid("Unsupported maintenance input topic");
    }
  }

  private void validateBoardTask(String aggregateId, JsonNode payload) {
    boolean currentShape = payload.has("lane");
    if (currentShape) {
      requireObjectWithOptionalFields(
          payload,
          BOARD_TASK_CURRENT_REQUIRED_FIELDS,
          BOARD_TASK_CURRENT_OPTIONAL_FIELDS,
          "board-task payload");
    } else {
      requireExactObject(payload, BOARD_TASK_BASE_FIELDS, "board-task payload");
    }
    requireIdentity(payload, "boardTaskId", aggregateId);
    requireUuid(payload, "warehouseId");
    requireNullableUuid(payload, "externalTaskId");
    requireEnum(payload, "status", Set.of("ACTIVE", "DONE", "CANCELLED"));
    if (currentShape) {
      requireEnum(payload, "lane", Set.of("SCHEDULED", "CURRENT"));
      if (payload.has("scheduledDate")) {
        requireLocalDate(payload, "scheduledDate");
      }
      if (payload.has("priority")) {
        requireInt(payload, "priority", 1, 5);
      }
      if (payload.has("pinned")) {
        requireBoolean(payload, "pinned");
      }
      validateDriverAudience(payload);
    }
    requireNullableInteger(payload, "plannedDurationMinutes");
    requireNullableTimestamp(payload, "deadlineAt");
    requireNullableTimestamp(payload, "doneAt");
    requireBoolean(payload, "deleted");
  }

  private void validateQueueEntry(String aggregateId, JsonNode payload) {
    boolean currentShape =
        payload.has("originalBudgetSeconds") || payload.has("currentBudgetSeconds");
    requireExactObject(
        payload,
        currentShape ? QUEUE_ENTRY_CURRENT_FIELDS : QUEUE_ENTRY_HISTORICAL_FIELDS,
        "queue-entry payload");
    requireIdentity(payload, "queueEntryId", aggregateId);
    requireUuid(payload, "taskId");
    requireNullableUuid(payload, "queueId");
    if (!currentShape) {
      requireText(payload, "queueName");
    }
    requireInteger(payload, "routeIndex");
    requireInteger(payload, "queuePosition");
    requireEnum(payload, "entryType", Set.of("REAL", "SHADOW"));
    requireEnum(payload, "status", Set.of("WAITING", "IN_PROGRESS", "PAUSED", "DONE", "CANCELLED"));
    requireNullableInteger(payload, "plannedDurationMinutes");
    requireNullableTimestamp(payload, "activeStartedAt");
    requireNullableTimestamp(payload, "pausedAt");
    requireNullableTimestamp(payload, "doneAt");
    requireLong(payload, "activeWorkSeconds", 0);
    if (currentShape) {
      requireNullableLong(payload, "originalBudgetSeconds", 1);
      requireNullableLong(payload, "currentBudgetSeconds", 1);
    }
    requireNullableEnum(payload, "pauseOrigin", Set.of("MANUAL", "AUTO"));
    requireArray(payload, "assignments").forEach(this::validateAssignment);
    requireArray(payload, "timeEvents").forEach(this::validateTimeEvent);
    requireArray(payload, "interruptions").forEach(this::validateInterruption);
    requireBoolean(payload, "deleted");
  }

  private static void validateDriverAudience(JsonNode payload) {
    JsonNode audience = payload.get("driverAudience");
    JsonNode plannedWorker = payload.get("plannedDriverWorkerId");
    if (audience == null) {
      require(plannedWorker == null, "A planned driver requires a driver audience");
      return;
    }
    requireNullableEnum(
        payload,
        "driverAudience",
        Set.of("UNASSIGNED", "ASSIGNED_DRIVER", "WAREHOUSE_DRIVERS"));
    if (plannedWorker != null) {
      requireNullableUuid(payload, "plannedDriverWorkerId");
    }
    String mode = audience.isNull() ? null : audience.stringValue();
    boolean assigned = "ASSIGNED_DRIVER".equals(mode);
    boolean hasWorker = plannedWorker != null && !plannedWorker.isNull();
    require(assigned == hasWorker, "Driver audience and planned worker are inconsistent");
  }

  private void validateAssignment(JsonNode value) {
    requireExactObject(
        value,
        Set.of(
            "assignmentId",
            "version",
            "workerGroupId",
            "workerId",
            "status",
            "assignedAt",
            "startedAt",
            "pausedAt",
            "finishedAt"),
        "assignment payload");
    requireUuid(value, "assignmentId");
    requireLong(value, "version", 0);
    requireNullableUuid(value, "workerGroupId");
    requireNullableUuid(value, "workerId");
    requireEnum(value, "status", Set.of("ACTIVE", "PAUSED", "DONE", "CANCELLED"));
    requireNullableTimestamp(value, "assignedAt");
    requireNullableTimestamp(value, "startedAt");
    requireNullableTimestamp(value, "pausedAt");
    requireNullableTimestamp(value, "finishedAt");
  }

  private void validateTaskEvidence(String aggregateId, JsonNode payload) {
    requireExactObject(
        payload,
        Set.of(
            "evidenceId",
            "entryId",
            "taskId",
            "routeIndex",
            "warehouseId",
            "workerId",
            "workerGroupId",
            "mediaId",
            "mediaGeneration",
            "capturedAt",
            "recordedAt",
            "state",
            "sourceType",
            "sourceId"),
        "task-evidence payload");
    requireIdentity(payload, "evidenceId", aggregateId);
    requireUuid(payload, "entryId");
    requireUuid(payload, "taskId");
    requireInt(payload, "routeIndex", 0, Integer.MAX_VALUE);
    requireUuid(payload, "warehouseId");
    requireUuid(payload, "workerId");
    requireNullableUuid(payload, "workerGroupId");
    requireUuid(payload, "mediaId");
    requireLong(payload, "mediaGeneration", 0);
    requireTimestamp(payload, "capturedAt");
    requireTimestamp(payload, "recordedAt");
    requireEnum(payload, "state", Set.of("READY", "REVIEW_REQUIRED"));
    JsonNode sourceType = payload.required("sourceType");
    JsonNode sourceId = payload.required("sourceId");
    require(
        (sourceType.isNull() && sourceId.isNull())
            || (sourceType.isTextual()
                && !sourceType.stringValue().isBlank()
                && sourceType.stringValue().length() <= 64
                && sourceId.isTextual()),
        "task-evidence source identity is invalid");
    if (!sourceId.isNull()) requireUuid(payload, "sourceId");
  }

  private void validateTimeEvent(JsonNode value) {
    requireExactObject(
        value,
        Set.of("timeEventId", "version", "workerId", "eventType", "createdAt", "relatedEntryId"),
        "time-event payload");
    requireUuid(value, "timeEventId");
    requireLong(value, "version", 0);
    requireNullableUuid(value, "workerId");
    requireEnum(
        value,
        "eventType",
        Set.of(
            "STARTED",
            "PAUSED",
            "RESUMED",
            "FINISHED",
            "CANCELLED",
            "AUTO_INTERRUPTED",
            "AUTO_RESUMED"));
    requireTimestamp(value, "createdAt");
    requireNullableUuid(value, "relatedEntryId");
  }

  private void validateInterruption(JsonNode value) {
    requireExactObject(
        value,
        Set.of(
            "interruptionId",
            "version",
            "workerId",
            "interruptedEntryId",
            "interruptingEntryId",
            "active",
            "createdAt",
            "resolvedAt"),
        "interruption payload");
    requireUuid(value, "interruptionId");
    requireLong(value, "version", 0);
    requireUuid(value, "workerId");
    requireUuid(value, "interruptedEntryId");
    requireUuid(value, "interruptingEntryId");
    requireBoolean(value, "active");
    requireTimestamp(value, "createdAt");
    requireNullableTimestamp(value, "resolvedAt");
  }

  private void validateMedia(String aggregateId, JsonNode payload) {
    Set<String> fields =
        new HashSet<>(
            Set.of(
                "mediaId",
                "ownerType",
                "ownerId",
                "warehouseId",
                "kind",
                "status",
                "generation",
                "rotationDegrees"));
    if (payload.has("folderId")) fields.add("folderId");
    if (payload.has("clientReferenceId")) fields.add("clientReferenceId");
    requireExactObject(payload, fields, "media payload");
    requireIdentity(payload, "mediaId", aggregateId);
    String ownerType = requireText(payload, "ownerType");
    requireLength(ownerType, 64, "media ownerType");
    requireLength(requireText(payload, "ownerId"), 128, "media ownerId");
    requireUuid(payload, "warehouseId");
    if (payload.has("folderId")) requireUuid(payload, "folderId");
    if ("TASK_BOARD_ENTRY".equals(ownerType)) {
      requireUuid(payload, "clientReferenceId");
    } else if (payload.has("clientReferenceId")) {
      require(
          payload.required("clientReferenceId").isNull(),
          "media clientReferenceId is invalid");
    }
    requireEnum(payload, "kind", Set.of("IMAGE", "VIDEO"));
    if ("TASK_BOARD_ENTRY".equals(ownerType)) {
      require(
          "IMAGE".equals(payload.required("kind").stringValue()),
          "Worker evidence must be an image");
    }
    requireEnum(
        payload,
        "status",
        Set.of("UPLOADING", "PROCESSING", "READY", "FAILED", "DELETED"));
    requireLong(payload, "generation", 0);
    requireIntEnum(payload, "rotationDegrees", Set.of(0, 90, 180, 270));
  }

  private void validateRentalItem(String eventType, String aggregateId, JsonNode payload) {
    if (eventType.endsWith("general-comment-changed.v1")) {
      requireExactObject(payload, Set.of("rentalItemId", "commentRevision"), "rental comment payload");
      requireIdentity(payload, "rentalItemId", aggregateId);
      requireLong(payload, "commentRevision", 0);
      return;
    }
    if (eventType.endsWith("manual-note-added.v1")) {
      requireExactObject(payload, Set.of("rentalItemId", "noteId"), "rental note payload");
      requireIdentity(payload, "rentalItemId", aggregateId);
      requireUuid(payload, "noteId");
      return;
    }
    requireExactObject(
        payload,
        Set.of("rentalItemId", "warehouseId", "status", "numberSha256"),
        "rental-item payload");
    requireIdentity(payload, "rentalItemId", aggregateId);
    requireUuid(payload, "warehouseId");
    requireText(payload, "status");
    require(
        SHA256.matcher(requireText(payload, "numberSha256")).matches(),
        "Rental item number hash is invalid");
  }

  private void validateLease(String aggregateId, JsonNode payload) {
    requireExactObject(
        payload,
        Set.of("leaseId", "rentalItemId", "fencingToken", "state"),
        "operation-lease payload");
    requireIdentity(payload, "leaseId", aggregateId);
    requireUuid(payload, "rentalItemId");
    requireLong(payload, "fencingToken", 1);
    requireEnum(payload, "state", Set.of("ACTIVE", "RELEASED", "EXPIRED"));
  }

  private void rejectProhibitedFields(JsonNode node) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              entry -> {
                require(
                    !PROHIBITED_FIELDS.contains(entry.getKey()),
                    "Inbound payload contains a prohibited field");
                rejectProhibitedFields(entry.getValue());
              });
    } else if (node.isArray()) {
      node.forEach(this::rejectProhibitedFields);
    }
  }

  private void requireKafkaKey(Object kafkaKey, String aggregateId) {
    require(kafkaKey instanceof byte[], "Kafka key must be raw UTF-8 aggregateId bytes");
    byte[] bytes = (byte[]) kafkaKey;
    String decoded = new String(bytes, StandardCharsets.UTF_8);
    require(
        java.util.Arrays.equals(decoded.getBytes(StandardCharsets.UTF_8), bytes),
        "Kafka key is not canonical UTF-8");
    require(aggregateId.equals(decoded), "Kafka key does not match aggregateId");
  }

  private static void requireExactObject(JsonNode node, Set<String> fields, String label) {
    require(node != null && node.isObject(), label + " must be an object");
    Set<String> actual = new HashSet<>();
    node.properties().forEach(entry -> actual.add(entry.getKey()));
    require(actual.equals(fields), label + " fields do not match the canonical schema");
  }

  private static void requireObjectWithOptionalFields(
      JsonNode node, Set<String> required, Set<String> optional, String label) {
    require(node != null && node.isObject(), label + " must be an object");
    Set<String> actual = new HashSet<>();
    node.properties().forEach(entry -> actual.add(entry.getKey()));
    Set<String> allowed = new HashSet<>(required);
    allowed.addAll(optional);
    require(
        actual.containsAll(required) && allowed.containsAll(actual),
        label + " fields do not match the canonical schema");
  }

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.required(field);
    require(value.isTextual(), field + " must be text");
    return value.stringValue();
  }

  private static UUID requireUuid(JsonNode node, String field) {
    try {
      String value = requireText(node, field);
      UUID uuid = UUID.fromString(value);
      require(uuid.toString().equals(value), field + " must be a canonical lowercase UUID");
      return uuid;
    } catch (IllegalArgumentException exception) {
      throw invalid(field + " must be a canonical UUID", exception);
    }
  }

  private static void requireNullableUuid(JsonNode node, String field) {
    JsonNode value = node.required(field);
    if (!value.isNull()) {
      requireUuid(node, field);
    }
  }

  private static long requireLong(JsonNode node, String field, long minimum) {
    JsonNode value = node.required(field);
    require(value.isIntegralNumber() && value.canConvertToLong(), field + " must be an integer");
    long result = value.longValue();
    require(result >= minimum, field + " is below its minimum");
    return result;
  }

  private static void requireNullableLong(JsonNode node, String field, long minimum) {
    JsonNode value = node.required(field);
    if (!value.isNull()) {
      requireLong(node, field, minimum);
    }
  }

  private static void requireInteger(JsonNode node, String field) {
    JsonNode value = node.required(field);
    require(value.isInt(), field + " must be a 32-bit integer");
  }

  private static void requireInt(JsonNode node, String field, int minimum, int maximum) {
    JsonNode value = node.required(field);
    require(value.isInt(), field + " must be a 32-bit integer");
    require(value.intValue() >= minimum && value.intValue() <= maximum, field + " is invalid");
  }

  private static void requireNullableInteger(JsonNode node, String field) {
    JsonNode value = node.required(field);
    require(value.isNull() || value.isIntegralNumber(), field + " must be an integer or null");
  }

  private static void requireBoolean(JsonNode node, String field) {
    require(node.required(field).isBoolean(), field + " must be boolean");
  }

  private static void requireEnum(JsonNode node, String field, Set<String> values) {
    require(values.contains(requireText(node, field)), field + " is outside the canonical enum");
  }

  private static void requireNullableEnum(JsonNode node, String field, Set<String> values) {
    JsonNode value = node.required(field);
    require(value.isNull() || (value.isTextual() && values.contains(value.stringValue())), field + " is invalid");
  }

  private static void requireIntEnum(JsonNode node, String field, Set<Integer> values) {
    JsonNode value = node.required(field);
    require(value.isInt() && values.contains(value.intValue()), field + " is outside the canonical enum");
  }

  private static void requireTimestamp(JsonNode node, String field) {
    try {
      OffsetDateTime.parse(requireText(node, field));
    } catch (RuntimeException exception) {
      throw invalid(field + " must be an RFC 3339 timestamp", exception);
    }
  }

  private static void requireNullableTimestamp(JsonNode node, String field) {
    JsonNode value = node.required(field);
    if (!value.isNull()) {
      requireTimestamp(node, field);
    }
  }

  private static void requireNullableLocalDate(JsonNode node, String field) {
    JsonNode value = node.required(field);
    if (value.isNull()) {
      return;
    }
    String text = requireText(node, field);
    try {
      LocalDate parsed = LocalDate.parse(text);
      require(parsed.toString().equals(text), field + " must be a canonical ISO local date");
    } catch (MaintenanceInboundValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid(field + " must be a canonical ISO local date", exception);
    }
  }

  private static void requireLocalDate(JsonNode node, String field) {
    require(!node.required(field).isNull(), field + " must be a canonical ISO local date");
    requireNullableLocalDate(node, field);
  }

  private static Iterable<JsonNode> requireArray(JsonNode node, String field) {
    JsonNode value = node.required(field);
    require(value.isArray(), field + " must be an array");
    return value;
  }

  private static void requireIdentity(JsonNode node, String field, String aggregateId) {
    require(aggregateId.equals(requireUuid(node, field).toString()), field + " does not match aggregateId");
  }

  private static void requireLength(String value, int maximum, String label) {
    require(value.length() <= maximum, label + " exceeds its maximum length");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw invalid(message);
    }
  }

  private static MaintenanceInboundValidationException invalid(String message) {
    return new MaintenanceInboundValidationException(message);
  }

  private static MaintenanceInboundValidationException invalid(String message, Throwable cause) {
    return new MaintenanceInboundValidationException(message, cause);
  }

  public record ValidatedInboundEvent(
      String sourceTopic,
      UUID eventId,
      String eventType,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      boolean actionable,
      String rawMessageSha256,
      String envelopeJson,
      JsonNode payload) {
    public MaintenanceInboundEffects.InboundEvent effectEvent() {
      return new MaintenanceInboundEffects.InboundEvent(
          sourceTopic,
          eventId,
          eventType,
          aggregateType,
          aggregateId,
          aggregateVersion,
          payload.deepCopy());
    }
  }
}
