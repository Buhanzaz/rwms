package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.CatalogVersionFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.EstimateFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.RepairFact;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Rejects schema drift, aggregate mismatches and unsafe data before a fact reaches Kafka. */
@Component
public class MaintenanceEventPayloadPolicy {
  private static final Map<String, MaintenanceEventType> EVENT_TYPES = Arrays.stream(MaintenanceEventType.values())
      .collect(Collectors.toUnmodifiableMap(MaintenanceEventType::value, Function.identity()));
  private static final Set<String> CATALOG_FIELDS = Set.of(
      "catalogVersionId",
      "warehouseId",
      "lifecycle",
      "sourceSha256",
      "nodeCount",
      "linkCount",
      "validationReportSha256");
  private static final Set<String> ESTIMATE_FIELDS = Set.of(
      "estimateId",
      "warehouseId",
      "rentalItemId",
      "lifecycle",
      "revision",
      "dispatchDate",
      "lineCount",
      "completionKind",
      "repairId");
  private static final Set<String> REPAIR_FIELDS = Set.of(
      "repairId",
      "rootRepairId",
      "sourceRepairId",
      "estimateId",
      "warehouseId",
      "rentalItemId",
      "origin",
      "kind",
      "executionState",
      "acceptanceState",
      "dispatchDate",
      "priority",
      "stages");
  private static final Set<String> REPAIR_STAGE_FIELDS = Set.of(
      "stageId", "kind", "order", "state", "queueId", "taskSync");
  private static final Set<String> TASK_SYNC_FIELDS = Set.of(
      "externalTaskId", "taskBoardRegistrationVersion", "generationState", "deliveryState");
  private static final Map<MaintenanceAggregateType, Class<?>> FACT_TYPES = Map.of(
      MaintenanceAggregateType.CATALOG_VERSION, CatalogVersionFact.class,
      MaintenanceAggregateType.ESTIMATE, EstimateFact.class,
      MaintenanceAggregateType.REPAIR, RepairFact.class);
  private static final Map<MaintenanceAggregateType, String> ID_FIELDS = Map.of(
      MaintenanceAggregateType.CATALOG_VERSION, "catalogVersionId",
      MaintenanceAggregateType.ESTIMATE, "estimateId",
      MaintenanceAggregateType.REPAIR, "repairId");
  private static final Set<String> FORBIDDEN_FIELDS = Set.of(
      "actor",
      "authorization",
      "comment",
      "credential",
      "decisionreason",
      "displayname",
      "email",
      "filename",
      "jwt",
      "login",
      "media",
      "mediareference",
      "mediaurl",
      "name",
      "objectkey",
      "objectpath",
      "password",
      "phone",
      "reason",
      "reworkreason",
      "secret",
      "signedurl",
      "sourceparty",
      "token",
      "uri",
      "url");

  private final ObjectMapper mapper;
  private final ObjectMapper strictMapper;

  public MaintenanceEventPayloadPolicy(ObjectMapper mapper) {
    this.mapper = mapper;
    this.strictMapper = mapper.rebuild()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }

  public JsonNode validateAndConvert(
      MaintenanceEventType eventType,
      MaintenanceAggregateType aggregateType,
      UUID aggregateId,
      Object payload) {
    requireEventType(eventType, aggregateType);
    if (!FACT_TYPES.get(aggregateType).isInstance(payload)) {
      throw new IllegalArgumentException("Maintenance event requires its exact typed integration fact");
    }
    JsonNode node = mapper.valueToTree(payload);
    validateNode(eventType.value(), aggregateType, aggregateId, node);
    return node;
  }

