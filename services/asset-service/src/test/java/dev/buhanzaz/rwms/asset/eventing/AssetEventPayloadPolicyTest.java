package dev.buhanzaz.rwms.asset.eventing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AssetEventPayloadPolicyTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AssetEventPayloadPolicy policy = new AssetEventPayloadPolicy(mapper);

  @Test
  void acceptsTheSanitizedRentalFact() {
    UUID id = UUID.randomUUID();

    assertThatCode(
            () ->
                policy.validateAndConvert(
                    AssetEventType.RENTAL_ITEM_STATUS_CHANGED,
                    AssetAggregateType.RENTAL_ITEM,
                    id,
                    Map.of(
                        "rentalItemId", id.toString(),
                        "warehouseId", UUID.randomUUID().toString(),
                        "status", "FREE",
                        "numberSha256", "0".repeat(64))))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsCommentsNotesTenantMediaAndPiiBeforeTheyReachOutboxOrDlt() {
    UUID id = UUID.randomUUID();
    var malicious = mapper.createObjectNode();
    malicious.put("rentalItemId", id.toString());
    malicious.put("comment", "operator@example.test");

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    AssetEventType.RENTAL_ITEM_GENERAL_COMMENT_CHANGED.value(),
                    AssetAggregateType.RENTAL_ITEM,
                    id,
                    malicious))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden field");
  }

  @Test
  void rejectsAWrongAggregateFamilyOrIdentity() {
    UUID id = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("equipmentId", id.toString());
    payload.put("code", "CHAIR");
    payload.put("category", "FURNITURE");
    payload.put("active", true);

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    AssetEventType.EQUIPMENT_CATALOG_CHANGED.value(),
                    AssetAggregateType.RENTAL_ITEM,
                    id,
                    payload))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
