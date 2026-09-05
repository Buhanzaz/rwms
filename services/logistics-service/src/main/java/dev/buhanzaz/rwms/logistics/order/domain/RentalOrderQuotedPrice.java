package dev.buhanzaz.rwms.logistics.order.domain;

/**
 * Immutable monthly whole-RUB price from the customer's exact presentation revision.
 * Both values are null only when that historical presentation never recorded a price.
 * This is an owner-derived fact, never a price accepted from a client command.
 */
public record RentalOrderQuotedPrice(Long pricingVersion, Long monthlyPriceRubles) {
  public RentalOrderQuotedPrice {
    if ((pricingVersion == null) != (monthlyPriceRubles == null)
        || (pricingVersion != null && (pricingVersion < 0 || monthlyPriceRubles < 0))) {
      throw new IllegalArgumentException(
          "Quoted price and revision must be nonnegative or both unknown");
    }
  }
}
