package dev.buhanzaz.rwms.logistics.eventing.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LogisticsInboundEnvelopeValidatorTest {
  private final LogisticsInboundEnvelopeValidator validator =
      new LogisticsInboundEnvelopeValidator(new ObjectMapper());

  @Test
  void acceptsHistoricalAndSuspensionAwareTaskFactsWithStrictBooleanValidation() {
    UUID taskId = UUID.randomUUID();
    var mapper = new ObjectMapper();
    var root = (tools.jackson.databind.node.ObjectNode)
        mapper.readTree(rentalEvent(UUID.randomUUID(), taskId, 2, "FREE"));
    root.put("producer", "task-board-service");
    root.put("aggregateType", "BOARD_TASK");
    root.put("eventType", "task-board.board-task.changed.v1");
    var payload = root.putObject("payload");
    payload.put("boardTaskId", taskId.toString());
    payload.put("warehouseId", UUID.randomUUID().toString());
    payload.putNull("externalTaskId");
    payload.put("status", "ACTIVE");
    payload.putNull("plannedDurationMinutes");
    payload.putNull("deadlineAt");
    payload.putNull("doneAt");
    payload.put("deleted", false);
    assertThat(validator.validate(LogisticsInboundTransportTopics.BOARD_TASK, taskId.toString(),
        mapper.writeValueAsBytes(root))).isNotNull();
    payload.put("lane", "SCHEDULED");
    payload.put("priority", 3);
    payload.put("pinned", false);
    payload.putNull("driverAudience");
    payload.putNull("plannedDriverWorkerId");
    for (boolean suspended : new boolean[] {true, false}) {
      payload.put("suspended", suspended);
      var event = validator.validate(LogisticsInboundTransportTopics.BOARD_TASK, taskId.toString(),
          mapper.writeValueAsBytes(root));
      assertThat(event.payload().required("suspended").booleanValue()).isEqualTo(suspended);
      assertThat(event.payload().required("status").stringValue()).isEqualTo("ACTIVE");
    }
    payload.put("suspended", "true");
    assertThatThrownBy(() -> validator.validate(LogisticsInboundTransportTopics.BOARD_TASK,
        taskId.toString(), mapper.writeValueAsBytes(root)))
        .isInstanceOf(LogisticsInboundValidationException.class);
    payload.put("suspended", true);
    payload.put("unexpected", true);
    assertThatThrownBy(() -> validator.validate(LogisticsInboundTransportTopics.BOARD_TASK,
        taskId.toString(), mapper.writeValueAsBytes(root)))
        .isInstanceOf(LogisticsInboundValidationException.class);
  }

  @Test
  void acceptsAnExactDeclaredAssetFactWithItsAggregateKafkaKey() {
    UUID eventId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();

    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event =
        validator.validate(
            LogisticsInboundTransportTopics.RENTAL_ITEM,
            assetId.toString().getBytes(StandardCharsets.UTF_8),
            rentalEvent(eventId, assetId, 0, "FREE"));

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.aggregateId()).isEqualTo(assetId.toString());
    assertThat(event.aggregateVersion()).isZero();
    assertThat(event.rawMessageSha256()).matches("[0-9a-f]{64}");
  }

  @Test
  void acceptsTheExactInventoryVisibilityRentalItemFact() {
    UUID eventId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();

    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event =
        validator.validate(
            LogisticsInboundTransportTopics.RENTAL_ITEM,
            assetId.toString(),
            inventoryVisibilityEvent(eventId, assetId, 1, true));

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.eventType())
        .isEqualTo("asset.rental-item.inventory-visibility-changed.v1");
    assertThat(event.aggregateVersion()).isEqualTo(1);
  }

  @Test
  void rejectsInventoryVisibilityFactsWithANonBooleanIsolationMarker() {
    UUID assetId = UUID.randomUUID();
    String body =
        new String(
                inventoryVisibilityEvent(UUID.randomUUID(), assetId, 1, true),
                StandardCharsets.UTF_8)
            .replace("\"isolated\": true", "\"isolated\": \"true\"");

    assertThatThrownBy(
            () ->
                validator.validate(
                    LogisticsInboundTransportTopics.RENTAL_ITEM,
                    assetId.toString(),
                    body.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(LogisticsInboundValidationException.class)
        .hasMessageContaining("isolated");
  }

  @Test
  void rejectsAnUndeclaredEventTypeEvenWhenTheEnvelopeLooksValid() {
    UUID eventId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    String body =
        new String(rentalEvent(eventId, assetId, 0, "FREE"), StandardCharsets.UTF_8)
            .replace("asset.rental-item.created.v1", "asset.rental-item.deleted.v1");

    assertThatThrownBy(
            () ->
                validator.validate(
                    LogisticsInboundTransportTopics.RENTAL_ITEM,
                    assetId.toString(),
                    body.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(LogisticsInboundValidationException.class)
        .hasMessageContaining("Event type");
  }

  @Test
  void rejectsAMismatchedKafkaKey() {
    UUID eventId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                validator.validate(
                    LogisticsInboundTransportTopics.RENTAL_ITEM,
                    UUID.randomUUID().toString(),
                    rentalEvent(eventId, assetId, 0, "FREE")))
        .isInstanceOf(LogisticsInboundValidationException.class)
        .hasMessageContaining("Kafka key");
  }

  @Test
  void acceptsCurrentReturnAndTransferMediaFactsWithFoldersAndStructuredOwners() {
    UUID eventId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();

    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event =
        validator.validate(
            LogisticsInboundTransportTopics.MEDIA,
            mediaId.toString(),
            mediaEvent(
                eventId,
                mediaId,
                2,
                "media.media.ready.v1",
                "LOGISTICS_RETURN",
                documentId + ":" + lineId,
                "READY"));

    assertThat(event.appliesToLogistics()).isTrue();
    assertThat(event.isPublicMediaFact()).isTrue();
    assertThat(event.aggregateVersion()).isEqualTo(2);

    UUID transferMediaId = UUID.randomUUID();
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent transfer =
        validator.validate(
            LogisticsInboundTransportTopics.MEDIA,
            transferMediaId.toString(),
            mediaEvent(
                UUID.randomUUID(),
                transferMediaId,
                2,
                "media.media.ready.v1",
                "LOGISTICS_TRANSFER",
                UUID.randomUUID() + ":" + UUID.randomUUID(),
                "READY"));

    assertThat(transfer.appliesToLogistics()).isTrue();
  }

  @Test
  void acknowledgesAForeignMediaOwnerBeforeApplyingLogisticsOwnerValidation() {
    UUID eventId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();

    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event =
        validator.validate(
            LogisticsInboundTransportTopics.MEDIA,
            mediaId.toString(),
            mediaEvent(
                eventId,
                mediaId,
                2,
                "media.media.ready.v1",
                "CABIN",
                "foreign-cabin-owner",
                "READY"));

    assertThat(event.appliesToLogistics()).isFalse();
  }

  @Test
  void rejectsARelevantMediaFactWithoutItsCanonicalFolderId() {
    UUID eventId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    String body =
        new String(
                mediaEvent(
                    eventId,
                    mediaId,
                    2,
                    "media.media.ready.v1",
                    "LOGISTICS_TRANSFER",
                    documentId + ":" + lineId,
                    "READY"),
                StandardCharsets.UTF_8)
            .replaceFirst("\\\"folderId\\\": \\\"[0-9a-f-]+\\\",\\s*", "");

    assertThatThrownBy(
            () ->
                validator.validate(
                    LogisticsInboundTransportTopics.MEDIA,
                    mediaId.toString(),
                    body.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(LogisticsInboundValidationException.class)
        .hasMessageContaining("media payload fields");
  }

  static byte[] rentalEvent(UUID eventId, UUID assetId, long version, String status) {
    return """
        {
          "envelopeVersion": 2,
          "eventId": "%s",
          "eventType": "asset.rental-item.created.v1",
          "eventVersion": 1,
          "occurredAt": null,
          "recordedAt": "2026-07-17T08:00:00Z",
          "producer": "asset-service",
          "aggregateType": "RENTAL_ITEM",
          "aggregateId": "%s",
          "aggregateVersion": %d,
          "correlation": {
            "correlationId": "00000000-0000-0000-0000-000000000801",
            "causationId": null
          },
          "actorRef": null,
          "payload": {
            "rentalItemId": "%s",
            "warehouseId": "00000000-0000-0000-0000-000000000802",
            "status": "%s",
            "numberSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          }
        }
        """.formatted(eventId, assetId, version, assetId, status)
        .getBytes(StandardCharsets.UTF_8);
  }

  static byte[] inventoryVisibilityEvent(
      UUID eventId, UUID assetId, long version, boolean isolated) {
    return """
        {
          "envelopeVersion": 2,
          "eventId": "%s",
          "eventType": "asset.rental-item.inventory-visibility-changed.v1",
          "eventVersion": 1,
          "occurredAt": null,
          "recordedAt": "2026-07-17T08:00:00Z",
          "producer": "asset-service",
          "aggregateType": "RENTAL_ITEM",
          "aggregateId": "%s",
          "aggregateVersion": %d,
          "correlation": {
            "correlationId": "00000000-0000-0000-0000-000000000801",
            "causationId": null
          },
          "actorRef": null,
          "payload": {
            "rentalItemId": "%s",
            "warehouseId": "00000000-0000-0000-0000-000000000802",
            "status": "FREE",
            "numberSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "inventoryId": "00000000-0000-0000-0000-000000000803",
            "isolated": %s
          }
        }
        """.formatted(eventId, assetId, version, assetId, isolated)
        .getBytes(StandardCharsets.UTF_8);
  }

  static byte[] mediaEvent(
      UUID eventId,
      UUID mediaId,
      long version,
      String eventType,
      String ownerType,
      String ownerId,
      String status) {
    return """
        {
          "envelopeVersion": 2,
          "eventId": "%s",
          "eventType": "%s",
          "eventVersion": 1,
          "occurredAt": null,
          "recordedAt": "2026-07-17T08:00:00Z",
          "producer": "media-service",
          "aggregateType": "MEDIA",
          "aggregateId": "%s",
          "aggregateVersion": %d,
          "correlation": {
            "correlationId": "00000000-0000-0000-0000-000000000803",
            "causationId": null
          },
          "actorRef": null,
          "payload": {
            "mediaId": "%s",
            "folderId": "%s",
            "ownerType": "%s",
            "ownerId": "%s",
            "warehouseId": "00000000-0000-0000-0000-000000000804",
            "kind": "IMAGE",
            "status": "%s",
            "generation": 1,
            "rotationDegrees": 0
          }
        }
        """
        .formatted(eventId, eventType, mediaId, version, mediaId, UUID.randomUUID(), ownerType, ownerId, status)
        .getBytes(StandardCharsets.UTF_8);
  }
}
