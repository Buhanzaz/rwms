package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement;
import dev.buhanzaz.rwms.logistics.inquiry.domain.LateChangeFeeMode;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService.LateChangePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CustomerBookingChangePolicyTest {
  private static final LocalDate DELIVERY = LocalDate.of(2026, 9, 10);

  @Test
  void twoCalendarDaysRemainFreeUntilWarehouseMidnightNotFortyEightHours() {
    var free = assess("2026-09-08T20:59:59Z", "Europe/Moscow", fixed(1500), 10000L);
    assertThat(free.amountRubles()).isZero();
    assertThat(free.settlement()).isEqualTo(CustomerChangeSettlement.NOT_REQUIRED);
    assertThat(free.expiresAt().toInstant()).isEqualTo(Instant.parse("2026-09-08T21:00:00Z"));
    var late = assess("2026-09-08T21:00:00Z", "Europe/Moscow", fixed(1500), 10000L);
    assertThat(late.amountRubles()).isEqualTo(1500L);
    assertThat(late.settlement()).isEqualTo(CustomerChangeSettlement.PAYMENT_REQUIRED);
  }

  @Test
  void theSameInstantUsesEachWarehousesCalendarDate() {
    assertThat(assess("2026-09-08T18:00:00Z", "Asia/Vladivostok", fixed(1500), 1L).amountRubles())
        .isEqualTo(1500L);
    assertThat(assess("2026-09-08T18:00:00Z", "Europe/Moscow", fixed(1500), 1L).amountRubles())
        .isZero();
  }

  @Test
  void absentPolicyIsNotZeroForLateChangesButDoesNotChargeEarlyNotice() {
    var missing = new LateChangePolicy(0, 2, null, null, null);
    assertThat(assess("2026-09-09T10:00:00Z", "UTC", missing, 10000L).amountRubles()).isNull();
    assertThat(assess("2026-09-08T10:00:00Z", "UTC", missing, null).amountRubles()).isZero();
  }

  @Test
  void percentageUsesExactOriginalPriceAndRoundsOnlyTheFinalWholeRuble() {
    var percentage =
        new LateChangePolicy(4, 2, LateChangeFeeMode.PERCENT, new BigDecimal("12.50"), null);
    assertThat(assess("2026-09-09T10:00:00Z", "UTC", percentage, 10004L).amountRubles())
        .isEqualTo(1251L);
    assertThat(assess("2026-09-09T10:00:00Z", "UTC", percentage, null).amountRubles()).isNull();
    var full = new LateChangePolicy(4, 2, LateChangeFeeMode.PERCENT, new BigDecimal("100"), null);
    assertThat(assess("2026-09-09T10:00:00Z", "UTC", full, Long.MAX_VALUE).amountRubles())
        .isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void zeroFeeAndZeroNoticeAreExplicitPolicies() {
    assertThat(assess("2026-09-09T10:00:00Z", "UTC", fixed(0), 10000L).settlement())
        .isEqualTo(CustomerChangeSettlement.NOT_REQUIRED);
    var zeroNotice = new LateChangePolicy(4, 0, LateChangeFeeMode.FIXED, BigDecimal.TEN, null);
    assertThat(assess("2026-09-10T23:59:00Z", "UTC", zeroNotice, 10000L).amountRubles()).isZero();
    assertThat(assess("2026-09-11T00:00:00Z", "UTC", zeroNotice, 10000L).amountRubles())
        .isEqualTo(10L);
  }

  private static LateChangePolicy fixed(long rubles) {
    return new LateChangePolicy(4, 2, LateChangeFeeMode.FIXED, BigDecimal.valueOf(rubles), null);
  }

  private static CustomerBookingChangePolicy.Assessment assess(
      String now, String zone, LateChangePolicy policy, Long price) {
    return CustomerBookingChangePolicy.assess(policy, DELIVERY, price, zone, Instant.parse(now));
  }
}
