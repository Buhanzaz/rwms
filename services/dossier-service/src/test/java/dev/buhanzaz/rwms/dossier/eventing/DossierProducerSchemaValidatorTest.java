package dev.buhanzaz.rwms.dossier.eventing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DossierProducerSchemaValidatorTest {
  private final DossierProducerSchemaValidator schemas = new DossierProducerSchemaValidator();

  @Test
  void rejectsProducerEnumRangeAndNestedShapeViolationsWithOneSafeCode() {
    assertRejected(
        "rwms.asset.rental-item.v1",
        envelope(
            "asset.rental-item.created.v1",
            "asset-service",
            "RENTAL_ITEM",
            0,
            """
            {"rentalItemId":"20000000-0000-0000-0000-000000000001","warehouseId":"30000000-0000-0000-0000-000000000001","status":"AVAILABLE","numberSha256":"not-a-sha256"}
            """));
    assertRejected(
        "rwms.media.media.v1",
        envelope(
            "media.media.ready.v1",
            "media-service",
            "MEDIA",
            1,
            """
            {"mediaId":"20000000-0000-0000-0000-000000000001","ownerType":"INVENTORY_FINDING","ownerId":"30000000-0000-0000-0000-000000000001","warehouseId":"40000000-0000-0000-0000-000000000001","kind":"AUDIO","status":"READY","generation":1,"rotationDegrees":0}
            """));
    assertRejected(
        "rwms.media.media.v1",
        envelope(
            "media.media.ready.v1",
            "media-service",
            "MEDIA",
            1,
            """
            {"mediaId":"20000000-0000-0000-0000-000000000001","ownerType":"INVENTORY_FINDING","ownerId":"30000000-0000-0000-0000-000000000001","warehouseId":"40000000-0000-0000-0000-000000000001","kind":"IMAGE","status":"READY","generation":-1,"rotationDegrees":0}
            """));
    assertRejected(
        "rwms.inventory.session.v1",
        envelope(
            "inventory.session.completed.v1",
            "inventory-service",
            "SESSION",
            0,
            """
            {"inventoryId":"20000000-0000-0000-0000-000000000001","warehouseId":"30000000-0000-0000-0000-000000000001","sessionRevision":1,"lifecycle":"COMPLETED","businessDate":"2026-07-18","expectedCount":0,"findingCount":0,"terminalAt":"2026-07-18T01:00:00Z","statistics":{"unexpected":"field"}}
            """));
  }

  @Test
  void acceptsTheCanonicalCabinPhotoFactSchema() {
    assertThatCode(
            () ->
                schemas.validate(
                    "rwms.media.cabin-photo.v1",
                    envelope(
                            "media.cabin.cover-changed.v1",
                            "media-service",
                            "CABIN_PHOTO_LIBRARY",
                            1,
                            """
                            {"cabinId":"20000000-0000-0000-0000-000000000001","warehouseId":"30000000-0000-0000-0000-000000000001","mediaId":"40000000-0000-0000-0000-000000000001","generation":1,"taskBoardEntryId":"50000000-0000-0000-0000-000000000001","previousCoverMediaId":null,"changedAt":"2026-07-18T00:00:00Z"}
                            """)
                        .getBytes(StandardCharsets.UTF_8)))
        .doesNotThrowAnyException();
  }

  private void assertRejected(String topic, String value) {
    assertThatThrownBy(() -> schemas.validate(topic, value.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(DossierValidationException.class)
        .hasMessage("SOURCE_SCHEMA_REJECTED")
        .hasMessageNotContaining("not-a-sha256")
        .hasMessageNotContaining("unexpected");
  }

  private static String envelope(
      String eventType, String producer, String aggregateType, long aggregateVersion, String payload) {
    return """
        {"envelopeVersion":2,"eventId":"10000000-0000-0000-0000-000000000001","eventType":"%s","eventVersion":1,"occurredAt":null,"recordedAt":"2026-07-18T00:00:00Z","producer":"%s","aggregateType":"%s","aggregateId":"20000000-0000-0000-0000-000000000001","aggregateVersion":%d,"correlation":{"correlationId":"50000000-0000-0000-0000-000000000001","causationId":null},"actorRef":null,"payload":%s}
        """
        .formatted(eventType, producer, aggregateType, aggregateVersion, payload.strip());
  }
}
