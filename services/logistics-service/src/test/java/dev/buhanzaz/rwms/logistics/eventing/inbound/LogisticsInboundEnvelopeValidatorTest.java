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
}
