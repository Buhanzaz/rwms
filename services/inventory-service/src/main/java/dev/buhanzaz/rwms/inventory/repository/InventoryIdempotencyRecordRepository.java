package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryIdempotencyRecord;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory idempotency record persistence.
 */
public interface InventoryIdempotencyRecordRepository
    extends JpaRepository<InventoryIdempotencyRecord, InventoryIdempotencyRecord.Key> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value from InventoryIdempotencyRecord value
       where value.subjectId = :subjectId
         and value.commandScope = :commandScope
         and value.idempotencyKey = :idempotencyKey
      """)
  Optional<InventoryIdempotencyRecord> findForUpdate(
      @Param("subjectId") UUID subjectId,
      @Param("commandScope") String commandScope,
      @Param("idempotencyKey") UUID idempotencyKey);
}
