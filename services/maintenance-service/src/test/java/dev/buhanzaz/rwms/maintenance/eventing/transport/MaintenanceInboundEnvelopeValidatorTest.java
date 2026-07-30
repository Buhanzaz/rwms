package dev.buhanzaz.rwms.maintenance.eventing.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MaintenanceInboundEnvelopeValidatorTest {
  private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
  private final MaintenanceInboundEnvelopeValidator validator =
      new MaintenanceInboundEnvelopeValidator(mapper);

  @Test
  void acceptsCurrentCanonicalBoardTaskFactsButOnlyTerminalFactsAreActionable() {
    UUID aggregateId = UUID.randomUUID();

    var created =
        validator.validate(
            MaintenanceTransportTopics.BOARD_TASK,
            key(aggregateId),
            boardTaskEnvelope(aggregateId, 0, "task-board.board-task.created.v1"));
    var completed =
        validator.validate(
            MaintenanceTransportTopics.BOARD_TASK,
            key(aggregateId),
            boardTaskEnvelope(aggregateId, 1, "task-board.board-task.completed.v1"));

    assertThat(created.actionable()).isFalse();
    assertThat(created.payload().required("scheduledDate").stringValue()).isEqualTo("2026-07-17");
    assertThat(created.payload().required("priority").intValue()).isEqualTo(3);
    assertThat(created.payload().required("pinned").booleanValue()).isFalse();
    assertThat(completed.actionable()).isTrue();
    assertThat(completed.aggregateId()).isEqualTo(aggregateId.toString());

    var historical = mapper.readTree(boardTaskEnvelope(aggregateId, 2, "task-board.board-task.created.v1"));
    ((tools.jackson.databind.node.ObjectNode) historical.required("payload"))
        .remove(java.util.List.of("scheduledDate", "priority", "pinned"));
    assertThat(
            validator.validate(
                MaintenanceTransportTopics.BOARD_TASK,
                key(aggregateId),
                mapper.writeValueAsBytes(historical)))
        .isNotNull();
  }

  @Test
  void rejectsWrongKafkaKeyTopicTupleAdditionalFieldsAndNonCanonicalUuid() throws Exception {
    UUID aggregateId = UUID.randomUUID();
    byte[] valid = boardTaskEnvelope(aggregateId, 0, "task-board.board-task.completed.v1");

    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.BOARD_TASK, key(UUID.randomUUID()), valid))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("Kafka key");
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.QUEUE_ENTRY, key(aggregateId), valid))
        .isInstanceOf(MaintenanceInboundValidationException.class);

    var unknown = mapper.readTree(valid).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) unknown.required("payload")).put("unexpected", true);
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.BOARD_TASK,
                    key(aggregateId),
                    mapper.writeValueAsBytes(unknown)))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("fields do not match");

    var partialCurrentShape = mapper.readTree(valid).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) partialCurrentShape.required("payload"))
        .remove("pinned");
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.BOARD_TASK,
                    key(aggregateId),
                    mapper.writeValueAsBytes(partialCurrentShape)))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("fields do not match");

    var wrongTypes = mapper.readTree(valid).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) wrongTypes.required("payload")).put("pinned", "false");
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.BOARD_TASK,
                    key(aggregateId),
                    mapper.writeValueAsBytes(wrongTypes)))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("pinned must be boolean");

    var fractionalPriority = mapper.readTree(valid).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) fractionalPriority.required("payload"))
        .put("priority", 3.5);
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.BOARD_TASK,
                    key(aggregateId),
                    mapper.writeValueAsBytes(fractionalPriority)))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("priority must be a 32-bit integer");

    var invalidDate = mapper.readTree(valid).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) invalidDate.required("payload"))
        .put("scheduledDate", "2026-02-30");
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.BOARD_TASK,
                    key(aggregateId),
                    mapper.writeValueAsBytes(invalidDate)))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("scheduledDate must be a canonical ISO local date");

    var nullableDate = mapper.readTree(valid).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) nullableDate.required("payload")).putNull("scheduledDate");
    assertThat(
            validator.validate(
                MaintenanceTransportTopics.BOARD_TASK,
                key(aggregateId),
                mapper.writeValueAsBytes(nullableDate)))
        .isNotNull();

    var extra = mapper.readTree(valid).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) extra.required("payload")).put("comment", "secret");
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.BOARD_TASK,
                    key(aggregateId),
                    mapper.writeValueAsBytes(extra)))
        .isInstanceOf(MaintenanceInboundValidationException.class);

    String shortened = new String(valid, StandardCharsets.UTF_8).replace(aggregateId.toString(), "1-1-1-1-1");
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.BOARD_TASK,
                    "1-1-1-1-1".getBytes(StandardCharsets.UTF_8),
                    shortened.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("canonical");
  }

  @Test
  void excludesProcessingRequestsFromTheMediaFactTopic() {
    UUID aggregateId = UUID.randomUUID();
    byte[] envelope = mediaEnvelope(aggregateId, "media.processing.request.v1");

    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.MEDIA, key(aggregateId), envelope))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("Event type");
  }

  @Test
  void acceptsServerAttributedTaskEvidenceAndRejectsIncompleteSourceIdentity() throws Exception {
    UUID evidenceId = UUID.randomUUID();
    byte[] valid = taskEvidenceEnvelope(evidenceId);

    var event =
        validator.validate(MaintenanceTransportTopics.TASK_EVIDENCE, key(evidenceId), valid);

    assertThat(event.actionable()).isTrue();
    assertThat(event.aggregateType()).isEqualTo("TASK_EVIDENCE");

    var incomplete = mapper.readTree(valid).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) incomplete.required("payload")).putNull("sourceId");
    assertThatThrownBy(
            () ->
                validator.validate(
                    MaintenanceTransportTopics.TASK_EVIDENCE,
                    key(evidenceId),
                    mapper.writeValueAsBytes(incomplete)))
        .isInstanceOf(MaintenanceInboundValidationException.class)
        .hasMessageContaining("source identity");
  }

  private byte[] boardTaskEnvelope(UUID aggregateId, long version, String eventType) {
    UUID externalTaskId = UUID.randomUUID();
    return json(
        """
        {
          "envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,
          "occurredAt":"2026-07-17T00:00:00Z","recordedAt":"2026-07-17T00:00:00Z",
          "producer":"task-board-service","aggregateType":"BOARD_TASK","aggregateId":"%s",
          "aggregateVersion":%d,"correlation":{"correlationId":"%s","causationId":null},
          "actorRef":null,"payload":{"boardTaskId":"%s","warehouseId":"%s",
          "externalTaskId":"%s","status":"%s","scheduledDate":"2026-07-17",
          "priority":3,"pinned":false,"plannedDurationMinutes":10,
          "deadlineAt":null,"doneAt":%s,"deleted":false}
        }
        """
            .formatted(
                UUID.randomUUID(),
                eventType,
                aggregateId,
                version,
                UUID.randomUUID(),
                aggregateId,
                UUID.randomUUID(),
                externalTaskId,
                eventType.endsWith(".completed.v1") ? "DONE" : "ACTIVE",
                eventType.endsWith(".completed.v1") ? "\"2026-07-17T00:00:00Z\"" : "null"));
  }

  private byte[] mediaEnvelope(UUID aggregateId, String eventType) {
    return json(
        """
        {
          "envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,
          "occurredAt":"2026-07-17T00:00:00Z","recordedAt":"2026-07-17T00:00:00Z",
          "producer":"media-service","aggregateType":"MEDIA","aggregateId":"%s",
          "aggregateVersion":0,"correlation":{"correlationId":"%s","causationId":null},
          "actorRef":null,"payload":{"mediaId":"%s","ownerType":"MAINTENANCE_REPAIR",
          "ownerId":"%s","warehouseId":"%s","folderId":"%s","kind":"IMAGE","status":"READY",
          "generation":0,"rotationDegrees":0}
        }
        """
            .formatted(
                UUID.randomUUID(),
                eventType,
                aggregateId,
                UUID.randomUUID(),
                aggregateId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()));
  }

  private byte[] taskEvidenceEnvelope(UUID evidenceId) {
    return json(
        """
        {
          "envelopeVersion":2,"eventId":"%s",
          "eventType":"task-board.task-evidence.ready.v1","eventVersion":1,
          "occurredAt":"2026-07-17T00:00:00Z","recordedAt":"2026-07-17T00:00:01Z",
          "producer":"task-board-service","aggregateType":"TASK_EVIDENCE",
          "aggregateId":"%s","aggregateVersion":1,
          "correlation":{"correlationId":"%s","causationId":null},"actorRef":null,
          "payload":{"evidenceId":"%s","entryId":"%s","taskId":"%s","routeIndex":0,
          "warehouseId":"%s","workerId":"%s","workerGroupId":null,"mediaId":"%s",
          "mediaGeneration":1,"capturedAt":"2026-07-17T00:00:00Z",
          "recordedAt":"2026-07-17T00:00:01Z","state":"READY",
          "sourceType":"MAINTENANCE_REPAIR","sourceId":"%s"}
        }
        """
            .formatted(
                UUID.randomUUID(),
                evidenceId,
                UUID.randomUUID(),
                evidenceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()));
  }

  private byte[] json(String value) {
    try {
      return mapper.writeValueAsBytes(mapper.readTree(value));
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static byte[] key(UUID aggregateId) {
    return aggregateId.toString().getBytes(StandardCharsets.UTF_8);
  }
}
