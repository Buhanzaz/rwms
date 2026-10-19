package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Order Command Receipt Repository; it does not own cross-service workflow decisions.
 */
public interface OrderCommandReceiptRepository
    extends JpaRepository<OrderCommandReceipt, UUID> {
  Optional<OrderCommandReceipt> findByActorSubjectIdAndOperationNameAndIdempotencyKey(
      UUID actorSubjectId, String operationName, UUID idempotencyKey);

}
