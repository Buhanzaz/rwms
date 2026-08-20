package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeTarget;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for replay-safe predecessor supersession effects. */
public interface InventoryAuthoritativeOutcomeTargetRepository
    extends JpaRepository<InventoryAuthoritativeOutcomeTarget, UUID> {
  boolean existsByTargetKindAndTargetIdAndLocalSupersededFalse(
      String targetKind, UUID targetId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select value from InventoryAuthoritativeOutcomeTarget value"
          + " where value.targetKind = 'REPAIR' and value.targetId = :repairId"
          + " and value.localSuperseded = false")
  Optional<InventoryAuthoritativeOutcomeTarget> findActiveRepairTargetForUpdate(
      @Param("repairId") UUID repairId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value from InventoryAuthoritativeOutcomeTarget value
       where value.inventoryId = :inventoryId
         and value.finalPlanVersion = :finalPlanVersion
         and value.findingId = :findingId
       order by value.targetKind, value.targetId
      """)
  List<InventoryAuthoritativeOutcomeTarget> findAllBySourceForUpdate(
      @Param("inventoryId") UUID inventoryId,
      @Param("finalPlanVersion") long finalPlanVersion,
      @Param("findingId") UUID findingId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryAuthoritativeOutcomeTarget value where value.id = :id")
  Optional<InventoryAuthoritativeOutcomeTarget> findByIdForUpdate(@Param("id") UUID id);
}
