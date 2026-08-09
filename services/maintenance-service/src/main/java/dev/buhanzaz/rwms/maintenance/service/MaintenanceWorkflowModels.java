package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Package-local workflow result carrier adapted by the facade's public replay marker. */
record CreateResult<T>(T response, boolean replayed) {}

/** Immutable service-local furniture quantity snapshot. */
record FurnitureQuantity(UUID equipmentId, long quantity) {}

/** Immutable service-local stored line media snapshot. */
record StoredLineMedia(UUID lineId, MediaReferenceInput reference) {}

/** Immutable service-local lease refresh snapshot. */
record LeaseRefresh(
      MaintenanceDependencyGateway.LeaseSnapshot lease, boolean ownerRenewed) {}

/** Immutable service-local catalog validation snapshot. */
record CatalogValidation(boolean dependencyAcyclic) {}

/** Immutable service-local catalog mutation target snapshot. */
record CatalogMutationTarget(CatalogVersionState state) {}

/** Immutable service-local warehouse admission preflight snapshot. */
record WarehouseAdmissionPreflight<T>(UUID warehouseId, T replay) {}

/** Immutable service-local estimate completion preflight snapshot. */
record EstimateCompletionPreflight(
      CreateResult<EstimateCommandResult> replay,
      UUID warehouseId,
      List<PlanStageInput> plan,
      UUID rentalItemId,
      List<FurnitureQuantity> furniture) {}

/** Immutable service-local queue repair command preflight snapshot. */
record QueueRepairCommandPreflight(
      CreateResult<RepairCommandResult> replay,
      UUID warehouseId,
      LeaseRefreshPlan leasePlan) {}

/** Immutable service-local acceptance command preflight snapshot. */
record AcceptanceCommandPreflight(
      CreateResult<RepairCommandResult> replay,
      UUID warehouseId,
      LeaseRefreshPlan leasePlan) {}

/** Immutable service-local lease copy signature snapshot. */
record LeaseCopySignature(
      UUID repairId,
      long repairVersion,
      UUID leaseId,
      Long leaseVersion,
      Long fencingToken,
      OffsetDateTime expiresAt,
      String reconciliationState) {}

/** Immutable service-local lease refresh plan snapshot. */
record LeaseRefreshPlan(
      UUID ownerRepairId,
      UUID rentalItemId,
      long rentalItemVersion,
      String ownerType,
      String ownerId,
      UUID leaseId,
      long leaseVersion,
      long fencingToken,
      OffsetDateTime expiresAt,
      List<LeaseCopySignature> copies) {}

/** Immutable service-local transfer departure preflight snapshot. */
record TransferDeparturePreflight(
      CreateResult<PrepareTransferRepairResponse> replay,
      TransferDeparturePlan plan) {}

