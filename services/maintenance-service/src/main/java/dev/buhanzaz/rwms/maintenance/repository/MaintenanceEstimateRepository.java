package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data persistence boundary for MaintenanceEstimate; business transitions remain in the owning service. */
public interface MaintenanceEstimateRepository extends JpaRepository<MaintenanceEstimate, UUID> {
  List<MaintenanceEstimate> findAllByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId);

  /** Returns one warehouse-scoped board page before aggregate details are hydrated. */
  @Query(
      """
      select estimate from MaintenanceEstimate estimate
      where estimate.warehouseId = :warehouseId
        and (:state is null or estimate.state = :state)
        and (:rentalItemId is null or estimate.rentalItemId = :rentalItemId)
      order by estimate.createdAt desc, estimate.id desc
      """)
  Page<MaintenanceEstimate> findPageByWarehouseId(
      @Param("warehouseId") UUID warehouseId,
      @Param("state") EstimateState state,
      @Param("rentalItemId") UUID rentalItemId,
      Pageable pageable);
  List<MaintenanceEstimate> findAllByRentalItemIdOrderByCreatedAtAscIdAsc(UUID rentalItemId);
  Optional<MaintenanceEstimate> findByIdAndWarehouseId(UUID id, UUID warehouseId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from MaintenanceEstimate value where value.id = :id")
  Optional<MaintenanceEstimate> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value from MaintenanceEstimate value
       where value.rentalItemId = :rentalItemId
       order by value.id
      """)
  List<MaintenanceEstimate> findAllByRentalItemIdForUpdate(
      @Param("rentalItemId") UUID rentalItemId);
}
