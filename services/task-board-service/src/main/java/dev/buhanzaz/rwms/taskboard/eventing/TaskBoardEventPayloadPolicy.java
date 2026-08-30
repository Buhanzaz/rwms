package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.BoardTaskFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.DriverShiftOwnerProofFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.EntryOwnerProofFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.GroupKpiDayFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueEntryFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueUsageReferenceFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.TaskEvidenceFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkQueueFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerClassFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerGroupFact;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Enforces exact event-type fields and task-board data-minimization rules before publication/use. */
@Component
public class TaskBoardEventPayloadPolicy {
  private static final Set<String> FORBIDDEN_FIELDS = Set.of(
      "firstname", "lastname", "middlename", "displayname", "applogin", "credentialerror",
      "credentialoperationid", "credentialoperationtype", "credentialoperationstartedat",
      "workername", "workernamesnapshot", "groupnamesnapshot", "password", "passwordhash",
      "token", "secret", "email", "phone", "name", "description", "comment", "title",
      "unitnumber", "tasktext", "roleingroup", "requestfingerprint", "reason",
      "externalreferenceid");
  private static final Map<TaskBoardAggregateType, Class<?>> PAYLOAD_TYPES = Map.ofEntries(
      Map.entry(TaskBoardAggregateType.WORKER_CLASS, WorkerClassFact.class),
      Map.entry(TaskBoardAggregateType.WORKER, WorkerFact.class),
      Map.entry(TaskBoardAggregateType.WORKER_GROUP, WorkerGroupFact.class),
      Map.entry(TaskBoardAggregateType.WORK_QUEUE, WorkQueueFact.class),
      Map.entry(TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, QueueUsageReferenceFact.class),
      Map.entry(TaskBoardAggregateType.BOARD_TASK, BoardTaskFact.class),
      Map.entry(TaskBoardAggregateType.QUEUE_ENTRY, QueueEntryFact.class),
      Map.entry(TaskBoardAggregateType.TASK_BOARD_ENTRY_OWNER_PROOF, EntryOwnerProofFact.class),
      Map.entry(TaskBoardAggregateType.TASK_EVIDENCE, TaskEvidenceFact.class),
      Map.entry(TaskBoardAggregateType.GROUP_KPI_DAY, GroupKpiDayFact.class),
      Map.entry(TaskBoardAggregateType.DRIVER_SHIFT_OWNER_PROOF, DriverShiftOwnerProofFact.class));
  private static final Map<TaskBoardAggregateType, String> ID_FIELDS = Map.ofEntries(
      Map.entry(TaskBoardAggregateType.WORKER_CLASS, "workerClassId"),
      Map.entry(TaskBoardAggregateType.WORKER, "workerId"),
      Map.entry(TaskBoardAggregateType.WORKER_GROUP, "workerGroupId"),
      Map.entry(TaskBoardAggregateType.WORK_QUEUE, "workQueueId"),
      Map.entry(TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, "queueUsageReferenceId"),
      Map.entry(TaskBoardAggregateType.BOARD_TASK, "boardTaskId"),
      Map.entry(TaskBoardAggregateType.QUEUE_ENTRY, "queueEntryId"),
      Map.entry(TaskBoardAggregateType.TASK_BOARD_ENTRY_OWNER_PROOF, "ownerId"),
      Map.entry(TaskBoardAggregateType.TASK_EVIDENCE, "evidenceId"),
      Map.entry(TaskBoardAggregateType.GROUP_KPI_DAY, "evidenceId"),
      Map.entry(TaskBoardAggregateType.DRIVER_SHIFT_OWNER_PROOF, "ownerId"));
  private static final Map<Class<?>, Set<String>> OPTIONAL_COMPATIBILITY_FIELDS =
      Map.of(
          BoardTaskFact.class,
          Set.of("driverAudience", "plannedDriverWorkerId"),
          WorkQueueFact.class,
          Set.of("availableTaskLimit", "workerFeedEnabled"));

  private final ObjectMapper objectMapper;
  private final ObjectMapper strictObjectMapper;