/** Immutable service-local transfer lease release plan snapshot. */
record TransferLeaseReleasePlan(
      UUID ownerRepairId,
      UUID leaseId,
      long leaseVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {}

/** Immutable service-local transfer departure plan snapshot. */
record TransferDeparturePlan(
      UUID warehouseId,
      UUID activeRepairId,
      List<TransferRepairSignature> chain,
      TransferLeaseReleasePlan leaseRelease) {}

/** Immutable service-local transfer arrival completion preflight snapshot. */
record TransferArrivalCompletionPreflight(
      CreateResult<CompleteTransferRepairResponse> replay,
      TransferArrivalPlan plan) {}

/** Immutable service-local transfer lease owner plan snapshot. */
record TransferLeaseOwnerPlan(
      UUID repairId, String ownerType, String ownerId) {}

/** Immutable service-local transfer task plan snapshot. */
record TransferTaskPlan(
      UUID repairId,
      UUID externalTaskId,
      long expectedVersion,
      boolean cancelForCapital) {}

/** Immutable service-local transfer routing result snapshot. */
record TransferRoutingResult(List<UUID> missingQueueDefinitionIds) {}

/** Immutable service-local transfer arrival remote result snapshot. */
record TransferArrivalRemoteResult(
      TransferRoutingResult routing,
      Map<UUID, Long> relocatedTaskVersions,
      Set<UUID> cancelledTaskRepairIds,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      MaintenanceDependencyGateway.LeaseSnapshot lease) {}

/** Immutable service-local transfer arrival plan snapshot. */
record TransferArrivalPlan(
      UUID activeRepairId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Long expectedAssetVersion,
      RepairComplexity targetComplexity,
      List<TransferRepairSignature> chain,
      List<TransferTaskPlan> tasks,
      TransferLeaseOwnerPlan leaseOwner,
      List<MaintenanceDependencyGateway.RoutingQueueRequirement> routingRequirements) {}

/** Immutable service-local transfer repair signature snapshot. */
record TransferRepairSignature(
      UUID id,
      long version,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID rootRepairId,
      UUID sourceRepairId,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      RepairReclassificationState reclassificationState,
      String transferState,
      UUID transferDocumentId,
      UUID transferLineId,
      UUID transferTargetWarehouseId,
      UUID externalTaskId,
      Long taskBoardVersion,
      UUID leaseId,
      Long leaseVersion,
      Long fencingToken,
      OffsetDateTime leaseExpiresAt,
      String leaseReconciliationState) {}

/** Immutable service-local routing identity snapshot. */
record RoutingIdentity(UUID queueId) {}

/** Immutable service-local rework candidate key snapshot. */
record ReworkCandidateKey(UUID repairId, UUID lineId) {}

/** Immutable service-local catalog content snapshot. */
record CatalogContent(
      List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {}

/** Immutable service-local catalog fork snapshot snapshot. */
record CatalogForkSnapshot(
      UUID sourceCatalogVersionId,
      long sourceCatalogVersion,
      CatalogVersionState sourceLifecycle,
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links) {}

/** Immutable service-local locked repair chain snapshot. */
record LockedRepairChain(
      MaintenanceRepair repair,
      List<MaintenanceRepair> sources,
      Map<MaintenanceEventStore.StreamRef, Long> streamVersions) {}

/** Immutable service-local locked rework snapshot. */
record LockedRework(
      MaintenanceRepair rework,
      MaintenanceRepair root,
      MaintenanceRepair source,
      Map<MaintenanceEventStore.StreamRef, Long> streamVersions) {}

/** Immutable service-local locked task outcome snapshot. */
record LockedTaskOutcome(
      MaintenanceRepair repair,
      MaintenanceRepair source,
      Map<MaintenanceEventStore.StreamRef, Long> streamVersions) {}

/** Immutable service-local characteristic plan snapshot. */
record CharacteristicPlan(
      UUID rentalItemId,
      UUID characteristicId,
      UUID rootRepairId,
      long minimumRentalItemVersion) {}

/** Immutable service-local driver task plan snapshot. */
record DriverTaskPlan(
      UUID repairId, MaintenanceDependencyGateway.DriverTaskCommand command) {}

/** Immutable service-local furniture custody plan snapshot. */
record FurnitureCustodyPlan(
      UUID repairId,
      UUID warehouseId,
      UUID rentalItemId,
      String ownerType,
      UUID ownerId,
      FurnitureAccountingMode furnitureAccountingMode,
      Map<UUID, String> equipmentNames,
      Map<UUID, Long> equipmentQuantities) {}

/** Immutable service-local empty estimate plan snapshot. */
record EmptyEstimatePlan(
      UUID estimateId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      String ownerType,
      String ownerId) {}

/** Immutable service-local task plan snapshot. */
record TaskPlan(
      UUID repairId,
      UUID warehouseId,
      UUID rentalItemId,
      UUID externalTaskId,
      boolean update,
      long expectedTaskVersion,
      LocalDate scheduledDate,
      int priority,
      boolean resolveDeliveredLocalDate,
      int dailyCapacity,
      List<MaintenanceDependencyGateway.TaskStage> stages) {}

/** Immutable service-local queue repair plan snapshot. */
record QueueRepairPlan(
      UUID repairId,
      long repairVersion,
      boolean alreadyQueued,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      RepairOrigin origin,
      boolean capital,
      String desiredAssetStatus,
      String queueTransition,
      String ownerType,
      String ownerId,
      boolean linkedReturn,
      String projectedAssetStatus,
      long projectedAssetVersion,
      boolean existingLifecycleOwner,
      FurnitureAccountingMode furnitureAccountingMode,
      List<FurnitureQuantity> requestedFurniture) {
    static QueueRepairPlan alreadyQueued(MaintenanceRepair repair) {
      return new QueueRepairPlan(
          repair.getId(),
          repair.getVersion(),
          true,
          null,
          null,
          0,
          null,
          false,
          null,
          null,
          null,
          null,
          false,
          null,
          0,
          false,
          FurnitureAccountingMode.TRACKED_CABIN_CONTENTS,
          List.of());
    }
  }

/** Immutable service-local queue repair remote result snapshot. */
record QueueRepairRemoteResult(
      boolean adoptExistingRepair,
      MaintenanceDependencyGateway.AssetSnapshot liveAsset,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      List<MaintenanceDependencyGateway.FurniturePendingReturn> furniture) {
    static QueueRepairRemoteResult adopt(
        MaintenanceDependencyGateway.AssetSnapshot liveAsset,
        List<MaintenanceDependencyGateway.FurniturePendingReturn> furniture) {
      return new QueueRepairRemoteResult(true, liveAsset, null, null, List.copyOf(furniture));
    }

    static QueueRepairRemoteResult queued(
        MaintenanceDependencyGateway.AssetSnapshot liveAsset,
        MaintenanceDependencyGateway.LeaseSnapshot lease,
        MaintenanceDependencyGateway.AssetSnapshot asset,
        List<MaintenanceDependencyGateway.FurniturePendingReturn> furniture) {
      return new QueueRepairRemoteResult(false, liveAsset, lease, asset, List.copyOf(furniture));
    }
  }

/** Immutable service-local asset transition plan snapshot. */
record AssetTransitionPlan(
      UUID repairId,
      long repairVersion,
      List<UUID> sourceRepairIds,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      String ownerType,
      String ownerId,
      String transition,
      MaintenanceDependencyGateway.LeaseSnapshot localLease) {}

/** Immutable service-local lease renewal plan snapshot. */
record LeaseRenewalPlan(
      UUID repairId,
      long repairVersion,
      UUID rentalItemId,
      UUID leaseId,
      long leaseVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {}

/** Immutable service-local task cancellation plan snapshot. */
record TaskCancellationPlan(
      UUID repairId, UUID externalTaskId, long expectedVersion) {}

/** Immutable service-local repair sync signature snapshot. */
record RepairSyncSignature(
      UUID id,
      long version,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      RepairReclassificationState reclassificationState,
      Long taskBoardVersion,
      UUID externalTaskId,
      long rentalItemVersion,
      UUID leaseId,
      Long leaseVersion,
      Long fencingToken,
      OffsetDateTime leaseExpiresAt,
      boolean movementToRepair) {}

/** Immutable service-local complexity sync plan snapshot. */
record ComplexitySyncPlan(
      UUID repairId,
      UUID warehouseId,
      UUID rentalItemId,
      boolean capital,
      String desiredStatus,
      String transition,
      long latestProjectedVersion,
      String ownerType,
      String ownerId,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      UUID leaseOwnerRepairId,
      List<UUID> complexityRepairIds,
      List<RepairSyncSignature> signatures,
      List<TaskCancellationPlan> taskCancellations) {}
