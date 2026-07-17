package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryStartOperation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryStartOperationRepository
    extends JpaRepository<InventoryStartOperation, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryStartOperation value where value.operationId = :operationId")
  Optional<InventoryStartOperation> findByIdForUpdate(@Param("operationId") UUID operationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value from InventoryStartOperation value
       where value.subjectId = :subjectId and value.idempotencyKey = :idempotencyKey
      """)
  Optional<InventoryStartOperation> findByKeyForUpdate(
      @Param("subjectId") UUID subjectId, @Param("idempotencyKey") UUID idempotencyKey);
}
