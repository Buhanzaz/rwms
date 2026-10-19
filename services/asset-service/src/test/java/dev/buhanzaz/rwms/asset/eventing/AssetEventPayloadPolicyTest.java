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
  void inventoryVisibilityRequiresAnExactTypedFactForHoldAndRelease() {
    UUID id = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("rentalItemId", id.toString());
    payload.put("warehouseId", UUID.randomUUID().toString());
    payload.put("status", "FREE");
    payload.put("numberSha256", "0".repeat(64));
    payload.put("inventoryId", UUID.randomUUID().toString());
    for (boolean isolated : new boolean[] {true, false}) {
      payload.put("isolated", isolated);
      assertThatCode(
              () ->
                  policy.validateNode(
                      AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED.value(),
                      AssetAggregateType.RENTAL_ITEM,
                      id,
                      payload))
          .doesNotThrowAnyException();
    }
    payload.put("isolated", "false");
    assertThatThrownBy(
            () ->
                policy.validateNode(
                    AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED.value(),
                    AssetAggregateType.RENTAL_ITEM,
                    id,
                    payload))
        .isInstanceOf(IllegalArgumentException.class);
    payload.put("isolated", false);
    payload.put("unexpected", true);
    assertThatThrownBy(
            () ->
                policy.validateNode(
                    AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED.value(),
                    AssetAggregateType.RENTAL_ITEM,
                    id,
                    payload))
        .isInstanceOf(IllegalArgumentException.class);
    payload.remove("unexpected");
    payload.put("inventoryId", "not-a-uuid");
    assertThatThrownBy(
            () ->
                policy.validateNode(
                    AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED.value(),
                    AssetAggregateType.RENTAL_ITEM,
                    id,
                    payload))
        .isInstanceOf(IllegalArgumentException.class);
    payload.remove("inventoryId");
    assertThatThrownBy(
            () ->
                policy.validateNode(
                    AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED.value(),
                    AssetAggregateType.RENTAL_ITEM,
                    id,
                    payload))
        .isInstanceOf(IllegalArgumentException.class);
  }

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
  void rejectsLegacyBusinessIdentifiersFromEquipmentFacts() {
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
                    AssetAggregateType.EQUIPMENT_CATALOG,
                    id,
                    payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden field");
  }

  @Test
  void acceptsCompleteImmutableMovementLocationContext() {
    UUID movementId = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("movementId", movementId.toString());
    payload.put("equipmentId", UUID.randomUUID().toString());
    payload.put("sourceBalanceId", UUID.randomUUID().toString());
    payload.put("targetBalanceId", UUID.randomUUID().toString());
    payload.put("quantity", 3);
    payload.put("movementKind", "CABIN_TO_STOCK");
    payload.put("equipmentCategory", "FURNITURE");
    payload.put("sourceWarehouseId", UUID.randomUUID().toString());
    payload.put("sourceRentalItemId", UUID.randomUUID().toString());
    payload.put("sourceLocationKind", "CABIN_NON_RENTED");
    payload.put("targetWarehouseId", UUID.randomUUID().toString());
    payload.putNull("targetRentalItemId");
    payload.put("targetLocationKind", "STOCK");

    assertThatCode(
            () ->
                policy.validateNode(
                    AssetEventType.EQUIPMENT_TRANSFERRED.value(),
                    AssetAggregateType.EQUIPMENT_MOVEMENT,
                    movementId,
                    payload))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsPartialMovementLocationContext() {
    UUID movementId = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("movementId", movementId.toString());
    payload.put("equipmentId", UUID.randomUUID().toString());
    payload.put("sourceBalanceId", UUID.randomUUID().toString());
    payload.put("targetBalanceId", UUID.randomUUID().toString());
    payload.put("quantity", 1);
    payload.put("movementKind", "WAREHOUSE_TO_WAREHOUSE");
    payload.put("sourceWarehouseId", UUID.randomUUID().toString());

    assertThatThrownBy(
            () ->
                policy.validateNode(
                    AssetEventType.EQUIPMENT_TRANSFERRED.value(),
                    AssetAggregateType.EQUIPMENT_MOVEMENT,
                    movementId,
                    payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absent or complete");
  }
}
