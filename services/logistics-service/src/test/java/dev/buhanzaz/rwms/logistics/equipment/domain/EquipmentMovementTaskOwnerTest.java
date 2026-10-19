package dev.buhanzaz.rwms.logistics.equipment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EquipmentMovementTaskOwnerTest {
  @Test
  void maintenanceDispositionUsesItsDecisionAsTheAssetMovementFence() {
    UUID localTaskId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    EquipmentMovementTask task =
        EquipmentMovementTask.create(
            UUID.randomUUID(),
            "БЫТ-521",
            30,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
            UUID.randomUUID(),
            UUID.randomUUID(),
            EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION,
            decisionId,
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", localTaskId);

    assertThat(task.getId()).isEqualTo(localTaskId);
    assertThat(task.getOwnerType()).isEqualTo(EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION);
    assertThat(task.getOwnerId()).isEqualTo(decisionId);
    assertThat(task.assetMovementOwnerId()).isEqualTo(decisionId);
  }

  @Test
  void publicTaskRetainsItsLocalTaskIdAsTheAssetMovementFence() {
    UUID localTaskId = UUID.randomUUID();
    EquipmentMovementTask task =
        EquipmentMovementTask.create(
            UUID.randomUUID(),
            "БЫТ-522",
            30,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "b".repeat(64));
    ReflectionTestUtils.setField(task, "id", localTaskId);

    assertThat(task.getOwnerType()).isEqualTo(EquipmentMovementTaskOwnerType.USER_REQUEST);
    assertThat(task.getOwnerId()).isNull();
    assertThat(task.assetMovementOwnerId()).isEqualTo(localTaskId);
  }

  @Test
  void maintenanceTaskCannotBeCreatedWithoutADurableDecision() {
    assertThatThrownBy(
            () ->
                EquipmentMovementTask.create(
                    UUID.randomUUID(),
                    "БЫТ-523",
                    30,
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION,
                    null,
                    "c".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("decision");
  }
}
