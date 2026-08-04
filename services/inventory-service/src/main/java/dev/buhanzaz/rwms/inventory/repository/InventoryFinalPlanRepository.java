package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryFinalPlanRepository extends JpaRepository<InventoryFinalPlan, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select plan from InventoryFinalPlan plan where plan.inventoryId = :inventoryId")
  Optional<InventoryFinalPlan> findByInventoryIdForUpdate(@Param("inventoryId") UUID inventoryId);
}
