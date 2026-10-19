package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceOperation;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory asset source operation persistence.
 */
public interface InventoryAssetSourceOperationRepository
    extends JpaRepository<InventoryAssetSourceOperation, InventoryAssetSourceId> {
  /** Insert-only proposal registration; a concurrent exact source can never overwrite its UUID. */
  @Modifying
  @Query(
      value =
          """
          insert into inventory_asset_source_operation(
            inventory_id, finding_id, version, request_fingerprint,
            reserved_rental_item_id, source_plan, proposal_response, created_at)
          values (
            :inventoryId, :findingId, 0, :requestFingerprint,
            :reservedRentalItemId, cast(:sourcePlan as jsonb),
            cast(:proposalResponse as jsonb), current_timestamp)
          on conflict (inventory_id, finding_id) do nothing
          """,
      nativeQuery = true)
  int insertProposal(
      @Param("inventoryId") UUID inventoryId,
      @Param("findingId") UUID findingId,
      @Param("requestFingerprint") String requestFingerprint,
      @Param("reservedRentalItemId") UUID reservedRentalItemId,
      @Param("sourcePlan") String sourcePlan,
      @Param("proposalResponse") String proposalResponse);

  @Query("select value.requestFingerprint from InventoryAssetSourceOperation value where value.id = :id")
  Optional<String> findRequestFingerprintById(@Param("id") InventoryAssetSourceId id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryAssetSourceOperation value where value.id = :id")
  Optional<InventoryAssetSourceOperation> findByIdForUpdate(
      @Param("id") InventoryAssetSourceId id);

  Optional<InventoryAssetSourceOperation> findByReservedRentalItemId(UUID reservedRentalItemId);

  List<InventoryAssetSourceOperation> findAllByReservedRentalItemIdIn(
      Collection<UUID> reservedRentalItemIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select value from InventoryAssetSourceOperation value
      where value.id.inventoryId = :inventoryId
      order by value.id.findingId
      """)
  List<InventoryAssetSourceOperation> findAllByInventoryIdForUpdate(
      @Param("inventoryId") UUID inventoryId);
}
