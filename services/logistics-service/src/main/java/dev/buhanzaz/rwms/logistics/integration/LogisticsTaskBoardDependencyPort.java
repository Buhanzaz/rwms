package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyFailures.unavailable;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverBoardSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverBoardTask;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverCompletionEvidence;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverShiftPlanSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskAudience;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskPreStartCancellation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskPlannerLineage;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentMovementBoardTask;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentMovementOperation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseDriverIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseDriverQueue;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WorkerOperationalAssignment;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplacementResult;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplacementSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplanCommitResult;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplanPrepareResult;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplanPrepareSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplanReleaseResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Task-board-owned worker availability, operational assignment and driver-work port used by
 * logistics.
 */
interface LogisticsTaskBoardDependencyPort {
  /** Active task-board-qualified drivers for one warehouse, without credential or contact data. */
  default List<WarehouseDriverIdentity> listWarehouseDrivers(UUID warehouseId) {
    throw unavailable("Warehouse driver listing is not configured");
  }

  /** Qualified drivers operationally available for one warehouse and planning instant. */
  default List<WarehouseDriverIdentity> listWarehouseDrivers(
      UUID warehouseId, OffsetDateTime at, boolean includeIncoming) {
    return listWarehouseDrivers(warehouseId);
  }

  /** Creates, updates, or exactly replays one planner-owned driver workday snapshot. */
  default void registerDriverShiftPlan(
      UUID idempotencyKey, UUID sourceShiftId, DriverShiftPlanSnapshot plan) {
    throw unavailable("Driver shift plan registration is not configured");
  }

  /** Replaces one complete pre-start planner revision inside one task-board transaction. */
  default PlanningReplacementResult replacePlanningAssignments(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplacementSnapshot replacement) {
    throw unavailable("Atomic planner replacement is not configured");
  }

  /** Prepares an execution hold for one exact published source-plan withdrawal. */
  default PlanningReplanPrepareResult preparePlanningReschedule(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplanPrepareSnapshot request) {
    throw unavailable("Published planner reschedule preparation is not configured");
  }

  /** Commits the held task tombstone and complete remaining source-plan revision. */
  default PlanningReplanCommitResult commitPlanningReschedule(
      UUID holdId, UUID idempotencyKey) {
    throw unavailable("Published planner reschedule commit is not configured");
  }

  /** Releases a hold only before the logistics owner mutation has committed. */
  default PlanningReplanReleaseResult releasePlanningReschedule(
      UUID holdId, UUID idempotencyKey) {
    throw unavailable("Published planner reschedule release is not configured");
  }

  /** Creates or exactly replays one transfer-backed operational driver assignment. */
  default WorkerOperationalAssignment createWorkerOperationalAssignment(
      UUID transferId,
      UUID workerId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      String mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil) {
    throw unavailable("Operational driver assignment is not configured");
  }

  /** Applies one expected-version operational-assignment lifecycle transition. */
  default WorkerOperationalAssignment transitionWorkerOperationalAssignment(
      UUID assignmentId, long expectedVersion, String targetStatus) {
    throw unavailable("Operational driver assignment transition is not configured");
  }

  EquipmentMovementBoardTask registerEquipmentMovementTask(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperation> operations);

  EquipmentMovementBoardTask readEquipmentMovementTask(UUID externalTaskId);

  EquipmentMovementBoardTask cancelEquipmentMovementTask(
      UUID externalTaskId, long expectedTaskVersion);

  default WarehouseDriverQueue readWarehouseDriverQueue(UUID warehouseId) {
    throw unavailable("Warehouse driver queue lookup is not configured");
  }

  /**
   * Reports whether the warehouse has exactly one active, visible driver queue.
   *
   * <p>A warehouse is allowed to operate without an in-house driver queue. Callers that reconcile
   * driver work must treat that configuration as an unavailable logistics lane rather than
   * repeatedly trying to read or populate a board that cannot exist.
   */
  default boolean isWarehouseDriverQueueAvailable(UUID warehouseId) {
    return true;
  }

