package dev.buhanzaz.rwms.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalItemTest {

  @Test
  void canonicalizesGlobalRentalItemNumbersWithoutAReusePath() {
    assertThat(RentalItem.canonicalNumber("  ab-12 / тест ")).isEqualTo("AB12ТЕСТ");
    assertThatThrownBy(() -> RentalItem.canonicalNumber("---"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reservesTerminalAndWorkflowStatusesFromThePublicManualTransition() {
    RentalItem item = RentalItem.create(UUID.randomUUID(), "A-1", null, null, null, null, null, null, "{}", "[]");

    assertThatThrownBy(() -> item.changeStatus(RentalItemStatus.WRITTEN_OFF))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fenced or terminal");
  }
}
