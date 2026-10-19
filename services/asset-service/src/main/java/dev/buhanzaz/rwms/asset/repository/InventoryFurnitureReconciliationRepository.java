package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryFurnitureReconciliation;
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

/** JPA-owned source identity plus narrow locked checks for foreign aggregate guards. */
public interface InventoryFurnitureReconciliationRepository
    extends JpaRepository<InventoryFurnitureReconciliation, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select reconciliation from InventoryFurnitureReconciliation reconciliation where reconciliation.inventoryId = :inventoryId")
  Optional<InventoryFurnitureReconciliation> findByInventoryIdForUpdate(
      @Param("inventoryId") UUID inventoryId);

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);

  @Query(
      value =
          """
          select hold.id
          from equipment_allocation_hold hold
          where hold.warehouse_id = :warehouseId
            and hold.equipment_id in (:equipmentIds)
            and (
              hold.state = 'COMMITTED'
              or (hold.state = 'ACTIVE' and hold.expires_at > :now)
            )
            and (
              hold.source_balance_id in (:balanceIds)
              or hold.source_balance_id is null
            )
          order by hold.id
          for update
          """,
      nativeQuery = true)
  List<UUID> lockLiveEquipmentHoldIds(
      @Param("warehouseId") UUID warehouseId,
      @Param("equipmentIds") Collection<UUID> equipmentIds,
      @Param("balanceIds") Collection<UUID> balanceIds,
      @Param("now") OffsetDateTime now);
}
