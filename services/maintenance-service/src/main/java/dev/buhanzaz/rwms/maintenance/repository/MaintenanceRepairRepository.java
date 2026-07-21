package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface MaintenanceRepairRepository extends JpaRepository<MaintenanceRepair, UUID> {
  List<MaintenanceRepair> findAllByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId);
  Optional<MaintenanceRepair> findByIdAndWarehouseId(UUID id, UUID warehouseId);
  Optional<MaintenanceRepair> findByExternalTaskId(UUID externalTaskId);
  List<MaintenanceRepair> findAllByLeaseId(UUID leaseId);
  boolean existsBySourceRepairIdAndExecutionStateIn(
      UUID sourceRepairId,
      java.util.Collection<dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from MaintenanceRepair value where value.id in :ids order by value.id")
  List<MaintenanceRepair> findAllByIdForUpdate(@Param("ids") java.util.Collection<UUID> ids);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select repair from MaintenanceRepair repair
       where repair.kind = dev.buhanzaz.rwms.maintenance.domain.RepairKind.PRIMARY
         and repair.leaseReconciliationState = 'ACTIVE'
         and repair.executionState in (
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.QUEUED,
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.IN_PROGRESS,
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.COMPLETED,
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.CANCELLED)
         and repair.acceptanceState not in (
           dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.ACCEPTED,
           dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.WRITTEN_OFF)
         and repair.leaseExpiresAt <= :deadline
         and not exists (
           select reconciliation.id from MaintenanceReconciliation reconciliation
            where reconciliation.repairId = repair.id
              and reconciliation.operationType = 'RENEW_LEASE'
              and reconciliation.state in (
                'PENDING', 'RETRY_PENDING', 'RECONCILIATION_REQUIRED', 'QUARANTINED'))
       order by repair.leaseExpiresAt, repair.id
      """)
  List<MaintenanceRepair> findLeaseRenewalCandidateForUpdateSkipLocked(
      @Param("deadline") OffsetDateTime deadline, Pageable pageable);
}