  default DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority) {
    return registerDriverTask(
        warehouseId,
        externalTaskId,
        sourceId,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        scheduledDate,
        priority,
        new DriverTaskAudience(DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null));
  }

  default DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority,
      DriverTaskAudience driverAudience) {
    throw unavailable("Driver task registration is not configured");
  }

  /** Registers one driver task with sanitized structured content for the existing WorkerApp feed. */
  default DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority,
      DriverTaskAudience driverAudience,
      DriverTaskWorkerContent workerContent) {
    return registerDriverTask(
        warehouseId,
        externalTaskId,
        sourceId,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        scheduledDate,
        priority,
        driverAudience,
        workerContent,
        null);
  }

  /** Registers a new driver task with optional proof of its immutable planner lineage. */
  default DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority,
      DriverTaskAudience driverAudience,
      DriverTaskWorkerContent workerContent,
      DriverTaskPlannerLineage plannerLineage) {
    if (plannerLineage == null && (workerContent == null || workerContent.isEmpty())) {
      return registerDriverTask(
          warehouseId,
          externalTaskId,
          sourceId,
          title,
          unitNumber,
          description,
          queueDefinitionId,
          scheduledDate,
          priority,
          driverAudience);
    }
    throw unavailable("Structured driver task registration is not configured");
  }

  /**
   * Replaces metadata and complete structured route content under task-board's pre-start version
   * fence.
   */
  default DriverBoardTask updateDriverTaskBeforeStart(
      UUID externalTaskId,
      long expectedTaskVersion,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      DriverTaskWorkerContent workerContent) {
    throw unavailable("Pre-start driver task update is not configured");
  }

  default DriverBoardTask readDriverTask(UUID externalTaskId) {
    throw unavailable("Driver task lookup is not configured");
  }

  /** Reads one logistics task only through an exact active contractor assignment. */
  default LogisticsDependencyGateway.ContractorTaskExecution readContractorTaskExecution(
      UUID workerId, UUID externalTaskId) {
    throw unavailable("Contractor task execution lookup is not configured");
  }

  /** Applies an idempotent expected-version START or COMPLETE to one exact contractor entry. */
  default LogisticsDependencyGateway.ContractorTaskActionResult applyContractorTaskAction(
      UUID workerId,
      UUID externalTaskId,
      UUID entryId,
      UUID idempotencyKey,
      String action,
      long expectedVersion,
      UUID evidenceId) {
    throw unavailable("Contractor task execution action is not configured");
  }

  /** Reserves one exact contractor evidence identity before any media bytes are accepted. */
  default LogisticsDependencyGateway.ContractorEvidenceReservation reserveContractorTaskEvidence(
      UUID workerId,
      UUID externalTaskId,
      UUID entryId,
      UUID evidenceId,
      OffsetDateTime capturedAt,
      String contentType,
      long sizeBytes,
      String sha256) {
    throw unavailable("Contractor task evidence reservation is not configured");
  }

  default DriverBoardTask cancelDriverTask(UUID externalTaskId, long expectedTaskVersion) {
    throw unavailable("Driver task cancellation is not configured");
  }

  /** Cancels source-owned work with an explicit bounded audit reason. */
  default DriverBoardTask cancelDriverTask(
      UUID externalTaskId, long expectedTaskVersion, String reason) {
    return cancelDriverTask(externalTaskId, expectedTaskVersion);
  }

  /**
   * Atomically cancels a source-owned driver task only if task-board still sees every route entry
   * as waiting. Unlike {@link #cancelDriverTask(UUID, long)}, this command never interrupts work
   * that has started while logistics was preparing the compensation.
   */
  default DriverTaskPreStartCancellation cancelDriverTaskIfPreStart(
      UUID externalTaskId, long expectedTaskVersion, String reason) {
    throw unavailable("Pre-start driver task cancellation is not configured");
  }

  default DriverBoardTask setDriverTaskLane(
      UUID externalTaskId, long expectedTaskVersion, String lane) {
    throw unavailable("Driver task lane transition is not configured");
  }

  default DriverBoardSnapshot readDriverBoard(UUID warehouseId) {
    throw unavailable("Driver board lookup is not configured");
  }

  default DriverBoardTask moveDriverTask(
      UUID externalTaskId,
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex) {
    return moveDriverTask(
        externalTaskId,
        expectedTaskVersion,
        expectedEntryVersion,
        targetLane,
        targetDate,
        targetIndex,
        null);
  }

  default DriverBoardTask moveDriverTask(
      UUID externalTaskId,
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex,
      DriverTaskAudience targetDriverAudience) {
    throw unavailable("Driver task movement is not configured");
  }

  default DriverCompletionEvidence readDriverCompletionEvidence(UUID externalTaskId) {
    throw unavailable("Driver completion evidence lookup is not configured");
  }
}
