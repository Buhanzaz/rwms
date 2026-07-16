package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.BoardTaskFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueEntryFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueUsageReferenceFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkQueueFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerClassFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerGroupFact;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TaskBoardEventPayloadPolicy {
  private static final Set<String> FORBIDDEN_FIELDS = Set.of(
      "firstname", "lastname", "middlename", "displayname", "applogin", "credentialerror",
      "credentialoperationid", "credentialoperationtype", "credentialoperationstartedat",
      "workername", "workernamesnapshot", "groupnamesnapshot", "password", "passwordhash",
      "token", "secret", "email", "phone", "name", "description", "comment", "title",
      "unitnumber", "tasktext", "roleingroup", "requestfingerprint", "reason",
      "externalreferenceid");
  private static final Map<TaskBoardAggregateType, Class<?>> PAYLOAD_TYPES = Map.of(
      TaskBoardAggregateType.WORKER_CLASS, WorkerClassFact.class,
      TaskBoardAggregateType.WORKER, WorkerFact.class,
      TaskBoardAggregateType.WORKER_GROUP, WorkerGroupFact.class,
      TaskBoardAggregateType.WORK_QUEUE, WorkQueueFact.class,
      TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, QueueUsageReferenceFact.class,
      TaskBoardAggregateType.BOARD_TASK, BoardTaskFact.class,
      TaskBoardAggregateType.QUEUE_ENTRY, QueueEntryFact.class);
  private static final Map<TaskBoardAggregateType, String> ID_FIELDS = Map.of(
      TaskBoardAggregateType.WORKER_CLASS, "workerClassId",
      TaskBoardAggregateType.WORKER, "workerId",
      TaskBoardAggregateType.WORKER_GROUP, "workerGroupId",
      TaskBoardAggregateType.WORK_QUEUE, "workQueueId",
      TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, "queueUsageReferenceId",
      TaskBoardAggregateType.BOARD_TASK, "boardTaskId",
      TaskBoardAggregateType.QUEUE_ENTRY, "queueEntryId");

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
      strictObjectMapper.readerFor(PAYLOAD_TYPES.get(aggregateType)).readValue(payload);
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
