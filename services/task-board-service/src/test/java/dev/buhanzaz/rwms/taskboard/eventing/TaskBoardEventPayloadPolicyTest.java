package dev.buhanzaz.rwms.taskboard.eventing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerFact;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TaskBoardEventPayloadPolicyTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TaskBoardEventPayloadPolicy policy = new TaskBoardEventPayloadPolicy(objectMapper);

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
}
