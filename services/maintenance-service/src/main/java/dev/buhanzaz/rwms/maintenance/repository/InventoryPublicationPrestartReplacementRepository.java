package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationPrestartReplacement;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data persistence boundary for InventoryPublicationPrestartReplacement; business transitions remain in the owning service. */
public interface InventoryPublicationPrestartReplacementRepository
    extends JpaRepository<InventoryPublicationPrestartReplacement, InventoryPublicationSourceId> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryPublicationPrestartReplacement value where value.id = :id")
  Optional<InventoryPublicationPrestartReplacement> findByIdForUpdate(
      @Param("id") InventoryPublicationSourceId id);

  @Query(
      """
      select (count(value) > 0)
        from InventoryPublicationPrestartReplacement value
       where value.predecessorRepairId = :repairId
         and value.phase <> 'APPLIED'
      """)
  boolean existsActiveForPredecessorRepairId(@Param("repairId") UUID repairId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value
        from InventoryPublicationPrestartReplacement value
       where value.predecessorRepairId = :repairId
         and value.phase <> 'APPLIED'
      """)
  Optional<InventoryPublicationPrestartReplacement> findActiveForPredecessorRepairIdForUpdate(
      @Param("repairId") UUID repairId);
}