  public void validateNode(
      String eventType,
      MaintenanceAggregateType aggregateType,
      UUID aggregateId,
      JsonNode payload) {
    MaintenanceEventType type = requireEventType(eventType, aggregateType);
    if (aggregateId == null || payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("Maintenance aggregate identity and object payload are required");
    }
    requireExactShape(aggregateType, payload);
    validateSensitiveValues(payload);
    JsonNode identity = payload.get(ID_FIELDS.get(aggregateType));
    if (identity == null
        || !identity.isTextual()
        || !aggregateId.toString().equals(identity.stringValue())) {
      throw new IllegalArgumentException("Maintenance event payload aggregate identity mismatch");
    }
    try {
      strictMapper.readerFor(FACT_TYPES.get(aggregateType)).readValue(payload);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "Maintenance event payload failed typed semantic validation for " + type.value(), exception);
    }
  }

  public MaintenanceAggregateType aggregateFor(MaintenanceEventType eventType) {
    return switch (eventType) {
      case CATALOG_IMPORTED, CATALOG_CHANGED, CATALOG_ACTIVATED, CATALOG_SUPERSEDED ->
          MaintenanceAggregateType.CATALOG_VERSION;
      case ESTIMATE_CREATED, ESTIMATE_DRAFT_CHANGED, ESTIMATE_COMPLETED, ESTIMATE_AMENDED ->
          MaintenanceAggregateType.ESTIMATE;
      case REPAIR_CREATED,
          REPAIR_PLAN_CHANGED,
          REPAIR_QUEUED,
          REPAIR_STAGE_COMPLETED,
          REPAIR_PENDING_ACCEPTANCE,
          REPAIR_REWORK_CREATED,
          REPAIR_TRANSFER_PREPARED,
          REPAIR_TRANSFERRED,
          REPAIR_ACCEPTED,
          REPAIR_WRITTEN_OFF -> MaintenanceAggregateType.REPAIR;
    };
  }

  private MaintenanceEventType requireEventType(
      String eventType, MaintenanceAggregateType aggregateType) {
    MaintenanceEventType type = EVENT_TYPES.get(eventType);
    if (type == null) {
      throw new IllegalArgumentException("Unsupported maintenance event type");
    }
    requireEventType(type, aggregateType);
    return type;
  }

  private void requireEventType(
      MaintenanceEventType eventType, MaintenanceAggregateType aggregateType) {
    if (eventType == null || aggregateType == null || aggregateFor(eventType) != aggregateType) {
      throw new IllegalArgumentException("Maintenance event type does not match aggregate family");
    }
  }

  private static void requireExactShape(
      MaintenanceAggregateType aggregateType, JsonNode payload) {
    switch (aggregateType) {
      case CATALOG_VERSION -> requireExactFields(payload, CATALOG_FIELDS);
      case ESTIMATE -> requireExactFields(payload, ESTIMATE_FIELDS);
      case REPAIR -> {
        requireExactFields(payload, REPAIR_FIELDS);
        JsonNode stages = payload.get("stages");
        if (stages == null || !stages.isArray()) {
          throw new IllegalArgumentException("Maintenance repair stages must be an array");
        }
        stages.forEach(stage -> {
          requireExactFields(stage, REPAIR_STAGE_FIELDS);
          requireExactFields(stage.get("taskSync"), TASK_SYNC_FIELDS);
        });
      }
    }
  }

  private static void requireExactFields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Maintenance event payload must be an object");
    }
    Set<String> actual = new HashSet<>();
    node.properties().forEach(entry -> actual.add(entry.getKey()));
    if (!actual.equals(expected)) {
      Set<String> missing = new HashSet<>(expected);
      missing.removeAll(actual);
      Set<String> unexpected = new HashSet<>(actual);
      unexpected.removeAll(expected);
      Map<String, Set<String>> mismatch = new HashMap<>();
      mismatch.put("missing", Set.copyOf(missing));
      mismatch.put("unexpected", Set.copyOf(unexpected));
      throw new IllegalArgumentException("Maintenance event payload does not match its exact schema: " + mismatch);
    }
  }

  private static void validateSensitiveValues(JsonNode node) {
    if (node.isObject()) {
      node.properties().forEach(entry -> {
        if (FORBIDDEN_FIELDS.contains(normalize(entry.getKey()))) {
          throw new IllegalArgumentException(
              "Maintenance event payload contains a forbidden field: " + entry.getKey());
        }
        validateSensitiveValues(entry.getValue());
      });
      return;
    }
    if (node.isArray()) {
      node.forEach(MaintenanceEventPayloadPolicy::validateSensitiveValues);
      return;
    }
    if (node.isTextual()
        && DomainEventEnvelopeV2.containsSensitiveTechnicalValue(node.stringValue())) {
      throw new IllegalArgumentException("Maintenance event payload contains a sensitive technical value");
    }
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }
}
