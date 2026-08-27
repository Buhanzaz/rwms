package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Spring Data persistence boundary for MaintenanceRepair; business transitions remain in the owning service. */
public interface MaintenanceRepairRepository
    extends JpaRepository<MaintenanceRepair, UUID>, JpaSpecificationExecutor<MaintenanceRepair> {
  List<MaintenanceRepair> findAllByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId);
  List<MaintenanceRepair> findAllByWarehouseIdAndExecutionStateInOrderByCreatedAtAscIdAsc(
      UUID warehouseId,
      java.util.Collection<dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState> states);
  Optional<MaintenanceRepair> findByIdAndWarehouseId(UUID id, UUID warehouseId);
  List<MaintenanceRepair> findAllByRentalItemIdOrderByCreatedAtAscIdAsc(UUID rentalItemId);
  List<MaintenanceRepair> findAllByTransferLineIdOrderByCreatedAtAscIdAsc(UUID transferLineId);
  @Query(
      """
      select repair from MaintenanceRepair repair
       where repair.id = :rootRepairId
          or repair.rootRepairId = :rootRepairId
       order by repair.createdAt, repair.id
      """)
  List<MaintenanceRepair> findRepairChain(@Param("rootRepairId") UUID rootRepairId);

  /** Fetches all chains shown on a decision page in one query, avoiding decision-row N+1 reads. */
  @Query(
      """
      select repair from MaintenanceRepair repair
       where repair.id in :rootRepairIds
          or repair.rootRepairId in :rootRepairIds
       order by repair.rootRepairId, repair.createdAt, repair.id
      """)
  List<MaintenanceRepair> findAllForDispositionRoots(
      @Param("rootRepairIds") java.util.Collection<UUID> rootRepairIds);
  Optional<MaintenanceRepair> findByExternalTaskId(UUID externalTaskId);
  List<MaintenanceRepair> findAllByLeaseId(UUID leaseId);
  @Query(
      """
      select repair from MaintenanceRepair repair
       where repair.rentalItemId in :rentalItemIds
       order by repair.rentalItemId, repair.id
      """)
  List<MaintenanceRepair> findAllByRentalItemIdInOrderByRentalItemIdAscIdAsc(
      @Param("rentalItemIds") java.util.Collection<UUID> rentalItemIds);
  boolean existsBySourceRepairIdAndExecutionStateIn(
      UUID sourceRepairId,
      java.util.Collection<dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState> states);
  boolean existsBySourceRepairIdAndAcceptanceStateIn(
      UUID sourceRepairId,
      java.util.Collection<dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState> states);

  /**
   * Reads one actionable acceptance page entirely in PostgreSQL. Inventory-origin work is
   * actionable only when every persisted route stage has a task-board completion identity; a
   * source blocked by an unresolved child rework is excluded by the same query.
   */
  @Query(
      value =
          """
          select repair from MaintenanceRepair repair
           where repair.warehouseId = :warehouseId
             and (:repairId is null or repair.id = :repairId)
             and repair.acceptanceState in (
               dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.PENDING,
               dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.IN_REWORK)
             and (:state is null or repair.acceptanceState = :state)
             and (
               repair.origin <> dev.buhanzaz.rwms.maintenance.domain.RepairOrigin.INVENTORY
               or (
                 exists (
                   select completedStage.id from RepairStage completedStage
                    where completedStage.repairId = repair.id)
                 and not exists (
                   select incompleteStage.id from RepairStage incompleteStage
                    where incompleteStage.repairId = repair.id
                      and (
                        incompleteStage.state <>
                          dev.buhanzaz.rwms.maintenance.domain.RepairStageState.DONE
                        or incompleteStage.externalQueueEntryId is null
                        or incompleteStage.taskBoardVersion is null
                        or incompleteStage.completedEventId is null
                        or incompleteStage.completedAt is null)))
             )
             and not exists (
               select child.id from MaintenanceRepair child
                where child.sourceRepairId = repair.id
                  and (
                    child.executionState in (
                      dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.DRAFT,
                      dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.QUEUED,
                      dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.IN_PROGRESS)
                    or child.acceptanceState in (
                      dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.PENDING,
                      dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.IN_REWORK)))
          """,
      countQuery =
          """
          select count(repair) from MaintenanceRepair repair
           where repair.warehouseId = :warehouseId
             and (:repairId is null or repair.id = :repairId)
             and repair.acceptanceState in (
               dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.PENDING,
               dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.IN_REWORK)
             and (:state is null or repair.acceptanceState = :state)
             and (
               repair.origin <> dev.buhanzaz.rwms.maintenance.domain.RepairOrigin.INVENTORY
               or (
                 exists (
                   select completedStage.id from RepairStage completedStage
                    where completedStage.repairId = repair.id)
                 and not exists (
                   select incompleteStage.id from RepairStage incompleteStage
                    where incompleteStage.repairId = repair.id
                      and (
                        incompleteStage.state <>
                          dev.buhanzaz.rwms.maintenance.domain.RepairStageState.DONE
                        or incompleteStage.externalQueueEntryId is null
                        or incompleteStage.taskBoardVersion is null
                        or incompleteStage.completedEventId is null
                        or incompleteStage.completedAt is null)))
             )
             and not exists (
               select child.id from MaintenanceRepair child
                where child.sourceRepairId = repair.id
                  and (
                    child.executionState in (
                      dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.DRAFT,
                      dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.QUEUED,
                      dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.IN_PROGRESS)
                    or child.acceptanceState in (
                      dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.PENDING,
                      dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.IN_REWORK)))
          """)
  Page<MaintenanceRepair> findAcceptancePage(
      @Param("warehouseId") UUID warehouseId,
      @Param("repairId") UUID repairId,
      @Param("state")
          dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState state,
      Pageable pageable);

  /** Locks active primary roots used to enforce the one-active-repair-chain invariant. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select repair from MaintenanceRepair repair
       where repair.rentalItemId = :rentalItemId
         and repair.kind = dev.buhanzaz.rwms.maintenance.domain.RepairKind.PRIMARY
         and repair.executionState <>
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.CANCELLED
         and repair.acceptanceState not in (
           dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.ACCEPTED,
           dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.WRITTEN_OFF)
       order by repair.createdAt, repair.id
      """)
  List<MaintenanceRepair> findActivePrimaryRootsForUpdate(
      @Param("rentalItemId") UUID rentalItemId,
      Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query(
      """
      select repair from MaintenanceRepair repair
       where repair.rentalItemId = :rentalItemId
         and repair.id <> :excludedRepairId
         and repair.kind = dev.buhanzaz.rwms.maintenance.domain.RepairKind.PRIMARY
         and repair.executionState in (
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.QUEUED,
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.IN_PROGRESS,
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.COMPLETED,
           dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState.CANCELLED)
         and repair.acceptanceState not in (
           dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.ACCEPTED,
           dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState.WRITTEN_OFF)
         and repair.leaseId is not null
         and repair.leaseReconciliationState in ('ACTIVE', 'RECONCILIATION_REQUIRED')
       order by repair.createdAt, repair.id
      """)
  List<MaintenanceRepair> findPrimaryLifecycleOwnerWithLeaseIdentity(
      @Param("rentalItemId") UUID rentalItemId,
      @Param("excludedRepairId") UUID excludedRepairId,
      Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from MaintenanceRepair value where value.id in :ids order by value.id")
  List<MaintenanceRepair> findAllByIdForUpdate(@Param("ids") java.util.Collection<UUID> ids);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select repair from MaintenanceRepair repair
       where repair.rentalItemId = :rentalItemId
       order by repair.id
      """)
  List<MaintenanceRepair> findAllByRentalItemIdForUpdate(
      @Param("rentalItemId") UUID rentalItemId);

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
           select replacement.id
             from InventoryPublicationPrestartReplacement replacement
            where replacement.predecessorRepairId = repair.id
              and replacement.phase <> 'APPLIED')
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
