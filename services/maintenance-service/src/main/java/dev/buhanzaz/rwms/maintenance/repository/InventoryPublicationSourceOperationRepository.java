package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceOperation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryPublicationSourceOperationRepository
    extends JpaRepository<InventoryPublicationSourceOperation, InventoryPublicationSourceId> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryPublicationSourceOperation value where value.id = :id")
  Optional<InventoryPublicationSourceOperation> findByIdForUpdate(
      @Param("id") InventoryPublicationSourceId id);
}
