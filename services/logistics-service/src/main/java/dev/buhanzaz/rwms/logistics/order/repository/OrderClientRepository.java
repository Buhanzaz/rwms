package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data persistence boundary for logistics-owned Order Client Repository; it does not own cross-service workflow decisions.
 */
public interface OrderClientRepository
    extends JpaRepository<OrderClient, UUID>, JpaSpecificationExecutor<OrderClient> {
  Optional<OrderClient> findByClientTypeAndNormalizedPhone(
      ClientType clientType, String normalizedPhone);

  Optional<OrderClient> findByCreatedBySubjectIdAndCreationIdempotencyKey(
      UUID createdBySubjectId, UUID creationIdempotencyKey);

}
