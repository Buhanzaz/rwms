package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;

public interface RentalOrderRepository
    extends JpaRepository<RentalOrder, UUID>, JpaSpecificationExecutor<RentalOrder> {
  @Override
  @EntityGraph(attributePaths = "client")
  Page<RentalOrder> findAll(Specification<RentalOrder> specification, Pageable pageable);

  @EntityGraph(attributePaths = "client")
  @Query("select orders from RentalOrder orders where orders.id = :id")
  Optional<RentalOrder> findWithClientById(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = "client")
  @Query("select orders from RentalOrder orders where orders.id = :id")
  Optional<RentalOrder> findForUpdate(@Param("id") UUID id);

  Optional<RentalOrder> findByCreatedBySubjectIdAndCreationIdempotencyKey(
      UUID createdBySubjectId, UUID creationIdempotencyKey);

  @Query(value = "select nextval('rental_order_number_seq')", nativeQuery = true)
  long nextOrderNumber();

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
