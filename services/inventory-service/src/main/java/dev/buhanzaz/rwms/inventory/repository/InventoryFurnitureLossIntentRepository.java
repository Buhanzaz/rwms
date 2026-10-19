package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.FurnitureLossIntentState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureLossIntent;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory furniture loss intent persistence.
 */
public interface InventoryFurnitureLossIntentRepository
    extends JpaRepository<InventoryFurnitureLossIntent, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select intent from InventoryFurnitureLossIntent intent where intent.findingId = :findingId")
  Optional<InventoryFurnitureLossIntent> findByFindingIdForUpdate(
      @Param("findingId") UUID findingId);

  List<InventoryFurnitureLossIntent> findAllByInventoryIdOrderByFindingIdAsc(UUID inventoryId);

  List<InventoryFurnitureLossIntent>
      findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscFindingIdAsc(
          Collection<FurnitureLossIntentState> states, OffsetDateTime now);
}
