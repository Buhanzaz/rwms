package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.NextRequiredAction;
import dev.buhanzaz.rwms.taskboard.service.DriverShiftService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Verifies the safe-default feature switch preserves the legacy Driver Up task flow. */
@SpringBootTest(
    properties = {
      "rwms.driver-shift.enabled=false",
      "rwms.driver-shift.suspicious-odometer-jump-km=1700"
    })
@ActiveProfiles("test")
class DriverShiftDisabledIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired DriverShiftService shifts;

  @Test
  void disabledStartupRequiresNoShiftPlanOrWarehouseDependency() {
    var response = shifts.today(UUID.randomUUID(), UUID.randomUUID());

    assertThat(response.enabled()).isFalse();
    assertThat(response.nextRequiredAction()).isEqualTo(NextRequiredAction.SHOW_TASKS);
    assertThat(response.suspiciousOdometerJumpKm()).isEqualTo(1_700);
    assertThat(response.shift()).isNull();
    assertThat(response.photos()).isEmpty();
  }
}
