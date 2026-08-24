package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Rental Order Repository; it does not own
 * cross-service workflow decisions.
 */
public interface RentalOrderRepository
    extends JpaRepository<RentalOrder, UUID>, JpaSpecificationExecutor<RentalOrder> {
  @Override
  @EntityGraph(attributePaths = "client")
  Page<RentalOrder> findAll(Specification<RentalOrder> specification, Pageable pageable);

  @EntityGraph(attributePaths = "client")
  @Query("select orders from RentalOrder orders where orders.id = :id")
  Optional<RentalOrder> findWithClientById(@Param("id") UUID id);

  @EntityGraph(attributePaths = "client")
  @Query("select orders from RentalOrder orders where orders.id in :ids")
  List<RentalOrder> findAllWithClientByIdIn(@Param("ids") java.util.Collection<UUID> ids);

  /** Loads saved orders eligible for external route planning in deterministic creation order. */
  @EntityGraph(attributePaths = {"client", "desiredDeliveryWindows"})
  @Query(
      """
      select distinct orders
      from RentalOrder orders
      where orders.warehouseId = :warehouseId
        and orders.status = :status
      order by orders.createdAt, orders.id
      """)
  List<RentalOrder> findAllPlanningCandidates(
      @Param("warehouseId") UUID warehouseId, @Param("status") RentalOrderStatus status);

  /** Loads one order and every fact required to fence a planner assignment. */
  @EntityGraph(attributePaths = {"client", "desiredDeliveryWindows"})
  @Query("select orders from RentalOrder orders where orders.id = :id")
  Optional<RentalOrder> findPlanningCandidateById(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = "client")
  @Query("select orders from RentalOrder orders where orders.id = :id")
  Optional<RentalOrder> findForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select orders from RentalOrder orders join fetch orders.client where orders.id in :ids order"
          + " by orders.id")
  List<RentalOrder> findAllForUpdateByIdIn(@Param("ids") Collection<UUID> ids);

  Optional<RentalOrder> findByCreatedBySubjectIdAndCreationIdempotencyKey(
      UUID createdBySubjectId, UUID creationIdempotencyKey);

  @Query("select function('nextval', 'rental_order_number_seq')")
  long nextOrderNumber();
}
