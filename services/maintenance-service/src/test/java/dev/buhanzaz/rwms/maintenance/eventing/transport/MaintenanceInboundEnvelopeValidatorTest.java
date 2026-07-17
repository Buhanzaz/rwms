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
  void acceptsFullSourceFamilyButOnlyTerminalTaskBoardFactsAreActionable() {
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
    assertThat(completed.actionable()).isTrue();
    assertThat(completed.aggregateId()).isEqualTo(aggregateId.toString());
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
          "externalTaskId":"%s","status":"DONE","plannedDurationMinutes":10,
          "deadlineAt":null,"doneAt":"2026-07-17T00:00:00Z","deleted":false}
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
                externalTaskId));
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
          "ownerId":"%s","warehouseId":"%s","kind":"IMAGE","status":"READY",
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
