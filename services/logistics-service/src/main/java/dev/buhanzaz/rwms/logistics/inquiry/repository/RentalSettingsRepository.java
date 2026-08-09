package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalSettings;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Rental Settings Repository; it does not own cross-service workflow decisions.
 */
public interface RentalSettingsRepository extends JpaRepository<RentalSettings, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select settings from RentalSettings settings where settings.id = :id")
  Optional<RentalSettings> findForUpdate(@Param("id") UUID id);
}
