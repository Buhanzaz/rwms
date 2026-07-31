package dev.buhanzaz.rwms.taskboard.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.BoardTaskFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueEntryFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.QueueBindingFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkQueueFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerGroupFact;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TaskBoardEventPayloadPolicyTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TaskBoardEventPayloadPolicy policy = new TaskBoardEventPayloadPolicy(objectMapper);

  @Test
  void acceptsAndSerializesApprovedTaskSchedulingFacts() {
    UUID taskId = UUID.randomUUID();
    LocalDate scheduledDate = LocalDate.of(2026, 7, 24);
    var fact =
        new BoardTaskFact(
            taskId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            TaskStatus.ACTIVE,
            scheduledDate,
            TaskLane.SCHEDULED,
            1,
            true,
            null,
            null,
            null,
            false);

    assertThatCode(
            () ->
                policy.validateAndConvert(
                    TaskBoardEventTypes.BOARD_TASK_CHANGED,
                    TaskBoardAggregateType.BOARD_TASK,
                    taskId,
                    fact))
        .doesNotThrowAnyException();

    var payload = objectMapper.valueToTree(fact);
    assertThat(payload.required("scheduledDate").stringValue()).isEqualTo("2026-07-24");
    assertThat(payload.required("priority").intValue()).isOne();
    assertThat(payload.required("pinned").booleanValue()).isTrue();
  }

  @Test
  void rejectsTaskSchedulingFactsOutsideThePriorityRange() {
    assertThatThrownBy(
            () ->
                new BoardTaskFact(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    TaskStatus.ACTIVE,
                    LocalDate.of(2026, 7, 24),
                    TaskLane.SCHEDULED,
                    0,
                    false,
                    null,
                    null,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priority");
  }

  @Test
  void rejectsTaskFactsWithoutCanonicalSchedulingFields() {
    UUID taskId = UUID.randomUUID();
    var incomplete = incompleteTaskFact(taskId);

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.BOARD_TASK_CHANGED,
                    TaskBoardAggregateType.BOARD_TASK,
                    taskId,
                    incomplete))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");
    assertThat(incomplete.has("scheduledDate")).isFalse();
    assertThat(incomplete.has("priority")).isFalse();
    assertThat(incomplete.has("pinned")).isFalse();

    UUID taskWithoutDeadlineId = UUID.randomUUID();
    var incompleteWithoutDeadline = incompleteTaskFact(taskWithoutDeadlineId);
    incompleteWithoutDeadline.putNull("deadlineAt");
    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.BOARD_TASK_CHANGED,
                    TaskBoardAggregateType.BOARD_TASK,
                    taskWithoutDeadlineId,
                    incompleteWithoutDeadline))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");
  }

  @Test
  void rejectsInvalidSchedulingValuesWhenTheyArePresent() {
    UUID taskId = UUID.randomUUID();
    var badPriority = incompleteTaskFact(taskId);
    badPriority.put("scheduledDate", "2026-07-24");
    badPriority.put("priority", 6);
    badPriority.put("pinned", false);

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.BOARD_TASK_CHANGED,
                    TaskBoardAggregateType.BOARD_TASK,
                    taskId,
                    badPriority))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");

    var badDate = incompleteTaskFact(taskId);
    badDate.put("scheduledDate", "24-07-2026");
    badDate.put("priority", 3);
    badDate.put("pinned", false);

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.BOARD_TASK_CHANGED,
                    TaskBoardAggregateType.BOARD_TASK,
                    taskId,
                    badDate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");
  }

  @Test
  void acceptsOnlyTheApprovedWorkerFactShape() {
    UUID workerId = UUID.randomUUID();

    assertThatCode(
            () ->
                policy.validateAndConvert(
                    TaskBoardEventTypes.WORKER_CHANGED,
                    TaskBoardAggregateType.WORKER,
                    workerId,
                    new WorkerFact(
                        workerId,
                        UUID.randomUUID(),
                        true,
                        UUID.randomUUID(),
                        List.of(),
                        false)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWorkerAndGroupFactsWithoutCurrentCanonicalState() {
    UUID workerId = UUID.randomUUID();
    var worker =
        objectMapper.valueToTree(
            new WorkerFact(
                workerId,
                UUID.randomUUID(),
                true,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(),
                false));
    ((tools.jackson.databind.node.ObjectNode) worker).remove("currentGroupId");

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.WORKER_CHANGED,
                    TaskBoardAggregateType.WORKER,
                    workerId,
                    worker))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");

    UUID groupId = UUID.randomUUID();
    var group =
        objectMapper.valueToTree(
            new WorkerGroupFact(
                groupId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                true,
                GroupOperationalStatus.AVAILABLE,
                List.of(),
                false));
    ((tools.jackson.databind.node.ObjectNode) group).remove("operationalStatus");

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.WORKER_GROUP_CHANGED,
                    TaskBoardAggregateType.WORKER_GROUP,
                    groupId,
                    group))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");
  }

  @Test
  void rejectsQueueEntryFactsWithoutCanonicalBudgetFields() {
    UUID entryId = UUID.randomUUID();
    var entry =
        objectMapper.valueToTree(
            new QueueEntryFact(
                entryId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                0,
                EntryType.REAL,
                EntryStatus.WAITING,
                30,
                null,
                null,
                null,
                0,
                1800L,
                1800L,
                null,
                List.of(),
                List.of(),
                List.of(),
                false));
    ((tools.jackson.databind.node.ObjectNode) entry).remove("originalBudgetSeconds");

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.QUEUE_ENTRY_CHANGED,
                    TaskBoardAggregateType.QUEUE_ENTRY,
                    entryId,
                    entry))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");
  }

  @Test
  void serializesOrderedQueueClassBindingsWithoutRemovedGroupBindings() {
    UUID queueId = UUID.randomUUID();
    UUID bindingId = UUID.randomUUID();
    var fact =
        new WorkQueueFact(
            queueId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            QueueType.MOVEMENT,
            QueuePurpose.GENERAL,
            0,
            true,
            false,
            false,
            null,
            null,
            false,
            1,
            List.of(
                new QueueBindingFact(
                    bindingId,
                    2,
                    UUID.randomUUID(),
                    1,
                    true,
                    ParticipationPolicy.REQUIRED,
                    true)),
            false);

    var payload =
        policy.validateAndConvert(
            TaskBoardEventTypes.WORK_QUEUE_CHANGED,
            TaskBoardAggregateType.WORK_QUEUE,
            queueId,
            fact);

    assertThat(payload.has("groupBindings")).isFalse();
    assertThat(payload.required("classBindings").get(0).required("bindingOrder").intValue())
        .isOne();
    assertThat(
            payload
                .required("classBindings")
                .get(0)
                .required("notifyOnPrimaryTake")
                .booleanValue())
        .isTrue();

    var missingQueueDefinition = payload.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) missingQueueDefinition).remove("queueDefinitionId");
    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.WORK_QUEUE_CHANGED,
                    TaskBoardAggregateType.WORK_QUEUE,
                    queueId,
                    missingQueueDefinition))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");

    var missingBindingOrder = payload.deepCopy();
    ((tools.jackson.databind.node.ObjectNode)
            missingBindingOrder.required("classBindings").get(0))
        .remove("bindingOrder");
    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.WORK_QUEUE_CHANGED,
                    TaskBoardAggregateType.WORK_QUEUE,
                    queueId,
                    missingBindingOrder))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");
  }

  @Test
  void rejectsPiiAndTechnicalCredentialCanariesBeforePersistence() {
    UUID workerId = UUID.randomUUID();
    var malicious = objectMapper.createObjectNode();
    malicious.put("workerId", workerId.toString());
    malicious.put("warehouseId", UUID.randomUUID().toString());
    malicious.put("active", true);
    malicious.put("profileRevision", UUID.randomUUID().toString());
    malicious.putArray("qualifications");
    malicious.put("deleted", false);
    malicious.put("password", "pii-canary-password");

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.WORKER_CHANGED,
                    TaskBoardAggregateType.WORKER,
                    workerId,
                    malicious))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden field");
  }

  @Test
  void rejectsHumanFreeTextBeforeItCanReachTheEventStoreOrDlt() {
    UUID taskId = UUID.randomUUID();
    var malicious = objectMapper.createObjectNode();
    malicious.put("boardTaskId", taskId.toString());
    malicious.put("warehouseId", UUID.randomUUID().toString());
    malicious.put("externalTaskId", UUID.randomUUID().toString());
    malicious.put("status", "WAITING");
    malicious.putNull("plannedDurationMinutes");
    malicious.putNull("deadlineAt");
    malicious.putNull("doneAt");
    malicious.put("deleted", false);
    malicious.put("title", "Персональные данные оператора");

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.BOARD_TASK_CHANGED,
                    TaskBoardAggregateType.BOARD_TASK,
                    taskId,
                    malicious))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden field");
  }

  @Test
  void rejectsCrossFamilyEventTypesAndAggregateIdentityTampering() {
    UUID workerId = UUID.randomUUID();
    tools.jackson.databind.JsonNode payload = objectMapper.valueToTree(
        new WorkerFact(
            workerId, UUID.randomUUID(), true, UUID.randomUUID(), List.of(), false));

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.WORKER_CLASS_CHANGED,
                    TaskBoardAggregateType.WORKER,
                    workerId,
                    payload))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                policy.validateNode(
                    TaskBoardEventTypes.WORKER_CHANGED,
                    TaskBoardAggregateType.WORKER,
                    UUID.randomUUID(),
                    payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity mismatch");
  }

  private tools.jackson.databind.node.ObjectNode incompleteTaskFact(UUID taskId) {
    var payload = objectMapper.createObjectNode();
    payload.put("boardTaskId", taskId.toString());
    payload.put("warehouseId", UUID.randomUUID().toString());
    payload.put("externalTaskId", UUID.randomUUID().toString());
    payload.put("status", "ACTIVE");
    payload.putNull("plannedDurationMinutes");
    payload.put("deadlineAt", "2026-07-24T23:30:00+03:00");
    payload.putNull("doneAt");
    payload.put("deleted", false);
    return payload;
  }
}