  public TaskBoardEventPayloadPolicy(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.strictObjectMapper =
        objectMapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  public JsonNode validateAndConvert(
      String eventType, TaskBoardAggregateType aggregateType, UUID aggregateId, Object payload) {
    requireEventType(eventType, aggregateType);
    if (!PAYLOAD_TYPES.get(aggregateType).isInstance(payload)) {
      throw new IllegalArgumentException("Task-board event requires its exact safe payload");
    }
    JsonNode node = objectMapper.valueToTree(payload);
    validateNode(eventType, aggregateType, aggregateId, node);
    return node;
  }

  public void validateNode(
      String eventType, TaskBoardAggregateType aggregateType, UUID aggregateId, JsonNode payload) {
    requireEventType(eventType, aggregateType);
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("Task-board event payload must be an object");
    }
    validateSensitiveValues(payload);
    JsonNode identity = payload.get(ID_FIELDS.get(aggregateType));
    if (identity == null || !identity.isTextual() || !aggregateId.equals(UUID.fromString(identity.stringValue()))) {
      throw new IllegalArgumentException("Task-board event aggregate identity mismatch");
    }
    try {
      Class<?> payloadType = PAYLOAD_TYPES.get(aggregateType);
      requireCanonicalRecordShape(payloadType, payload);
      strictObjectMapper.readerFor(payloadType).readValue(payload);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Task-board event payload failed typed semantic validation", exception);
    }
  }

  public void requireEventType(String eventType, TaskBoardAggregateType aggregateType) {
    Set<String> supported = TaskBoardEventTypes.BY_AGGREGATE.get(aggregateType);
    if (supported == null || !supported.contains(eventType)) {
      throw new IllegalArgumentException("Task-board event type does not match aggregate family");
    }
  }

  /**
   * Requires the exact current fact shape except for fields explicitly omitted by immutable events
   * stored before a compatible additive contract change.
   */
  private static void requireCanonicalRecordShape(Class<?> recordType, JsonNode payload) {
    if (!recordType.isRecord() || !payload.isObject()) {
      throw new IllegalArgumentException("Task-board event payload must use a record object");
    }
    if (recordType == BoardTaskFact.class
        && payload.has("plannedDriverWorkerId")
        && !payload.has("driverAudience")) {
      throw new IllegalArgumentException(
          "Task-board planned driver event identity requires an audience mode");
    }
    if (recordType == WorkQueueFact.class) {
      boolean hasLimit = payload.has("availableTaskLimit");
      boolean hasSwitch = payload.has("workerFeedEnabled");
      if (hasLimit != hasSwitch
          || (hasLimit
              && (payload.get("availableTaskLimit").isNull()
                  || payload.get("workerFeedEnabled").isNull()))) {
        throw new IllegalArgumentException(
            "Task-board work-queue plan fields must both be present or both be absent");
      }
    }
    Set<String> compatibilityFields =
        OPTIONAL_COMPATIBILITY_FIELDS.getOrDefault(recordType, Set.of());
    for (var component : recordType.getRecordComponents()) {
      if (!payload.has(component.getName())) {
        if (compatibilityFields.contains(component.getName())) {
          continue;
        }
        throw new IllegalArgumentException(
            "Task-board event payload misses canonical field " + component.getName());
      }
      JsonNode value = payload.get(component.getName());
      if (value == null || value.isNull()) {
        continue;
      }
      if (component.getType().isRecord()) {
        requireCanonicalRecordShape(component.getType(), value);
        continue;
      }
      if (List.class.isAssignableFrom(component.getType())
          && component.getGenericType() instanceof ParameterizedType parameterized
          && parameterized.getActualTypeArguments()[0] instanceof Class<?> itemType
          && itemType.isRecord()) {
        if (!value.isArray()) {
          throw new IllegalArgumentException(
              "Task-board event payload field " + component.getName() + " must be an array");
        }
        value.forEach(item -> requireCanonicalRecordShape(itemType, item));
      }
    }
  }

  private static void validateSensitiveValues(JsonNode node) {
    if (node.isObject()) {
      node.properties().forEach(entry -> {
        if (FORBIDDEN_FIELDS.contains(normalize(entry.getKey()))) {
          throw new IllegalArgumentException("Task-board event payload contains a forbidden field");
        }
        validateSensitiveValues(entry.getValue());
      });
      return;
    }
    if (node.isArray()) {
      node.forEach(TaskBoardEventPayloadPolicy::validateSensitiveValues);
      return;
    }
    if (node.isTextual() && DomainEventEnvelopeV2.containsSensitiveTechnicalValue(node.stringValue())) {
      throw new IllegalArgumentException("Task-board event payload contains a sensitive technical value");
    }
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }
}
