package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.TransferPlan;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanState;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanWorkflowState;
import dev.buhanzaz.rwms.logistics.domain.TransferReservationReadiness;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for the planning projection of an existing transfer document. */
public interface TransferPlanRepository extends JpaRepository<TransferPlan, UUID> {
  Optional<TransferPlan> findByDocument_Id(UUID documentId);

  /** Locks the complete planning aggregate before a fenced draft mutation or confirmation. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select plan from TransferPlan plan where plan.document.id = :documentId")
  Optional<TransferPlan> findForUpdateByDocumentId(@Param("documentId") UUID documentId);

  /**
   * Returns exact ready transfer cargo that can share one already calculated positioning leg.
   * Cargo collections are materialized inside the read transaction; the caller never mutates them.
   */
  @EntityGraph(attributePaths = {"document", "cabinGroups"})
  @Query(
      """
      select distinct plan
        from TransferPlan plan
       where plan.state = :state
         and plan.reservationReadiness = :reservationReadiness
         and plan.workflowState = :workflowState
         and plan.tripDriverId = :driverId
         and plan.tripVehicleId = :vehicleId
         and plan.plannedDepartureAt = :departureAt
         and plan.plannedArrivalAt = :arrivalAt
         and plan.document.warehouseId = :sourceWarehouseId
         and plan.document.destinationWarehouseId = :destinationWarehouseId
         and (plan.cabinGroups is not empty or plan.looseFurniture is not empty)
       order by plan.createdAt, plan.id
      """)
  List<TransferPlan> findRouteReadyCargo(
      @Param("state") TransferPlanState state,
      @Param("reservationReadiness") TransferReservationReadiness reservationReadiness,
      @Param("workflowState") TransferPlanWorkflowState workflowState,
      @Param("driverId") UUID driverId,
      @Param("vehicleId") UUID vehicleId,
      @Param("departureAt") OffsetDateTime departureAt,
      @Param("arrivalAt") OffsetDateTime arrivalAt,
      @Param("sourceWarehouseId") UUID sourceWarehouseId,
      @Param("destinationWarehouseId") UUID destinationWarehouseId);
}
