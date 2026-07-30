package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderClientRepository
    extends JpaRepository<OrderClient, UUID>, JpaSpecificationExecutor<OrderClient> {
  Optional<OrderClient> findByClientTypeAndNormalizedName(
      ClientType clientType, String normalizedName);

  Optional<OrderClient> findByClientTypeAndNormalizedPhone(
      ClientType clientType, String normalizedPhone);

  Optional<OrderClient> findByCreatedBySubjectIdAndCreationIdempotencyKey(
      UUID createdBySubjectId, UUID creationIdempotencyKey);

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
