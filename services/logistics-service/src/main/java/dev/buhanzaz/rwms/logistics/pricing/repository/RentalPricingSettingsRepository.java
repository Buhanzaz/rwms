package dev.buhanzaz.rwms.logistics.pricing.repository;

import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingSettings;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locks the seeded tariff owner so edits to different rows still share one version fence. */
public interface RentalPricingSettingsRepository
    extends JpaRepository<RentalPricingSettings, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select settings from RentalPricingSettings settings where settings.id = :id")
  Optional<RentalPricingSettings> findForUpdate(@Param("id") UUID id);

  /** One statement keeps a tariff revision and its prices consistent in an enclosing order write. */
  @Query(
      value =
          """
          select settings.version as pricingVersion, rates.equipment_id as equipmentId,
                 rates.monthly_price_rubles as monthlyPriceRubles
          from rental_pricing_settings settings
          left join rental_pricing_equipment_rate rates on rates.settings_id = settings.id
          where settings.id = :id
          order by rates.equipment_id
          """,
      nativeQuery = true)
  List<EquipmentReceiptRate> readEquipmentReceiptRates(@Param("id") UUID id);

  /** A null furniture pair is the left-join evidence of an existing empty tariff revision. */
  interface EquipmentReceiptRate {
    Long getPricingVersion();

    UUID getEquipmentId();

    Long getMonthlyPriceRubles();
  }
}
