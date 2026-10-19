package dev.buhanzaz.rwms.logistics.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable positive monthly rate for two asset-owned catalog identities; zero is omitted. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalPricingRate {
  @NotNull
  @Column(name = "rental_type_id", nullable = false)
  private UUID rentalTypeId;

  @NotNull
  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Positive
  @Column(name = "monthly_price_rubles", nullable = false)
  private long monthlyPriceRubles;

  public RentalPricingRate(UUID rentalTypeId, UUID categoryId, long monthlyPriceRubles) {
    this.rentalTypeId = Objects.requireNonNull(rentalTypeId, "rentalTypeId");
    this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
    if (monthlyPriceRubles <= 0) {
      throw new IllegalArgumentException("A stored monthly price must be positive whole rubles");
    }
    this.monthlyPriceRubles = monthlyPriceRubles;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof RentalPricingRate rate)) return false;
    return monthlyPriceRubles == rate.monthlyPriceRubles
        && Objects.equals(rentalTypeId, rate.rentalTypeId)
        && Objects.equals(categoryId, rate.categoryId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(rentalTypeId, categoryId, monthlyPriceRubles);
  }
}
