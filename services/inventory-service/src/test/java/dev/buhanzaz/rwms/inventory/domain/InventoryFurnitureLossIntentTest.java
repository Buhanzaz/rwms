package dev.buhanzaz.rwms.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryFurnitureLossIntentTest {
  @Test
  void preservesFrozenIdentityAcrossRetryAndSuccessfulDecision() {
    UUID findingId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    InventoryFurnitureLossIntent intent =
        InventoryFurnitureLossIntent.pending(
            findingId,
            inventoryId,
            warehouseId,
            equipmentId,
            idempotencyKey,
            "a".repeat(64),
            "{\"quantity\":2}");

    intent.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
    intent.transientFailure(
        "MAINTENANCE_SERVICE_UNAVAILABLE",
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2));
    intent.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(3));
    UUID decisionId = UUID.randomUUID();
    intent.succeed(decisionId);

    assertThat(intent.getFindingId()).isEqualTo(findingId);
    assertThat(intent.getInventoryId()).isEqualTo(inventoryId);
    assertThat(intent.getWarehouseId()).isEqualTo(warehouseId);
    assertThat(intent.getEquipmentId()).isEqualTo(equipmentId);
    assertThat(intent.getIdempotencyKey()).isEqualTo(idempotencyKey);
    assertThat(intent.getAttemptCount()).isEqualTo(2);
    assertThat(intent.getState()).isEqualTo(FurnitureLossIntentState.SUCCEEDED);
    assertThat(intent.getDecisionId()).isEqualTo(decisionId);
    assertThat(intent.getFailureCode()).isNull();
  }

  @Test
  void terminalLossIntentCannotBeRetried() {
    InventoryFurnitureLossIntent intent =
        InventoryFurnitureLossIntent.pending(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "b".repeat(64),
            "{}");
    intent.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
    intent.block("MAINTENANCE_REQUEST_REJECTED");

    assertThatThrownBy(
            () ->
                intent.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not retryable");
  }
}
