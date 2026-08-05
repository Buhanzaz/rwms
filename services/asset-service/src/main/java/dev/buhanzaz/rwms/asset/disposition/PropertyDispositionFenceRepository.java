package dev.buhanzaz.rwms.asset.disposition;

import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionFenceState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyDispositionFenceRepository
    extends JpaRepository<PropertyDispositionFence, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select fence from PropertyDispositionFence fence where fence.decisionId = :decisionId")
  Optional<PropertyDispositionFence> findByDecisionIdForUpdate(
      @Param("decisionId") UUID decisionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select fence
      from PropertyDispositionFence fence
      where fence.assetKind = :assetKind
        and fence.assetId = :assetId
        and fence.warehouseId = :warehouseId
        and fence.state = :state
        and fence.maintenanceCustodyClaimId is null
      """)
  Optional<PropertyDispositionFence> findByPhysicalAssetForUpdate(
      @Param("assetKind") PropertyAssetKind assetKind,
      @Param("assetId") UUID assetId,
      @Param("warehouseId") UUID warehouseId,
      @Param("state") PropertyDispositionFenceState state);
}
