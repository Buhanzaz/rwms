package dev.buhanzaz.rwms.logistics.inquiry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RentalSettingsTest {
  private static final UUID ACTOR = UUID.randomUUID();
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-05T10:00:00Z");

  @Test
  void defaultsLeaveFeeAndSupportUnconfigured() {
    RentalSettings settings = RentalSettings.defaults(ACTOR, NOW);
    assertThat(settings.getLateChangeNoticeDays()).isEqualTo(2);
    assertThat(settings.getLateChangeFeeMode()).isNull();
    assertThat(settings.getLateChangeFeeValue()).isNull();
    assertThat(settings.getRentalSupportPhone()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "FIXED, 0",
    "FIXED, 1500.00",
    "FIXED, 9223372036854775807",
    "PERCENT, 0",
    "PERCENT, 12.25",
    "PERCENT, 100"
  })
  void acceptsExactConfiguredFees(LateChangeFeeMode mode, BigDecimal fee) {
    RentalSettings settings = RentalSettings.defaults(ACTOR, NOW);
    settings.update(11, 65, 75, 2880, 0, mode, fee, "+74951234567", ACTOR, NOW);
    assertThat(settings.getLateChangeFeeMode()).isEqualTo(mode);
    assertThat(settings.getLateChangeFeeValue()).isEqualByComparingTo(fee);
    assertThat(settings.getLateChangeNoticeDays()).isZero();
    assertThat(settings.getChatSelectionHoldMinutes()).isEqualTo(11);
    assertThat(settings.getManualBookingHoldMinutes()).isEqualTo(65);
    assertThat(settings.getPresentationHoldMinutes()).isEqualTo(75);
    assertThat(settings.getDraftReservationHoldMinutes()).isEqualTo(2880);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "FIXED, -1",
        "FIXED, 1.01",
        "FIXED, 9223372036854775808",
        "PERCENT, 100.01",
        "PERCENT, 1.001",
        "PERCENT, -0.01",
        "FIXED, NULL",
        "NULL, 0"
      },
      nullValues = "NULL")
  void rejectsInvalidFeesWithoutPartiallyUpdatingHolds(LateChangeFeeMode mode, BigDecimal fee) {
    RentalSettings settings = RentalSettings.defaults(ACTOR, NOW);
    assertThatThrownBy(() -> settings.update(11, 65, 75, 2880, 3, mode, fee, null, ACTOR, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(settings.getChatSelectionHoldMinutes()).isEqualTo(10);
    assertThat(settings.getLateChangeNoticeDays()).isEqualTo(2);
    assertThat(settings.getLateChangeFeeMode()).isNull();
  }

  @Test
  void explicitlyClearsPolicyAndPhoneWithoutInventingZero() {
    RentalSettings settings = RentalSettings.defaults(ACTOR, NOW);
    settings.update(
        10, 60, 60, 1440, 2, LateChangeFeeMode.FIXED, BigDecimal.TEN, "+74951234567", ACTOR, NOW);
    settings.update(10, 60, 60, 1440, 4, null, null, null, ACTOR, NOW);
    assertThat(settings.getLateChangeNoticeDays()).isEqualTo(4);
    assertThat(settings.getLateChangeFeeValue()).isNull();
    assertThat(settings.getRentalSupportPhone()).isNull();
  }

  @Test
  void rejectsNegativeNoticeAndNonDialableSupportPhone() {
    RentalSettings settings = RentalSettings.defaults(ACTOR, NOW);
    assertThatThrownBy(() -> settings.update(10, 60, 60, 1440, -1, null, null, null, ACTOR, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> settings.update(10, 60, 60, 1440, 2, null, null, "Call manager", ACTOR, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> settings.update(10, 60, 60, 1440, 2, null, null, "", ACTOR, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
