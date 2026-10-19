package dev.buhanzaz.rwms.logistics.pricing.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * One logistics-owned tariff revision shared by every warehouse. Catalog IDs are references, not
 * locally owned types; absent pairs have a zero monthly price without creating catalog records.
 */
@Entity
@Table(name = "rental_pricing_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalPricingSettings {
  public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Id
  @NotNull
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @PositiveOrZero
  @Column(name = "version", nullable = false)
  private long version;

  @Valid
  @ElementCollection
  @CollectionTable(name = "rental_pricing_rate", joinColumns = @JoinColumn(name = "settings_id"))
  @Getter(AccessLevel.NONE)
  private Set<RentalPricingRate> rates = new LinkedHashSet<>();

  @ElementCollection
  @CollectionTable(
      name = "rental_pricing_equipment_rate",
      joinColumns = @JoinColumn(name = "settings_id"))
  @MapKeyColumn(name = "equipment_id", nullable = false)
  @Column(name = "monthly_price_rubles", nullable = false)
  @Getter(AccessLevel.NONE)
  private Map<@NotNull UUID, @NotNull @Positive Long> equipmentRates = new LinkedHashMap<>();

  @Column(name = "updated_by_subject_id")
  private UUID updatedBySubjectId;

  @NotNull
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** The migration seeds the production singleton; this factory also supports domain tests. */
  public static RentalPricingSettings defaults(OffsetDateTime now) {
    RentalPricingSettings settings = new RentalPricingSettings();
    settings.id = SINGLETON_ID;
    settings.updatedAt = Objects.requireNonNull(now, "now");
    return settings;
  }

  public Set<RentalPricingRate> getRates() {
    return Collections.unmodifiableSet(rates);
  }

  public Map<UUID, Long> getEquipmentRates() {
    return Collections.unmodifiableMap(equipmentRates);
  }

  /** Sets the monthly whole-RUB price of one furniture unit; zero removes only its override. */
  public void setEquipmentMonthlyPrice(
      UUID equipmentId, long monthlyPriceRubles, UUID actorSubjectId, OffsetDateTime now) {
    Objects.requireNonNull(equipmentId, "equipmentId");
    Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    Objects.requireNonNull(now, "now");
    if (monthlyPriceRubles < 0) {
      throw new IllegalArgumentException("Monthly price must be nonnegative whole rubles");
    }
    if (equipmentRates.getOrDefault(equipmentId, 0L) == monthlyPriceRubles) return;
    if (monthlyPriceRubles == 0) {
      equipmentRates.remove(equipmentId);
    } else {
      equipmentRates.put(equipmentId, monthlyPriceRubles);
    }
    updatedBySubjectId = actorSubjectId;
    updatedAt = now;
  }

  /**
   * Sets exactly one pair; explicit zero removes its override and an unchanged price is a no-op.
   */
  public void setMonthlyPrice(
      UUID rentalTypeId,
      UUID categoryId,
      long monthlyPriceRubles,
      UUID actorSubjectId,
      OffsetDateTime now) {
    Objects.requireNonNull(rentalTypeId, "rentalTypeId");
    Objects.requireNonNull(categoryId, "categoryId");
    Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    Objects.requireNonNull(now, "now");
    if (monthlyPriceRubles < 0) {
      throw new IllegalArgumentException("Monthly price must be nonnegative whole rubles");
    }
    long current =
        rates.stream()
            .filter(
                rate ->
                    rentalTypeId.equals(rate.getRentalTypeId())
                        && categoryId.equals(rate.getCategoryId()))
            .mapToLong(RentalPricingRate::getMonthlyPriceRubles)
            .findFirst()
            .orElse(0);
    if (current == monthlyPriceRubles) return;
    rates.removeIf(
        rate ->
            rentalTypeId.equals(rate.getRentalTypeId()) && categoryId.equals(rate.getCategoryId()));
    if (monthlyPriceRubles > 0) {
      rates.add(new RentalPricingRate(rentalTypeId, categoryId, monthlyPriceRubles));
    }
    updatedBySubjectId = actorSubjectId;
    updatedAt = now;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    if (thisClass != otherClass) return false;
    RentalPricingSettings settings = (RentalPricingSettings) other;
    return getId() != null && Objects.equals(getId(), settings.getId());
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
