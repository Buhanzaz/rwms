package dev.buhanzaz.rwms.logistics.driver.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.CreateDriverTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DriverTaskApiModelsTest {
  @Test
  void manualSourceRequiresGeneralMovementAndNonblankComment() {
    CreateDriverTaskRequest missingComment =
        request(DriverTaskSourceType.MANUAL, DriverTaskKind.GENERAL_MOVEMENT, " ");
    CreateDriverTaskRequest wrongKind =
        request(DriverTaskSourceType.MANUAL, DriverTaskKind.DELIVER_TO_REPAIR, "Комментарий");
    CreateDriverTaskRequest valid =
        request(DriverTaskSourceType.MANUAL, DriverTaskKind.GENERAL_MOVEMENT, "Комментарий");

    assertThat(missingComment.isManualMovementValid()).isFalse();
    assertThat(wrongKind.isManualMovementValid()).isFalse();
    assertThat(valid.isManualMovementValid()).isTrue();
  }

  private static CreateDriverTaskRequest request(
      DriverTaskSourceType sourceType, DriverTaskKind kind, String comment) {
    return new CreateDriverTaskRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        sourceType,
        UUID.randomUUID(),
        kind,
        DriverTaskPlanningMode.AUTO,
        null,
        3,
        true,
        comment);
  }
}
