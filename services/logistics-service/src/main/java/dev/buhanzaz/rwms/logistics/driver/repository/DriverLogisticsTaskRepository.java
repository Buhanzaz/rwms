package dev.buhanzaz.rwms.logistics.driver.repository;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

  @EntityGraph(attributePaths = "members")
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
  @EntityGraph(attributePaths = "members")
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

  /** Reads current and terminal projections for a bounded set of planner-created documents. */
  @EntityGraph(attributePaths = "members")
  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.sourceType = :sourceType
        and task.sourceId in :sourceIds
      order by task.sourceId, task.createdAt, task.id
      """)
  List<DriverLogisticsTask> findAllBySourceTypeAndSourceIdIn(
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

  /** Locks an exact bounded replacement membership by its stable external identities. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = "members")
  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.externalTaskId in :externalTaskIds
      order by task.externalTaskId
      """)
  List<DriverLogisticsTask> findAllForUpdateByExternalTaskIdIn(
      @Param("externalTaskIds") Collection<UUID> externalTaskIds);

  /** Locks the complete current local membership for one planner lineage. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = "members")
  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.sourcePlanId = :sourcePlanId
        and task.plannerMembershipState = dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlannerMembershipState.ACTIVE
      order by task.externalTaskId
      """)
  List<DriverLogisticsTask> findAllForUpdateBySourcePlanId(
      @Param("sourcePlanId") UUID sourcePlanId);

  @EntityGraph(attributePaths = "members")
  List<DriverLogisticsTask> findAllByWarehouseIdOrderByCreatedAtAscIdAsc(UUID warehouseId);

  @EntityGraph(attributePaths = "members")
  List<DriverLogisticsTask> findAllByWarehouseIdAndExternalTaskIdIn(
      UUID warehouseId, Collection<UUID> externalTaskIds);

  /** Source IDs whose non-cancelled capital movement already suppresses a capital-repair card. */
  @Query(
      """
      select distinct task.sourceId
      from DriverLogisticsTask task
      where task.warehouseId = :warehouseId
        and task.sourceType = dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType.CAPITAL_REPAIR
        and task.kind = dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind.CAPITAL_TO_PRODUCTION
        and task.state <> dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.CANCELLED
      """)
  List<UUID> findActiveCapitalRepairSourceIds(@Param("warehouseId") UUID warehouseId);

  /** Bounded expiry candidates; completed/finalizing work is never a cancellation candidate. */
  @Query(
      """
      select task.id from DriverLogisticsTask task
      where task.warehouseId = :warehouseId and task.scheduledDate < :today
        and task.tripExpiryRequestedAt is null
        and task.kind in (
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind.SHIPMENT,
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind.RETURN,
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind.TRANSFER)
        and task.state in (
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.REGISTERING,
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.SCHEDULED,
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.CURRENT)
      order by task.scheduledDate, task.id
      """)
  List<UUID> findOverdueTripIds(
      @Param("warehouseId") UUID warehouseId, @Param("today") LocalDate today, Pageable page);

  /** Recent terminal expiry history, strictly scoped to the authorized warehouse. */
  @EntityGraph(attributePaths = "members")
  List<DriverLogisticsTask>
      findByWarehouseIdAndStateAndTripExpiryRequestedAtIsNotNullOrderByUpdatedAtDescIdDesc(
          UUID warehouseId, DriverTaskState state, Pageable page);

  /** One bounded operational feed across the rental manager's current warehouse grants. */
  List<DriverLogisticsTask>
      findByWarehouseIdInAndStateAndTripExpiryRequestedAtIsNotNullOrderByUpdatedAtDescIdDesc(
          Collection<UUID> warehouseIds, DriverTaskState state, Pageable page);

  /**
   * Counts active date-only transport work that must conservatively reserve one driver for the
   * whole day until the planner supplies an exact service window.
   */
  long countByWarehouseIdAndScheduledDateAndKindInAndStateNotIn(
      UUID warehouseId,
      LocalDate scheduledDate,
      Collection<DriverTaskKind> kinds,
      Collection<DriverTaskState> excludedStates);

  /**
   * Counts conservative whole-day delivery reservations while excluding a rental-order shipment
   * already represented by an exact CustomerApp route slot.
   */
  @Query(
      """
      select count(task)
      from DriverLogisticsTask task
      where task.warehouseId = :warehouseId
        and task.scheduledDate = :scheduledDate
        and task.kind in (
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind.SHIPMENT,
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind.TRANSFER)
        and task.state not in (
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.COMPLETED,
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState.CANCELLED)
        and not exists (
          select document.id
          from LogisticsDocument document, CustomerDeliverySlot slot
          where task.sourceType = dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType.LOGISTICS_DOCUMENT
            and document.id = task.sourceId
            and document.rentalOrderId = slot.orderId
            and slot.state in (
              dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState.CHECKOUT_PENDING,
              dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState.CONFIRMED))
      """)
  long countWholeDayDeliveryReservations(
      @Param("warehouseId") UUID warehouseId, @Param("scheduledDate") LocalDate scheduledDate);

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
      @Param("now") OffsetDateTime now,
      Pageable page);

  /**
   * Defers an unchanged status fallback poll without advancing the business aggregate version. The
   * workflow store holds the row lock while issuing this technical scheduling update.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      """
      update DriverLogisticsTask task
      set task.nextAttemptAt = :nextAttemptAt
      where task.id = :id
        and task.version = :expectedVersion
        and task.state = :expectedState
      """)
  int deferStatusPoll(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("expectedState") DriverTaskState expectedState,
      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt);

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

  /** Returns only already-published shared base work eligible no later than the requested day. */
  @Query(
      """
      select task
      from DriverLogisticsTask task
      where task.warehouseId = :warehouseId
        and task.driverAudienceMode = :audience
        and task.kind in :kinds
        and task.state = :state
        and task.externalTaskId is not null
        and task.taskBoardEntryStatus = 'WAITING'
        and task.scheduledDate <= :availableDate
      order by task.priority desc, task.scheduledDate asc, task.createdAt asc, task.id asc
      """)
  List<DriverLogisticsTask> findPlanningBaseTaskCandidates(
      @Param("warehouseId") UUID warehouseId,
      @Param("availableDate") LocalDate availableDate,
      @Param("audience") DriverTaskAudienceMode audience,
      @Param("kinds") Collection<DriverTaskKind> kinds,
      @Param("state") DriverTaskState state,
      Pageable page);

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
