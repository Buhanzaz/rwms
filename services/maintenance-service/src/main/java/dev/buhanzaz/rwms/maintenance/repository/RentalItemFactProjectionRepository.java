package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data persistence boundary for RentalItemFactProjection; business transitions remain in the owning service. */
public interface RentalItemFactProjectionRepository
    extends JpaRepository<RentalItemFactProjection, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from RentalItemFactProjection value where value.rentalItemId = :rentalItemId")
  Optional<RentalItemFactProjection> findByIdForUpdate(@Param("rentalItemId") UUID rentalItemId);
}
