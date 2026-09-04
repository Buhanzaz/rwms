package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement;
import dev.buhanzaz.rwms.logistics.inquiry.domain.LateChangeFeeMode;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService.LateChangePolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Pure customer-initiated fee assessment; company recovery never enters this policy. */
public final class CustomerBookingChangePolicy {
  private CustomerBookingChangePolicy() {}

  /**
   * Compares calendar dates in the warehouse timezone, not a rolling number of hours. A percentage
   * uses the original confirmed delivery price, rounded HALF_UP once to the contract's whole RUB. A
   * quote expires at the earlier of fifteen minutes and the next warehouse-local midnight.
   */
  public static Assessment assess(
      LateChangePolicy policy,
      LocalDate deliveryDate,
      Long originalPriceRubles,
      String warehouseTimeZone,
      Instant now) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(deliveryDate, "deliveryDate");
    ZoneId zone = ZoneId.of(warehouseTimeZone);
    LocalDate noticeDate = now.atZone(zone).toLocalDate();
    Instant expiry = now.plus(15, ChronoUnit.MINUTES);
    Instant midnight = noticeDate.plusDays(1).atStartOfDay(zone).toInstant();
    if (midnight.isBefore(expiry)) expiry = midnight;
    Long fee = 0L;
    CustomerChangeSettlement settlement = CustomerChangeSettlement.NOT_REQUIRED;
    if (ChronoUnit.DAYS.between(noticeDate, deliveryDate) < policy.noticeDays()) {
      if (policy.feeMode() == null
          || policy.feeValue() == null
          || (policy.feeMode() == LateChangeFeeMode.PERCENT
              && (originalPriceRubles == null || originalPriceRubles < 0))) {
        fee = null;
        settlement = CustomerChangeSettlement.POLICY_UNCONFIGURED;
      } else {
        fee =
            policy.feeMode() == LateChangeFeeMode.FIXED
                ? policy.feeValue().longValueExact()
                : BigDecimal.valueOf(originalPriceRubles)
                    .multiply(policy.feeValue())
                    .movePointLeft(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        settlement =
            fee == 0
                ? CustomerChangeSettlement.NOT_REQUIRED
                : CustomerChangeSettlement.PAYMENT_REQUIRED;
      }
    }
    return new Assessment(noticeDate, fee, settlement, expiry.atOffset(ZoneOffset.UTC));
  }

  /** Immutable calculation, not a payment result or a reservation of replacement capacity. */
  public record Assessment(
      LocalDate noticeDate,
      Long amountRubles,
      CustomerChangeSettlement settlement,
      OffsetDateTime expiresAt) {}
}
