package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.RentalItemCreationIntent;
import dev.buhanzaz.rwms.asset.domain.RentalItemCreationIntentState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for asset-owned mandatory-photo creation intents. */
public interface RentalItemCreationIntentRepository
    extends JpaRepository<RentalItemCreationIntent, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select intent from RentalItemCreationIntent intent where intent.id = :id")
  Optional<RentalItemCreationIntent> findByIdForUpdate(@Param("id") UUID id);

  Page<RentalItemCreationIntent> findAllByWarehouseIdAndStateOrderByCreatedAtAscIdAsc(
      UUID warehouseId, RentalItemCreationIntentState state, Pageable pageable);

}
