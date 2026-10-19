package dev.buhanzaz.rwms.logistics.equipment.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EquipmentMovementDurationInvariantTest {
  @Test
  void newMovementTaskRejectsMissingOrNonPositiveDuration() {
    assertThatThrownBy(() -> task(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plannedDurationMinutes");
    assertThatThrownBy(() -> task(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plannedDurationMinutes");
  }

  private static EquipmentMovementTask task(Integer durationMinutes) {
    return EquipmentMovementTask.create(
        UUID.randomUUID(),
        "CAB-701",
        durationMinutes,
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));
  }
}
