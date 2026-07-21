package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderCommandReceiptRepository
    extends JpaRepository<OrderCommandReceipt, UUID> {
  Optional<OrderCommandReceipt> findByActorSubjectIdAndOperationNameAndIdempotencyKey(
      UUID actorSubjectId, String operationName, UUID idempotencyKey);

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
