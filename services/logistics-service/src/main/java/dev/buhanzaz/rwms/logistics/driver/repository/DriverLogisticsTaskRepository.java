package dev.buhanzaz.rwms.logistics.driver.repository;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriverLogisticsTaskRepository
    extends JpaRepository<DriverLogisticsTask, UUID> {
  Optional<DriverLogisticsTask> findByCreatedBySubjectIdAndIdempotencyKey(
      UUID createdBySubjectId, UUID idempotencyKey);

  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.sourceType = :sourceType
        and task.sourceId = :sourceId
        and task.kind = :kind
        and task.state <> dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.CANCELLED
      """)
  Optional<DriverLogisticsTask> findActiveBySourceTypeAndSourceIdAndKind(
      @Param("sourceType") DriverTaskSourceType sourceType,
      @Param("sourceId") UUID sourceId,
      @Param("kind") DriverTaskKind kind);

  Optional<DriverLogisticsTask> findByExternalTaskId(UUID externalTaskId);

  Optional<DriverLogisticsTask> findFirstByRepairIdAndKindOrderByCreatedAtDescIdDesc(
      UUID repairId, DriverTaskKind kind);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.repairId = :repairId
        and task.kind = :kind
      order by task.createdAt desc, task.id desc
      """)
  List<DriverLogisticsTask> findByRepairIdAndKindForUpdate(
      @Param("repairId") UUID repairId, @Param("kind") DriverTaskKind kind);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select task from DriverLogisticsTask task where task.externalTaskId = :externalTaskId")
  Optional<DriverLogisticsTask> findForUpdateByExternalTaskId(
      @Param("externalTaskId") UUID externalTaskId);

  List<DriverLogisticsTask> findAllByWarehouseIdOrderByCreatedAtAscIdAsc(
      UUID warehouseId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from DriverLogisticsTask task where task.id = :id")
  Optional<DriverLogisticsTask> findForUpdate(@Param("id") UUID id);

  @Query(
      """
      select task.id
      from DriverLogisticsTask task
      where task.state in :states
        and (task.nextAttemptAt is null or task.nextAttemptAt <= :now)
      order by task.nextAttemptAt asc nulls first, task.id asc
      """)
  List<UUID> findDueIds(
      @Param("states") Collection<DriverTaskState> states,
      @Param("now") OffsetDateTime now);

  @Query(
      """
      select distinct task.warehouseId
      from DriverLogisticsTask task
      where task.state in :states
      order by task.warehouseId
      """)
  List<UUID> findActiveWarehouseIds(@Param("states") Collection<DriverTaskState> states);

  boolean existsByWarehouseIdAndStateIn(
      UUID warehouseId, Collection<DriverTaskState> states);

  boolean existsByWarehouseIdAndStateAndManualPromotionHoldUntilAfter(
      UUID warehouseId, DriverTaskState state, OffsetDateTime value);

  List<DriverLogisticsTask> findAllByWarehouseIdAndStateAndManualPromotionHoldUntilAfterOrderByManualPromotionHoldUntilAscIdAsc(
      UUID warehouseId, DriverTaskState state, OffsetDateTime value);

  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.warehouseId = :warehouseId
        and task.state = :state
      order by task.completedAt desc nulls last, task.id desc
      """)
  List<DriverLogisticsTask> findRecentByWarehouseAndState(
      @Param("warehouseId") UUID warehouseId,
      @Param("state") DriverTaskState state);

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
