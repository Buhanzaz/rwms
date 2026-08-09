package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory finding persistence.
 */
public interface InventoryFindingRepository extends JpaRepository<InventoryFinding, UUID> {
  Optional<InventoryFinding> findByIdAndInventoryId(UUID id, UUID inventoryId);

  Optional<InventoryFinding> findByIdAndInventoryIdAndMembershipActiveTrue(
      UUID id, UUID inventoryId);

  Optional<InventoryFinding> findByInventoryIdAndIdentityMatchKeyAndMembershipActiveTrue(
      UUID inventoryId, String matchKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select finding from InventoryFinding finding
       where finding.inventoryId = :inventoryId and finding.identityMatchKey = :matchKey
         and finding.membershipActive = true
      """)
  Optional<InventoryFinding> findActiveByInventoryIdAndIdentityMatchKeyForUpdate(
      @Param("inventoryId") UUID inventoryId, @Param("matchKey") String matchKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select finding from InventoryFinding finding, InventorySession session
       where finding.inventoryId = session.id
         and session.lifecycle = dev.buhanzaz.rwms.inventory.domain.SessionLifecycle.ACTIVE
         and finding.assetId = :assetId
         and finding.membershipActive = true
      """)
  List<InventoryFinding> findInActiveSessionsByAssetIdForUpdate(
      @Param("assetId") UUID assetId);

  @Query(
      """
      select finding.inventoryId
        from InventoryFinding finding, InventorySession session
       where finding.id = :findingId
         and session.id = finding.inventoryId
         and session.warehouseId = :warehouseId
      """)
  Optional<UUID> findOwnedInventoryId(
      @Param("findingId") UUID findingId, @Param("warehouseId") UUID warehouseId);

  Page<InventoryFinding> findByInventoryIdAndMembershipActiveTrue(
      UUID inventoryId, Pageable pageable);

  @Query(
      """
      select finding.inventoryId as inventoryId,
             count(finding) as findingCount,
             sum(case when finding.inspection <> :notInspected then 1 else 0 end) as inspectedCount
       from InventoryFinding finding
       where finding.inventoryId in :inventoryIds
         and finding.membershipActive = true
       group by finding.inventoryId
      """)
  List<InventoryFindingCounts> countByInventoryIds(
      @Param("inventoryIds") Set<UUID> inventoryIds,
      @Param("notInspected") InspectionState notInspected);

  List<InventoryFinding> findAllByInventoryIdOrderById(UUID inventoryId);

  List<InventoryFinding> findAllByInventoryIdAndMembershipActiveTrueOrderById(
      UUID inventoryId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select finding from InventoryFinding finding where finding.inventoryId = :inventoryId order by finding.id")
  List<InventoryFinding> findAllByInventoryIdForUpdateOrderById(
      @Param("inventoryId") UUID inventoryId);

  long countByInventoryId(UUID inventoryId);

  long countByInventoryIdAndMembershipActiveTrue(UUID inventoryId);

  interface InventoryFindingCounts {
    UUID getInventoryId();

    long getFindingCount();

    long getInspectedCount();
  }
}
