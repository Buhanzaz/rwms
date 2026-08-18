package dev.buhanzaz.rwms.dossier.eventing;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Validates the exact dossier source-contract surface before any raw value reaches PostgreSQL. */
@Component
public final class DossierEnvelopeValidator {
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
          "accesstoken",
          "refreshtoken",
          "idtoken",
          "login",
          "displayname",
          "email",
          "comment",
          "reason",
          "sourceparty",
          "mediaurl",
          "objectkey",
          "objectpath",
          "signedurl",
          "token",
          "jwt");

  private static final Map<String, EventPolicy> EVENT_POLICIES = policies();

  private final ObjectMapper mapper;
  private final DossierProducerSchemaValidator producerSchemas;

  public DossierEnvelopeValidator(
      ObjectMapper mapper, DossierProducerSchemaValidator producerSchemas) {
    this.mapper =
        mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    this.producerSchemas = producerSchemas;
  }

  /**
   * Parses one Kafka record only when its topic policy, canonical V2 envelope, aggregate key and
   * producer-specific payload schema all agree; validation itself does not persist raw input.
   */
  public DossierValidatedEvent validate(
      String topic, int partition, long offset, Object kafkaKey, byte[] raw) {
    if (partition < 0 || offset < 0 || raw == null || raw.length == 0) {
      throw invalid("SOURCE_RECORD_INVALID");
    }
    DossierSourceTopics.TopicPolicy topicPolicy = DossierSourceTopics.require(topic);
    try {
      JsonNode root = mapper.readTree(raw);
      requireExactObject(root, ENVELOPE_FIELDS);
      require(root.required("envelopeVersion").isInt() && root.required("envelopeVersion").intValue() == 2);
      UUID eventId = uuid(root, "eventId", false);
      String eventType = text(root, "eventType");
      EventPolicy eventPolicy = EVENT_POLICIES.get(eventType);
      if (eventPolicy == null || !eventPolicy.topic().equals(topic)) {
        throw invalid("SOURCE_EVENT_TYPE_UNSUPPORTED");
      }
      int eventVersion = integer(root, "eventVersion", 1);
      if (eventVersion != 1) throw invalid("SOURCE_EVENT_VERSION_UNSUPPORTED");
      Instant occurredAt = instant(root, "occurredAt", true);
      Instant recordedAt = instant(root, "recordedAt", false);
      String producer = text(root, "producer");
      if (!topicPolicy.producer().equals(producer)) {
        throw invalid("SOURCE_PRODUCER_UNSUPPORTED");
      }
      String aggregateType = text(root, "aggregateType");
      if (!topicPolicy.aggregateTypes().contains(aggregateType)
          || !eventPolicy.aggregateType().equals(aggregateType)) {
        throw invalid("SOURCE_AGGREGATE_UNSUPPORTED");
      }
      UUID aggregateId = uuid(root, "aggregateId", false);
      if (!normalizeKafkaKey(kafkaKey).equals(aggregateId.toString())) {
        throw invalid("SOURCE_RECORD_KEY_MISMATCH");
      }
      long aggregateVersion = number(root, "aggregateVersion", 0);

      JsonNode correlation = root.required("correlation");
      requireExactObject(correlation, CORRELATION_FIELDS);
      UUID correlationId = uuid(correlation, "correlationId", false);
      UUID causationId = uuid(correlation, "causationId", true);

      JsonNode actor = root.required("actorRef");
      UUID actorSubjectId = null;
      String principalType = null;
      String profileRevision = null;
      if (!actor.isNull()) {
        requireExactObject(actor, ACTOR_FIELDS);
        actorSubjectId = uuid(actor, "subjectId", false);
        principalType = text(actor, "principalType");
        profileRevision = nullableText(actor, "profileRevision");
      }

      JsonNode payload = root.required("payload");
      try {
        producerSchemas.validate(topic, raw);
      } catch (DossierValidationException exception) {
        throw invalid("SOURCE_PAYLOAD_REJECTED", exception);
      }
      requireObjectFields(payload, eventPolicy.payloadFields(), eventPolicy.requiredFields());
      rejectProhibited(payload);
      eventPolicy.identityField().ifPresent(field -> require(uuid(payload, field, false).equals(aggregateId)));

      Subject subject = subject(eventPolicy, payload, aggregateId);
      String canonicalEnvelope = canonicalJson(root);
      return new DossierValidatedEvent(
          topic,
          partition,
          offset,
          eventId,
          eventType,
          eventVersion,
          occurredAt,
          recordedAt,
          producer,
          topicPolicy.producerCode(),
          aggregateType,
          aggregateId,
          aggregateVersion,
          correlationId,
          causationId,
          actorSubjectId,
          principalType,
          profileRevision,
          DossierEventHash.sha256(canonicalEnvelope),
          canonicalEnvelope,
          payload.deepCopy(),
          subject.cabinId(),
          subject.warehouseId(),
          subject.secondaryId(),
          eventPolicy.activityCode(),
          eventPolicy.subjectKind() != SubjectKind.NONE);
    } catch (DossierValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid("SOURCE_SCHEMA_REJECTED", exception);
    }
  }

  static Set<String> acceptedEventTypes(String topic) {
    return EVENT_POLICIES.entrySet().stream()
        .filter(entry -> entry.getValue().topic().equals(topic))
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static Subject subject(EventPolicy policy, JsonNode payload, UUID aggregateId) {
    return switch (policy.subjectKind()) {
      case ASSET ->
          new Subject(
              uuid(payload, "rentalItemId", false), optionalUuid(payload, "warehouseId"), null);
      case MAINTENANCE ->
          new Subject(
              uuid(payload, "rentalItemId", false), uuid(payload, "warehouseId", false), null);
      case INVENTORY_FINDING ->
          new Subject(
              uuid(payload, "assetId", true),
              uuid(payload, "warehouseId", false),
              uuid(payload, "findingId", false));
      case INVENTORY_PUBLICATION ->
          new Subject(
              null,
              uuid(payload, "warehouseId", false),
              uuid(payload, "findingId", false));
      case INVENTORY_OWNER_PROOF ->
          new Subject(
              null,
              uuid(payload, "warehouseId", false),
              uuid(payload, "ownerId", false));
      case MEDIA -> {
        String ownerType = text(payload, "ownerType");
        UUID ownerId = uuid(payload, "ownerId", false);
        UUID warehouseId = uuid(payload, "warehouseId", false);
        yield switch (ownerType) {
          case "INVENTORY_FINDING" -> new Subject(null, warehouseId, ownerId);
          case "CABIN" -> new Subject(ownerId, warehouseId, ownerId);
          default -> throw invalid("SOURCE_SCHEMA_REJECTED");
        };
      }
      case NONE -> new Subject(null, optionalUuid(payload, "warehouseId"), aggregateId);
    };
  }

  private static Map<String, EventPolicy> policies() {
    Map<String, EventPolicy> result = new java.util.LinkedHashMap<>();
    Set<String> rental = Set.of("rentalItemId", "warehouseId", "status", "numberSha256");
    add(result, "rwms.asset.rental-item.v1", "RENTAL_ITEM", SubjectKind.ASSET, rental, rental,
        "rentalItemId", "CABIN_CREATED", "asset.rental-item.created.v1");
    add(result, "rwms.asset.rental-item.v1", "RENTAL_ITEM", SubjectKind.ASSET, rental, rental,
        "rentalItemId", "CABIN_PASSPORT_CHANGED", "asset.rental-item.passport-changed.v1");
    add(result, "rwms.asset.rental-item.v1", "RENTAL_ITEM", SubjectKind.ASSET, rental, rental,
        "rentalItemId", "CABIN_STATUS_CHANGED", "asset.rental-item.status-changed.v1");
    add(result, "rwms.asset.rental-item.v1", "RENTAL_ITEM", SubjectKind.ASSET, rental, rental,
        "rentalItemId", "CABIN_WAREHOUSE_CHANGED", "asset.rental-item.warehouse-changed.v1");
    add(result, "rwms.asset.rental-item.v1", "RENTAL_ITEM", SubjectKind.ASSET, rental, rental,
        "rentalItemId", "CABIN_LOGISTICS_EFFECT_APPLIED", "asset.rental-item.logistics-effect-applied.v1");
    Set<String> comment = Set.of("rentalItemId", "commentRevision");
    add(result, "rwms.asset.rental-item.v1", "RENTAL_ITEM", SubjectKind.ASSET, comment, comment,
        "rentalItemId", "CABIN_COMMENT_REVISION_CHANGED", "asset.rental-item.general-comment-changed.v1");
    Set<String> note = Set.of("rentalItemId", "noteId");
    add(result, "rwms.asset.rental-item.v1", "RENTAL_ITEM", SubjectKind.ASSET, note, note,
        "rentalItemId", "CABIN_MANUAL_NOTE_ADDED", "asset.rental-item.manual-note-added.v1");

    Set<String> estimate = Set.of("estimateId", "warehouseId", "rentalItemId", "lifecycle", "revision", "dispatchDate", "lineCount", "completionKind", "repairId", "forceCapitalRepair");
    addMaintenance(result, "ESTIMATE", estimate, "estimateId", Map.of(
        "maintenance.estimate.created.v1", "ESTIMATE_CREATED",
        "maintenance.estimate.draft-changed.v1", "ESTIMATE_DRAFT_CHANGED",
        "maintenance.estimate.completed.v1", "ESTIMATE_COMPLETED",
        "maintenance.estimate.amended.v1", "ESTIMATE_AMENDED"));
    Set<String> repair = Set.of("repairId", "rootRepairId", "sourceRepairId", "estimateId", "warehouseId", "rentalItemId", "origin", "kind", "executionState", "acceptanceState", "dispatchDate", "priority", "stages", "forceCapitalRepair");
    addMaintenance(result, "REPAIR", repair, "repairId", Map.ofEntries(
        Map.entry("maintenance.repair.created.v1", "REPAIR_CREATED"),
        Map.entry("maintenance.repair.plan-changed.v1", "REPAIR_PLAN_CHANGED"),
        Map.entry("maintenance.repair.queued.v1", "REPAIR_QUEUED"),
        Map.entry("maintenance.repair.stage-completed.v1", "REPAIR_STAGE_COMPLETED"),
        Map.entry("maintenance.repair.pending-acceptance.v1", "REPAIR_PENDING_ACCEPTANCE"),
        Map.entry("maintenance.repair.rework-created.v1", "REPAIR_REWORK_CREATED"),
        Map.entry("maintenance.repair.transfer-prepared.v1", "REPAIR_TRANSFER_PREPARED"),
        Map.entry("maintenance.repair.transferred.v1", "REPAIR_TRANSFERRED"),
        Map.entry("maintenance.repair.accepted.v1", "REPAIR_ACCEPTED"),
        Map.entry("maintenance.repair.written-off.v1", "REPAIR_WRITTEN_OFF")));

    Set<String> finding = Set.of("inventoryId", "findingId", "warehouseId", "sessionRevision", "findingRevision", "origin", "inspection", "reconciliation", "assetId", "sourceAttached", "mediaCount", "planFingerprintSha256");
    add(result, "rwms.inventory.session.v1", "FINDING", SubjectKind.INVENTORY_FINDING, finding, finding,
        "findingId", "INVENTORY_FINDING_ADDED", "inventory.finding.added.v1");
    add(result, "rwms.inventory.session.v1", "FINDING", SubjectKind.INVENTORY_FINDING, finding, finding,
        "findingId", "INVENTORY_INSPECTION_SAVED", "inventory.finding.inspection-saved.v1");
    Set<String> ownerProof = Set.of("ownerType", "ownerId", "warehouseId", "ownerRevision", "active");
    add(result, "rwms.inventory.session.v1", "FINDING", SubjectKind.INVENTORY_OWNER_PROOF,
        ownerProof, ownerProof, "ownerId", null, "inventory.finding.owner-proof.v1");
    Set<String> session = Set.of("inventoryId", "warehouseId", "sessionRevision", "lifecycle", "businessDate", "expectedCount", "findingCount", "terminalAt", "statistics");
    for (String event : Set.of("inventory.session.started.v1", "inventory.session.completed.v1", "inventory.session.cancelled.v1")) {
      add(result, "rwms.inventory.session.v1", "SESSION", SubjectKind.NONE, session, session, "inventoryId", null, event);
    }
    Set<String> publication = Set.of("inventoryId", "findingId", "publicationIntentId", "warehouseId", "publicationRevision", "state", "attemptCount", "maintenanceRepairId", "failureCode", "sourceReference");
    Map<String, String> publicationCodes = Map.ofEntries(
        Map.entry("inventory.publication.ready.v1", "INVENTORY_PUBLICATION_READY"),
        Map.entry("inventory.publication.requested.v1", "INVENTORY_PUBLICATION_REQUESTED"),
        Map.entry("inventory.publication.succeeded.v1", "INVENTORY_PUBLICATION_SUCCEEDED"),
        Map.entry("inventory.publication.transient-failed.v1", "INVENTORY_PUBLICATION_TRANSIENT_FAILED"),
        Map.entry("inventory.publication.blocked.v1", "INVENTORY_PUBLICATION_BLOCKED"),
        Map.entry("inventory.publication.closed-blocked.v1", "INVENTORY_PUBLICATION_CLOSED_BLOCKED"));
    publicationCodes.forEach((event, code) -> add(result, "rwms.inventory.publication.v1", "PUBLICATION", SubjectKind.INVENTORY_PUBLICATION, publication, publication, "publicationIntentId", code, event));

    Set<String> requiredMedia = Set.of("mediaId", "ownerType", "ownerId", "warehouseId", "kind", "status", "generation", "rotationDegrees");
    Set<String> media = new HashSet<>(requiredMedia);
    media.add("folderId");
    Map<String, String> mediaCodes = Map.of(
        "media.media.ready.v1", "MEDIA_READY",
        "media.media.failed.v1", "MEDIA_FAILED",
        "media.media.rotated.v1", "MEDIA_ROTATED",
        "media.media.deleted.v1", "MEDIA_DELETED");
    mediaCodes.forEach((event, code) -> add(result, "rwms.media.media.v1", "MEDIA", SubjectKind.MEDIA, media, requiredMedia, "mediaId", code, event));
    add(result, "rwms.media.media.v1", "MEDIA", SubjectKind.MEDIA, media, requiredMedia, "mediaId", null, "media.media.uploaded.v1");

    Set<String> logistics = Set.of("documentId", "documentType", "state", "warehouseId", "destinationWarehouseId", "lineCount", "resultCode");
    String[] families = {"return", "shipment", "transfer"};
    for (String family : families) {
      String topic = "rwms.logistics." + family + ".v1";
      String aggregate = family.toUpperCase(java.util.Locale.ROOT);
      for (String event : logisticsEvents(family)) {
        add(result, topic, aggregate, SubjectKind.NONE, logistics, logistics, "documentId", null, event);
      }
    }

    Set<String> boardTask = Set.of("boardTaskId", "warehouseId", "externalTaskId", "status", "scheduledDate", "lane", "priority", "pinned", "driverAudience", "plannedDriverWorkerId", "plannedDurationMinutes", "deadlineAt", "doneAt", "deleted");
    Set<String> requiredBoardTask = Set.of("boardTaskId", "warehouseId", "externalTaskId", "status", "lane", "plannedDurationMinutes", "deadlineAt", "doneAt", "deleted");
    for (String suffix : Set.of("created", "changed", "completed", "cancelled")) {
      add(result, "rwms.task-board.board-task.v1", "BOARD_TASK", SubjectKind.NONE, boardTask, requiredBoardTask, "boardTaskId", null, "task-board.board-task." + suffix + ".v1");
    }
    Set<String> queueEntry = Set.of("queueEntryId", "taskId", "queueId", "routeIndex", "queuePosition", "entryType", "status", "plannedDurationMinutes", "activeStartedAt", "pausedAt", "doneAt", "activeWorkSeconds", "originalBudgetSeconds", "currentBudgetSeconds", "pauseOrigin", "assignments", "timeEvents", "interruptions", "deleted");
    for (String suffix : Set.of("created", "changed", "taken", "paused", "resumed", "completed", "moved", "cancelled", "interrupted", "returning")) {
      add(result, "rwms.task-board.queue-entry.v1", "QUEUE_ENTRY", SubjectKind.NONE, queueEntry, queueEntry, "queueEntryId", null, "task-board.queue-entry." + suffix + ".v1");
    }
    return Map.copyOf(result);
  }

  private static Set<String> logisticsEvents(String family) {
    return switch (family) {
      case "return" -> Set.of("logistics.return.created.v1", "logistics.return.registration-started.v1", "logistics.return.inspection-required.v1", "logistics.return.acceptance-started.v1", "logistics.return.accepted.v1", "logistics.return.estimate-started.v1", "logistics.return.estimate-requested.v1", "logistics.return.conflicted.v1", "logistics.return.conflict.v1", "logistics.return.reconciliation-required.v1");
      case "shipment" -> Set.of("logistics.shipment.created.v1", "logistics.shipment.draft-updated.v1", "logistics.shipment.preparation-started.v1", "logistics.shipment.planned.v1", "logistics.shipment.confirmation-started.v1", "logistics.shipment.preparation-confirmed.v1", "logistics.shipment.cancellation-started.v1", "logistics.shipment.cancelled.v1", "logistics.shipment.conflict.v1", "logistics.shipment.reconciliation-required.v1");
      case "transfer" -> Set.of("logistics.transfer.created.v1", "logistics.transfer.departure-started.v1", "logistics.transfer.departed.v1", "logistics.transfer.arrival-started.v1", "logistics.transfer.line-arrived.v1", "logistics.transfer.completed.v1", "logistics.transfer.cancelled.v1", "logistics.transfer.conflict.v1", "logistics.transfer.reconciliation-required.v1");
      default -> throw new IllegalArgumentException("Unsupported family");
    };
  }

  /**
   * Registers strict maintenance v1 policies while accepting facts created before the additive
   * manual capital-repair choice. New producers still include {@code forceCapitalRepair}; omission
   * in a historical fact has the compatibility meaning {@code false}.
   */
  private static void addMaintenance(
      Map<String, EventPolicy> result,
      String aggregate,
      Set<String> fields,
      String identity,
      Map<String, String> codes) {
    String topic = "rwms.maintenance." + aggregate.toLowerCase(java.util.Locale.ROOT) + ".v1";
    Set<String> required = new HashSet<>(fields);
    required.remove("forceCapitalRepair");
    Set<String> immutableRequired = Set.copyOf(required);
    codes.forEach(
        (event, code) ->
            add(
                result,
                topic,
                aggregate,
                SubjectKind.MAINTENANCE,
                fields,
                immutableRequired,
                identity,
                code,
                event));
  }

  private static void add(Map<String, EventPolicy> result, String topic, String aggregate, SubjectKind subject, Set<String> fields, Set<String> required, String identity, String code, String event) {
    result.put(event, new EventPolicy(topic, aggregate, subject, fields, required, java.util.Optional.ofNullable(identity), code));
  }

  private static void requireExactObject(JsonNode node, Set<String> fields) {
    require(node != null && node.isObject());
    Set<String> names = new HashSet<>();
    names.addAll(node.propertyNames());
    require(names.equals(fields));
  }

  private static void requireObjectFields(
      JsonNode node, Set<String> allowedFields, Set<String> requiredFields) {
    require(node != null && node.isObject());
    Set<String> names = new HashSet<>();
    names.addAll(node.propertyNames());
    require(allowedFields.containsAll(names) && names.containsAll(requiredFields));
  }

  private static void rejectProhibited(JsonNode node) {
    if (node.isObject()) {
      node.properties().forEach(entry -> {
        require(!PROHIBITED_FIELDS.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT)));
        rejectProhibited(entry.getValue());
      });
    } else if (node.isArray()) {
      node.forEach(DossierEnvelopeValidator::rejectProhibited);
    } else if (node.isTextual()) {
      require(!DomainEventEnvelopeV2.containsSensitiveTechnicalValue(node.stringValue()));
    }
  }

  private String canonicalJson(JsonNode value) {
    return mapper
        .writer()
        .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .writeValueAsString(mapper.convertValue(value, Object.class));
  }

  private static UUID uuid(JsonNode node, String field, boolean nullable) {
    JsonNode value = node.required(field);
    if (value.isNull() && nullable) return null;
    require(value.isTextual());
    try {
      return UUID.fromString(value.stringValue());
    } catch (IllegalArgumentException exception) {
      throw invalid("SOURCE_SCHEMA_REJECTED", exception);
    }
  }

  private static UUID optionalUuid(JsonNode node, String field) {
    return node.has(field) ? uuid(node, field, true) : null;
  }

  private static String normalizeKafkaKey(Object value) {
    if (value instanceof byte[] bytes) {
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
    if (value instanceof String text) return text;
    if (value instanceof UUID uuid) return uuid.toString();
    throw invalid("SOURCE_RECORD_KEY_MISMATCH");
  }

  private static String text(JsonNode node, String field) {
    String value = nullableText(node, field);
    require(value != null && !value.isBlank() && value.length() <= 256);
    return value;
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.required(field);
    if (value.isNull()) return null;
    require(value.isTextual());
    return value.stringValue();
  }

  private static int integer(JsonNode node, String field, int minimum) {
    JsonNode value = node.required(field);
    require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= minimum);
    return value.intValue();
  }

  private static long number(JsonNode node, String field, long minimum) {
    JsonNode value = node.required(field);
    require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= minimum);
    return value.longValue();
  }

  private static Instant instant(JsonNode node, String field, boolean nullable) {
    String value = nullableText(node, field);
    if (value == null && nullable) return null;
    require(value != null);
    try {
      return Instant.parse(value);
    } catch (RuntimeException exception) {
      throw invalid("SOURCE_SCHEMA_REJECTED", exception);
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw invalid("SOURCE_SCHEMA_REJECTED");
  }

  private static DossierValidationException invalid(String code) {
    return new DossierValidationException(code);
  }

  private static DossierValidationException invalid(String code, Throwable cause) {
    return new DossierValidationException(code, cause);
  }

  private record EventPolicy(
      String topic,
      String aggregateType,
      SubjectKind subjectKind,
      Set<String> payloadFields,
      Set<String> requiredFields,
      java.util.Optional<String> identityField,
      String activityCode) {}

  private record Subject(UUID cabinId, UUID warehouseId, UUID secondaryId) {}

  private enum SubjectKind {
    ASSET,
    MAINTENANCE,
    INVENTORY_FINDING,
    INVENTORY_PUBLICATION,
    INVENTORY_OWNER_PROOF,
    MEDIA,
    NONE
  }
}
