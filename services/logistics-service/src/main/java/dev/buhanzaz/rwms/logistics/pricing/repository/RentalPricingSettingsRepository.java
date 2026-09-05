package dev.buhanzaz.rwms.logistics.pricing.repository;

import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingSettings;
import jakarta.persistence.LockModeType;
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
}
