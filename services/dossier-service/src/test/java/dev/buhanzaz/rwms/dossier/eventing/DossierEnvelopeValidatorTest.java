package dev.buhanzaz.rwms.dossier.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DossierEnvelopeValidatorTest {
  private static final UUID EVENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID CABIN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID WAREHOUSE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private final DossierEnvelopeValidator validator =
      new DossierEnvelopeValidator(new ObjectMapper(), new DossierProducerSchemaValidator());

  @Test
  void validatesDirectAssetSubjectAndCanonicalCoordinates() {
    DossierValidatedEvent event =
        validator.validate(
            "rwms.asset.rental-item.v1",
            2,
            19,
            CABIN_ID.toString(),
            assetFact().getBytes(StandardCharsets.UTF_8));

    assertThat(event.eventId()).isEqualTo(EVENT_ID);
    assertThat(event.cabinId()).isEqualTo(CABIN_ID);
    assertThat(event.warehouseId()).isEqualTo(WAREHOUSE_ID);
    assertThat(event.activityCode()).isEqualTo("CABIN_CREATED");
    assertThat(event.partition()).isEqualTo(2);
    assertThat(event.offset()).isEqualTo(19);
  }

  @Test
  void acceptsTheUtf8ByteArrayKafkaKeyUsedByRealProducerRelays() {
    DossierValidatedEvent event =
        validator.validate(
            "rwms.asset.rental-item.v1",
            0,
            20,
            CABIN_ID.toString().getBytes(StandardCharsets.UTF_8),
            assetFact().getBytes(StandardCharsets.UTF_8));

    assertThat(event.aggregateId()).isEqualTo(CABIN_ID);
  }

  @Test
  void acceptsSharedInventorySessionAsJournalOnlyEvidence() {
    UUID sessionId = UUID.fromString("40000000-0000-0000-0000-000000000001");
    String fact =
        envelope(
            "inventory.session.started.v1",
            "inventory-service",
            "SESSION",
            sessionId,
            """
            {"inventoryId":"%s","warehouseId":"%s","sessionRevision":1,"lifecycle":"ACTIVE","businessDate":"2026-07-18","expectedCount":0,"findingCount":0,"terminalAt":null,"statistics":null}
            """
                .formatted(sessionId, WAREHOUSE_ID));

    DossierValidatedEvent event =
        validator.validate(
            "rwms.inventory.session.v1",
            0,
            0,
            sessionId.toString(),
            fact.getBytes(StandardCharsets.UTF_8));

    assertThat(event.activityCode()).isNull();
    assertThat(event.subjectCapable()).isFalse();
    assertThat(event.cabinId()).isNull();
  }

  @Test
  void acceptsOwnerProofAsAssociationOnlyEvidence() {
    UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000002");
    String fact =
        envelope(
            "inventory.finding.owner-proof.v1",
            "inventory-service",
            "FINDING",
            findingId,
            """
            {"ownerType":"INVENTORY_FINDING","ownerId":"%s","warehouseId":"%s","ownerRevision":3,"active":true}
            """
                .formatted(findingId, WAREHOUSE_ID));

    DossierValidatedEvent event =
        validator.validate(
            "rwms.inventory.session.v1",
            0,
            2,
            findingId.toString(),
            fact.getBytes(StandardCharsets.UTF_8));

    assertThat(event.activityCode()).isNull();
    assertThat(event.subjectCapable()).isTrue();
    assertThat(event.secondaryId()).isEqualTo(findingId);
    assertThat(event.warehouseId()).isEqualTo(WAREHOUSE_ID);
  }

  @Test
  void hashesCanonicalValidatedEnvelopeRatherThanWireWhitespaceOrPropertyOrder() {
    String reordered =
        assetFact()
            .replace(
                "{\"rentalItemId\":\"%s\",\"warehouseId\":\"%s\",\"status\":\"AVAILABLE\",\"numberSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"
                    .formatted(CABIN_ID, WAREHOUSE_ID),
                "{ \"numberSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"status\":\"AVAILABLE\",\"warehouseId\":\"%s\",\"rentalItemId\":\"%s\" }"
                    .formatted(WAREHOUSE_ID, CABIN_ID));
    DossierValidatedEvent first =
        validator.validate(
            "rwms.asset.rental-item.v1", 0, 3, CABIN_ID.toString(), assetFact().getBytes(StandardCharsets.UTF_8));
    DossierValidatedEvent second =
        validator.validate(
            "rwms.asset.rental-item.v1", 0, 4, CABIN_ID.toString(), reordered.getBytes(StandardCharsets.UTF_8));

    assertThat(second.payloadSha256()).isEqualTo(first.payloadSha256());
    assertThat(second.canonicalEnvelope()).isEqualTo(first.canonicalEnvelope());
  }

  @Test
  void rejectsKeyMismatchUnknownFieldAndSensitivePayloadWithoutEchoingValues() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    "rwms.asset.rental-item.v1",
                    0,
                    1,
                    UUID.randomUUID().toString(),
                    assetFact().getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(DossierValidationException.class)
        .hasMessage("SOURCE_RECORD_KEY_MISMATCH");

    String unsafe = assetFact().replace("\"numberSha256\":\"", "\"email\":\"x@example.test\",\"numberSha256\":\"");
    assertThatThrownBy(
            () ->
                validator.validate(
                    "rwms.asset.rental-item.v1",
                    0,
                    1,
                    CABIN_ID.toString(),
                    unsafe.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(DossierValidationException.class)
        .hasMessage("SOURCE_PAYLOAD_REJECTED")
        .hasMessageNotContaining("example.test");
  }

  private static String assetFact() {
    return envelope(
        "asset.rental-item.created.v1",
        "asset-service",
        "RENTAL_ITEM",
        CABIN_ID,
        """
        {"rentalItemId":"%s","warehouseId":"%s","status":"AVAILABLE","numberSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        """
            .formatted(CABIN_ID, WAREHOUSE_ID));
  }

  private static String envelope(
      String eventType, String producer, String aggregateType, UUID aggregateId, String payload) {
    return """
        {"envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":null,"recordedAt":"2026-07-18T00:00:00Z","producer":"%s","aggregateType":"%s","aggregateId":"%s","aggregateVersion":0,"correlation":{"correlationId":"50000000-0000-0000-0000-000000000001","causationId":null},"actorRef":null,"payload":%s}
        """
        .formatted(EVENT_ID, eventType, producer, aggregateType, aggregateId, payload.strip());
  }
}
