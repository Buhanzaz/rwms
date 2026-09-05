package dev.buhanzaz.rwms.logistics.order.api;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentSource;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Payment projection for {@link RentalOrder}, including a frozen non-fiscal bill and database time.
 * Null state/receipt remain explicit for drafts and historical orders; reads never start a timer.
 */
public record OrderPaymentResponse(
    UUID orderId,
    long orderVersion,
    RentalOrderStatus orderStatus,
    RentalOrderPaymentState state,
    OffsetDateTime startedAt,
    OffsetDateTime expiresAt,
    OffsetDateTime resolvedAt,
    RentalOrderPaymentSource source,
    OffsetDateTime serverTime,
    boolean canConfirm,
    Receipt receipt) {
  /**
   * Immutable initial bill; money is whole RUB encoded as exact decimal strings, not floating point.
   */
  public record Receipt(
      int schemaVersion,
      UUID orderId,
      String orderNumber,
      OffsetDateTime issuedAt,
      String currency,
      boolean deliveryIncluded,
      List<Line> lines,
      String totalRubles) {}

  /**
   * One quantity × monthly unit price × duration; DELIVERY is charged once and has no rental months.
   */
  public record Line(
      String kind,
      UUID rentalItemId,
      UUID equipmentId,
      String label,
      String quantity,
      Long rentalMonths,
      String unitPriceRubles,
      String amountRubles,
      Long pricingVersion) {}
}
