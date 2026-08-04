package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureReconciliationIntent;
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

public interface InventoryFurnitureReconciliationIntentRepository
    extends JpaRepository<InventoryFurnitureReconciliationIntent, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select intent from InventoryFurnitureReconciliationIntent intent where intent.inventoryId = :inventoryId")
  Optional<InventoryFurnitureReconciliationIntent> findByInventoryIdForUpdate(
      @Param("inventoryId") UUID inventoryId);

  List<InventoryFurnitureReconciliationIntent>
      findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscInventoryIdAsc(
          Collection<FurnitureReconciliationState> states, OffsetDateTime now);
}
