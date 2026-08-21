package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcome;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for durable authoritative inventory outcome coordinators. */
public interface InventoryAuthoritativeOutcomeRepository
    extends JpaRepository<InventoryAuthoritativeOutcome, InventoryPublicationSourceId> {
  /**
   * Resolves the newest applied inventory finding that currently owns a retained repair target.
   * Corrected completed plans deliberately retain the same target in more than one historical
   * coordinator, so an unbounded single-result lookup is invalid.
   */
  default Optional<InventoryAuthoritativeOutcome> findNewestAppliedByTargetRepairId(
      UUID targetRepairId) {
    return findNewestAppliedByTargetRepairId(targetRepairId, Pageable.ofSize(1)).stream()
        .findFirst();
  }

  @Query(
      """
      select value from InventoryAuthoritativeOutcome value
       where value.targetRepairId = :targetRepairId
         and value.phase = 'APPLIED'
       order by value.inventoryCompletedAt desc, value.id.finalPlanVersion desc
      """)
  List<InventoryAuthoritativeOutcome> findNewestAppliedByTargetRepairId(
      @Param("targetRepairId") UUID targetRepairId, Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryAuthoritativeOutcome value where value.id = :id")
  Optional<InventoryAuthoritativeOutcome> findByIdForUpdate(
      @Param("id") InventoryPublicationSourceId id);

  /** Locks a bounded newest lower plan version for same-finding correction recovery. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value from InventoryAuthoritativeOutcome value
       where value.id.inventoryId = :inventoryId
         and value.id.findingId = :findingId
         and value.id.finalPlanVersion < :finalPlanVersion
       order by value.id.finalPlanVersion desc
      """)
  List<InventoryAuthoritativeOutcome> findPreviousForUpdate(
      @Param("inventoryId") UUID inventoryId,
      @Param("findingId") UUID findingId,
      @Param("finalPlanVersion") long finalPlanVersion,
      Pageable page);
}
