package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerEmploymentType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Warehouse-scoped worker queries, including login uniqueness and locked credential workflows. */
public interface WorkerRepository extends JpaRepository<Worker, UUID> {
  /** Directory reads exclude archived identities; historical JPA associations remain loadable. */
  @Override
  @Query("select worker from Worker worker where worker.id = :id and worker.archived = false")
  Optional<Worker> findById(@Param("id") UUID id);

  @Query("select worker from Worker worker where worker.warehouseId = :warehouseId "
      + "and worker.archived = false order by worker.displayName")
  List<Worker> findAllByWarehouseIdOrderByDisplayNameAsc(UUID warehouseId);

  boolean existsByContractorCompanyId(UUID companyId);

  /**
   * Returns active workers holding the active primary qualification of the warehouse's active
   * logistics-driver queue. The existence subquery avoids duplicate identities if persistence
   * corruption leaves duplicate binding rows.
   */
  @Query(
      """
      select worker
      from Worker worker
      where worker.warehouseId = :warehouseId
        and worker.active = true
        and exists (
          select 1
          from WorkerClassAssignment qualification, WorkQueueClassBinding binding
          where qualification.worker = worker
            and qualification.active = true
            and qualification.workerClass.active = true
            and binding.workerClass = qualification.workerClass
            and binding.queue.warehouseId = :warehouseId
            and binding.queue.active = true
            and binding.queue.definition.active = true
            and binding.queue.definition.purpose = :purpose
            and binding.bindingOrder = 0
            and binding.participationPolicy = :participationPolicy
        )
      order by lower(worker.displayName), worker.id
      """)
  List<Worker> findActiveLogisticsDrivers(
      @Param("warehouseId") UUID warehouseId,
      @Param("purpose") QueuePurpose purpose,
      @Param("participationPolicy") ParticipationPolicy participationPolicy);

  /**
   * Returns every active staff driver qualified at their home warehouse plus active contractor
   * profiles. Operational assignment filtering is deliberately applied by the directory service.
   */
  @Query(
      """
      select worker
      from Worker worker
      where worker.active = true
        and (
          worker.employmentType = :contractorType
          or (
            worker.employmentType = :staffType
            and exists (
              select 1
              from WorkerClassAssignment qualification, WorkQueueClassBinding binding
              where qualification.worker = worker
                and qualification.active = true
                and qualification.workerClass.active = true
                and binding.workerClass = qualification.workerClass
                and binding.queue.warehouseId = worker.warehouseId
                and binding.queue.active = true
                and binding.queue.definition.active = true
                and binding.queue.definition.purpose = :purpose
                and binding.bindingOrder = 0
                and binding.participationPolicy = :participationPolicy
            )
          )
        )
      order by lower(worker.displayName), worker.id
      """)
  List<Worker> findAllActiveLogisticsDrivers(
      @Param("staffType") WorkerEmploymentType staffType,
      @Param("contractorType") WorkerEmploymentType contractorType,
      @Param("purpose") QueuePurpose purpose,
      @Param("participationPolicy") ParticipationPolicy participationPolicy);

  /** Locks one worker before validating or changing its operational assignment history. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select worker from Worker worker where worker.id = :id")
  Optional<Worker> findByIdForUpdate(@Param("id") UUID id);

  Optional<Worker> findByAppLoginIgnoreCase(String appLogin);

  List<Worker> findAllByCurrentGroupId(UUID groupId);

  boolean existsByCurrentGroupIdAndActiveTrue(UUID groupId);

  boolean existsByAppLoginIgnoreCaseAndIdNot(String appLogin, UUID id);
}
