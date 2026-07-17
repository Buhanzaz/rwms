package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSourceOperation;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSourceOperationId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepairSourceOperationRepository
    extends JpaRepository<InventoryRepairSourceOperation, InventoryRepairSourceOperationId> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryRepairSourceOperation value where value.id = :id")
  Optional<InventoryRepairSourceOperation> findByIdForUpdate(
      @Param("id") InventoryRepairSourceOperationId id);
}
