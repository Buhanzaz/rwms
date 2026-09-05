package dev.buhanzaz.rwms.logistics.order.domain;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, non-fiscal initial bill for one order. Whole-RUB money and quantities use exact
 * decimal strings so the persisted bill and its clients never round through floating point.
 */
public record RentalOrderReceiptData(
    int schemaVersion,
    UUID orderId,
    String orderNumber,
    OffsetDateTime issuedAt,
    String currency,
    boolean deliveryIncluded,
    List<Line> lines,
    String totalRubles) {
  public RentalOrderReceiptData {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(issuedAt, "issuedAt");
    if (schemaVersion != 1
        || !"RUB".equals(currency)
        || orderNumber == null
        || orderNumber.isBlank()) {
      throw new IllegalArgumentException("Receipt identity is invalid");
    }
    lines = List.copyOf(lines);
    if (lines.isEmpty()
        || lines.stream().noneMatch(line -> line.kind() == Kind.CABIN)
        || lines.stream().filter(line -> line.kind() == Kind.DELIVERY).count() > 1
        || deliveryIncluded != lines.stream().anyMatch(line -> line.kind() == Kind.DELIVERY)) {
      throw new IllegalArgumentException("Receipt composition is invalid");
    }
    BigInteger total =
        lines.stream()
            .map(line -> amount(line.amountRubles()))
            .reduce(BigInteger.ZERO, BigInteger::add);
    if (!total.equals(amount(totalRubles))) {
      throw new IllegalArgumentException("Receipt total does not match its lines");
    }
  }

  /** An initial bill contains rentals and, only when actually quoted, one delivery charge. */
  public enum Kind {
    CABIN,
    FURNITURE,
    DELIVERY
  }

  /** One exact quantity × unit price × rental duration; delivery has no monthly multiplier. */
  public record Line(
      Kind kind,
      UUID rentalItemId,
      UUID equipmentId,
      String label,
      String quantity,
      Long rentalMonths,
      String unitPriceRubles,
      String amountRubles,
      Long pricingVersion) {
    public Line {
      Objects.requireNonNull(kind, "kind");
      if (label == null || label.isBlank() || label.length() > 600) {
        throw new IllegalArgumentException("Receipt line label is invalid");
      }
      BigInteger count = amount(quantity);
      BigInteger unitPrice = amount(unitPriceRubles);
      if (count.signum() <= 0
          || count.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
          || unitPrice.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
        throw new IllegalArgumentException("Receipt line quantity or unit price is invalid");
      }
      if (kind == Kind.DELIVERY) {
        if (rentalItemId != null
            || equipmentId != null
            || rentalMonths != null
            || pricingVersion != null
            || !count.equals(BigInteger.ONE)) {
          throw new IllegalArgumentException("Delivery receipt line is invalid");
        }
      } else if (rentalItemId == null
          || rentalMonths == null
          || rentalMonths < 1
          || rentalMonths > 120
          || pricingVersion == null
          || pricingVersion < 0
          || (kind == Kind.CABIN && (equipmentId != null || !count.equals(BigInteger.ONE)))
          || (kind == Kind.FURNITURE && equipmentId == null)) {
        throw new IllegalArgumentException("Rental receipt line is invalid");
      }
      BigInteger expected =
          count
              .multiply(unitPrice)
              .multiply(BigInteger.valueOf(rentalMonths == null ? 1 : rentalMonths));
      if (!expected.equals(amount(amountRubles))) {
        throw new IllegalArgumentException("Receipt line amount does not match its factors");
      }
    }
  }

  private static BigInteger amount(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,79}")) {
      throw new IllegalArgumentException(
          "Receipt amounts must be exact nonnegative decimal integers");
    }
    return new BigInteger(value);
  }
}
