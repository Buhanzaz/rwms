package dev.buhanzaz.rwms.maintenance.disposition.repository;

import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository boundary for a maintenance-owned disposition decision aggregate. */
public interface PropertyDispositionDecisionRepository
    extends JpaRepository<PropertyDispositionDecision, UUID> {
  Optional<PropertyDispositionDecision> findByIdAndWarehouseId(UUID id, UUID warehouseId);

  Optional<PropertyDispositionDecision> findByAssetKindAndRootRepairId(
      PropertyDispositionAssetKind assetKind, UUID rootRepairId);

  Optional<PropertyDispositionDecision> findByMaintenanceCustodyClaimId(UUID claimId);

  Optional<PropertyDispositionDecision> findBySourceAndInventoryIdAndFindingId(
      PropertyDispositionSource source, UUID inventoryId, UUID findingId);

  Optional<PropertyDispositionDecision> findByInitiatedBySubjectIdAndIdempotencyKey(
      UUID initiatedBySubjectId, UUID idempotencyKey);

  Page<PropertyDispositionDecision> findAllByWarehouseIdAndKindAndState(
      UUID warehouseId,
      PropertyDispositionKind kind,
      PropertyDispositionState state,
      Pageable pageable);

  Page<PropertyDispositionDecision> findAllByWarehouseIdAndKind(
      UUID warehouseId,
      PropertyDispositionKind kind,
      Pageable pageable);

  List<PropertyDispositionDecision> findAllByStateInOrderByCreatedAtAscIdAsc(
      Collection<PropertyDispositionState> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select decision from PropertyDispositionDecision decision
       where decision.id = :id
      """)
  Optional<PropertyDispositionDecision> findByIdForUpdate(@Param("id") UUID id);
}
