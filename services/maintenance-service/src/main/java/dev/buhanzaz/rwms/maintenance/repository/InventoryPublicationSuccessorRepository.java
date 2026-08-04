package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSuccessor;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryPublicationSuccessorRepository
    extends JpaRepository<InventoryPublicationSuccessor, InventoryPublicationSourceId> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value from InventoryPublicationSuccessor value
       where value.predecessorRepairId = :predecessorRepairId
         and value.state = 'WAITING_PREDECESSOR'
       order by value.id.inventoryId, value.id.finalPlanVersion, value.id.findingId
      """)
  List<InventoryPublicationSuccessor> findWaitingByPredecessorRepairIdForUpdate(
      @Param("predecessorRepairId") UUID predecessorRepairId);

  Optional<InventoryPublicationSuccessor> findById(InventoryPublicationSourceId id);
}
