package dev.buhanzaz.rwms.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacyRentalItemStatusTest {

  @Test
  void mapsOnlyTheApprovedLegacyStatuses() {
    assertThat(LegacyRentalItemStatus.mappings())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "READY", RentalItemStatus.FREE,
                "TEMP_RESERVED", RentalItemStatus.BOOKED,
                "RESERVED", RentalItemStatus.RESERVED,
                "IN_RENT", RentalItemStatus.RENTED,
                "NEED_INSPECTION", RentalItemStatus.AFTER_RENT,
                "WAITING_REPAIR", RentalItemStatus.REPAIR,
                "IN_REPAIR", RentalItemStatus.REPAIR,
                "WAITING_REPAIR_CHECK", RentalItemStatus.WAITING_REPAIR_CHECK,
                "IN_CAP_REPAIR", RentalItemStatus.CAPITAL_REPAIR));
  }

  @Test
  void leavesUnknownLegacyValuesUninterpreted() {
    assertThatThrownBy(() -> LegacyRentalItemStatus.requireTarget("SOMETHING_NEW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not mapped");
  }
}
