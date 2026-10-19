package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionReview;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for inventory-owned cabin disposition review heads. */
public interface InventoryCabinDispositionReviewRepository
    extends JpaRepository<InventoryCabinDispositionReview, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select review from InventoryCabinDispositionReview review where review.inventoryId = :inventoryId")
  Optional<InventoryCabinDispositionReview> findByInventoryIdForUpdate(
      @Param("inventoryId") UUID inventoryId);
}
