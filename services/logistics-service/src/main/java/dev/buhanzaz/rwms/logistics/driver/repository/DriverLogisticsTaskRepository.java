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
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned driver tasks and their locked workflow
 * reads.
 */
public interface DriverLogisticsTaskRepository extends JpaRepository<DriverLogisticsTask, UUID> {
  @EntityGraph(attributePaths = "members")
  @Query("select task from DriverLogisticsTask task where task.id = :id")
  Optional<DriverLogisticsTask> findWithMembersById(@Param("id") UUID id);

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

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.sourceType = :sourceType
        and task.sourceId = :sourceId
        and task.kind = :kind
        and task.state <> dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.CANCELLED
      """)
  Optional<DriverLogisticsTask> findActiveForUpdateBySourceTypeAndSourceIdAndKind(
      @Param("sourceType") DriverTaskSourceType sourceType,
      @Param("sourceId") UUID sourceId,
      @Param("kind") DriverTaskKind kind);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.sourceType = :sourceType
        and task.sourceId in :sourceIds
      order by task.createdAt asc, task.id asc
      """)
  List<DriverLogisticsTask> findAllForUpdateBySourceTypeAndSourceIdIn(
      @Param("sourceType") DriverTaskSourceType sourceType,
      @Param("sourceIds") Collection<UUID> sourceIds);

  /** Detects legacy line-derived tasks so an existing shipment is never regrouped in place. */
  @Query(
      """
      select case when count(task) > 0 then true else false end
      from DriverLogisticsTask task
      where task.sourceType = :sourceType
        and task.sourceId in :sourceIds
      """)
  boolean existsBySourceTypeAndSourceIdIn(
      @Param("sourceType") DriverTaskSourceType sourceType,
      @Param("sourceIds") Collection<UUID> sourceIds);

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
  @Query("select task from DriverLogisticsTask task where task.externalTaskId = :externalTaskId")
  Optional<DriverLogisticsTask> findForUpdateByExternalTaskId(
      @Param("externalTaskId") UUID externalTaskId);

  @EntityGraph(attributePaths = "members")
  List<DriverLogisticsTask> findAllByWarehouseIdOrderByCreatedAtAscIdAsc(UUID warehouseId);

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
      @Param("states") Collection<DriverTaskState> states, @Param("now") OffsetDateTime now);

  @Query(
      """
      select distinct task.warehouseId
      from DriverLogisticsTask task
      where task.state in :states
      order by task.warehouseId
      """)
  List<UUID> findActiveWarehouseIds(@Param("states") Collection<DriverTaskState> states);

  boolean existsByWarehouseIdAndStateIn(UUID warehouseId, Collection<DriverTaskState> states);

  boolean existsByWarehouseIdAndStateAndManualPromotionHoldUntilAfter(
      UUID warehouseId, DriverTaskState state, OffsetDateTime value);

  List<DriverLogisticsTask>
      findAllByWarehouseIdAndStateAndManualPromotionHoldUntilAfterOrderByManualPromotionHoldUntilAscIdAsc(
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
      @Param("warehouseId") UUID warehouseId, @Param("state") DriverTaskState state);

  @EntityGraph(attributePaths = "members")
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select distinct task
      from DriverLogisticsTask task
      left join task.members member
      where task.state not in (
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.COMPLETED,
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.CANCELLED)
        and (task.cabinId in :assetIds or member.cabinId in :assetIds or task.sourceId in :documentIds)
      order by task.id
      """)
  List<DriverLogisticsTask> findInventoryCandidatesForUpdate(
      @Param("assetIds") Collection<UUID> assetIds,
      @Param("documentIds") Collection<UUID> documentIds);
}
