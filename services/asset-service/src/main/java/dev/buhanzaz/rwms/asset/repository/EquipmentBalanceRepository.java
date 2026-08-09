package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentBalance;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local equipment balance persistence.
 */
public interface EquipmentBalanceRepository extends JpaRepository<EquipmentBalance, UUID> {
  List<EquipmentBalance> findAllByRentalItemIdInAndQuantityGreaterThanAndLocationKindIn(
      Collection<UUID> rentalItemIds,
      long minimumQuantity,
      Collection<BalanceLocationKind> locationKinds);

  @Query(
      """
      select balance
      from EquipmentBalance balance
      where balance.warehouseId = :warehouseId
        and balance.equipmentId in :equipmentIds
        and balance.locationKind in :locationKinds
        and (balance.rentalItemId is null or balance.rentalItemId in :rentalItemIds)
      order by balance.equipmentId, balance.rentalItemId, balance.locationKind
      """)
  List<EquipmentBalance> findFurnitureScope(
      @Param("warehouseId") UUID warehouseId,
      @Param("equipmentIds") Collection<UUID> equipmentIds,
      @Param("rentalItemIds") Collection<UUID> rentalItemIds,
      @Param("locationKinds") Collection<BalanceLocationKind> locationKinds);

  @Query(
      """
      select balance
      from EquipmentBalance balance
      where balance.warehouseId = :warehouseId
        and balance.equipmentId in :equipmentIds
        and balance.rentalItemId is null
        and balance.locationKind = dev.buhanzaz.rwms.asset.domain.BalanceLocationKind.STOCK
      order by balance.equipmentId
      """)
  List<EquipmentBalance> findFurnitureStockScope(
      @Param("warehouseId") UUID warehouseId,
      @Param("equipmentIds") Collection<UUID> equipmentIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select balance
      from EquipmentBalance balance
      where balance.warehouseId = :warehouseId
        and balance.equipmentId in :equipmentIds
        and balance.locationKind in :locationKinds
        and (balance.rentalItemId is null or balance.rentalItemId in :rentalItemIds)
      order by balance.equipmentId, balance.rentalItemId, balance.locationKind
      """)
  List<EquipmentBalance> findFurnitureScopeForUpdate(
      @Param("warehouseId") UUID warehouseId,
      @Param("equipmentIds") Collection<UUID> equipmentIds,
      @Param("rentalItemIds") Collection<UUID> rentalItemIds,
      @Param("locationKinds") Collection<BalanceLocationKind> locationKinds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select balance
      from EquipmentBalance balance
      where balance.warehouseId = :warehouseId
        and balance.equipmentId in :equipmentIds
        and balance.rentalItemId is null
        and balance.locationKind = dev.buhanzaz.rwms.asset.domain.BalanceLocationKind.STOCK
      order by balance.equipmentId
      """)
  List<EquipmentBalance> findFurnitureStockScopeForUpdate(
      @Param("warehouseId") UUID warehouseId,
      @Param("equipmentIds") Collection<UUID> equipmentIds);
}
