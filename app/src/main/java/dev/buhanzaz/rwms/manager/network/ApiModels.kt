package dev.buhanzaz.rwms.manager.network

data class CurrentUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val principalType: String,
    val globalRole: String,
    val rentalAccess: Boolean,
    val warehouseAccessAll: Boolean,
    val warehouseAccesses: List<WarehouseAccessDto> = emptyList(),
)

data class WarehouseAccessDto(
    val warehouseId: String,
    val level: String,
)

data class WarehouseDto(
    val id: String,
    val version: Long,
    val name: String,
    val city: String,
    val address: String? = null,
    val timeZone: String,
    val active: Boolean,
    val sortOrder: Int? = null,
) {
    val label: String
        get() = listOf(name, city)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
}

data class InventorySessionDto(
    val id: String,
    val sessionRevision: Long,
    val warehouseId: String,
    val warehouseVersion: Long,
    val warehouseTimeZone: String,
    val businessDate: String,
    val lifecycle: String,
    val expectedCount: Int,
    val findingCount: Long,
    val inspectedCount: Long,
    val startedAt: String,
    val terminalAt: String? = null,
    val publicationState: String,
)

data class InventoryFindingDto(
    val id: String,
    val inventoryId: String,
    val findingRevision: Long,
    val origin: String,
    val inspection: String,
    val reconciliation: String,
    val assetId: String? = null,
    val assetVersion: Long? = null,
    val displayCanonicalNumber: String,
    val identityMatchKey: String,
    val passportObservation: ObservationDto,
    val equipmentObservation: ObservationDto,
    val mutationState: String,
    val comment: String,
    val expectedSnapshot: InventorySnapshotDto? = null,
    val inspectionBaseline: InventoryCurrentSnapshotDto? = null,
    val currentSnapshot: InventoryCurrentSnapshotDto? = null,
    val conflicts: List<InventoryConflictDto> = emptyList(),
    val conflictResolution: InventoryConflictResolutionDto? = null,
    /** User-facing source assigned after an inspection was persisted. */
    val inspectionSource: String? = null,
    val frozenPlan: InventoryFrozenPlanDto? = null,
    val media: List<MediaReferenceDto> = emptyList(),
    val coverMediaId: String? = null,
)

data class InventorySnapshotDto(
    val assetId: String,
    val assetVersion: Long,
    val warehouseId: String,
    val status: String,
    val displayCanonicalNumber: String,
    val tenantSnapshot: String? = null,
    val passportSnapshot: Map<String, Any?> = emptyMap(),
    val contentsSnapshot: Any? = null,
)

data class InventoryCurrentSnapshotDto(
    val assetId: String,
    val assetVersion: Long,
    val warehouseId: String,
    val status: String,
    val displayCanonicalNumber: String,
    val tenantSnapshot: String? = null,
    val passportSnapshot: Map<String, Any?> = emptyMap(),
    val contentsSnapshot: List<Any?> = emptyList(),
    val repairsSnapshot: List<InventoryRepairRegistryFactDto> = emptyList(),
)

data class InventoryRepairRegistryFactDto(
    val repairId: String,
    val rootRepairId: String,
    val origin: String,
    val kind: String,
    val executionState: String,
    val acceptanceState: String,
    val planFingerprintSha256: String,
)

data class InventoryConflictDto(
    val code: String,
    val message: String,
    val expected: String? = null,
    val actual: String? = null,
)

data class InventoryConflictResolutionDto(
    val strategy: String,
    val reason: String? = null,
    val resolvedAt: String,
)

data class ObservationDto(
    val presence: String,
    val value: Any? = null,
)

data class InventoryFindingPageDto(
    val content: List<InventoryFindingDto>,
    val page: PageMetadataDto,
)

data class PageMetadataDto(
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class ResolveNumberRequest(
    val expectedSessionRevision: Long,
    val submittedNumber: String,
)

data class NumberResolutionDto(
    val displayCanonicalNumber: String,
    val identityMatchKey: String,
    val outcome: String,
    val finding: InventoryFindingDto? = null,
)

data class CreateFindingAssetRequest(
    val expectedSessionRevision: Long,
    val expectedFindingRevision: Long,
    val sourceRevision: Long = 1,
    val origin: String,
    val displayCanonicalNumber: String,
    val safePassport: Map<String, Any?>,
)

data class ObservationInput(
    val presence: String,
    @param:ExplicitNull val value: Any?,
)

data class SaveInspectionRequest(
    val expectedSessionRevision: Long,
    val expectedFindingRevision: Long,
    val inspection: String,
    val comment: String,
    val passportObservation: ObservationInput,
    val equipmentObservation: ObservationInput,
    val media: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
    @param:ExplicitNull val planSelection: InventoryPlanSelectionDto? = null,
)

data class InventoryPlanLineInputDto(
    val aggregationKind: String,
    @param:ExplicitNull val catalogNodeId: String?,
    @param:ExplicitNull
    @property:ExplicitNull
    val routingCatalogNodeId: String?,
    @param:ExplicitNull val description: String?,
    @param:ExplicitNull val type: String?,
    @param:ExplicitNull val unit: String?,
    val quantity: String,
    @param:ExplicitNull val unitPriceMinor: Long?,
    @param:ExplicitNull val normativeMinutes: String?,
    @param:ExplicitNull val groupComment: String?,
    val mediaReferences: List<MediaReferenceDto> = emptyList(),
)

data class InventoryPlanStageSelectionDto(
    val catalogNodeId: String,
    val kind: String,
    val order: Int,
)

data class InventoryPlanSelectionDto(
    val mode: String,
    val priority: Int = 3,
    @param:ExplicitNull val coverMediaId: String? = null,
    val movementToRepair: Boolean,
    @param:ExplicitNull val logisticsPlanningMode: String?,
    @param:ExplicitNull val logisticsScheduledDate: String? = null,
    val lines: List<InventoryPlanLineInputDto>,
    val stages: List<InventoryPlanStageSelectionDto>,
)

data class InventoryFrozenPlanLineDto(
    val id: String,
    val sourceKind: String,
    val lineType: String,
    val catalogVersionId: String? = null,
    val catalogNodeId: String? = null,
    val description: String,
    val normalizedDescription: String? = null,
    val unit: String,
    val quantity: String,
    val unitPriceMinor: Long,
    val normativeMinutes: String,
    val groupComment: String? = null,
    val mediaReferences: List<MediaReferenceDto> = emptyList(),
)

data class InventoryFrozenPlanStageDto(
    val id: String,
    val order: Int,
    val catalogNodeId: String,
    val catalogNodeName: String,
    val kind: String,
    val routingQueueId: String,
    val routingQueueName: String,
    val routingQueueType: String,
    val photoRequired: Boolean,
    val normativeDurationMinutes: Int,
)

data class InventoryFrozenPlanDto(
    val mode: String,
    val catalogVersionId: String,
    val fingerprintSha256: String,
    val priority: Int = 3,
    val coverMediaId: String? = null,
    val movementToRepair: Boolean = false,
    val logisticsPlanningMode: String? = null,
    val logisticsScheduledDate: String? = null,
    val lines: List<InventoryFrozenPlanLineDto> = emptyList(),
    val stages: List<InventoryFrozenPlanStageDto> = emptyList(),
)

data class ResolveInventoryConflictRequest(
    val expectedSessionRevision: Long,
    val expectedFindingRevision: Long,
    val strategy: String,
    @param:ExplicitNull val reason: String?,
)

data class InventoryRevisionExpectationDto(
    val findingId: String,
    val expectedFindingRevision: Long,
)

data class InventoryCompletionRiskDto(
    val findingId: String,
    val code: String,
)

data class InventoryStatisticsLineDto(
    val aggregationKind: String,
    val catalogVersionId: String? = null,
    val catalogNodeId: String? = null,
    val normalizedDescription: String? = null,
    val type: String,
    val unit: String,
    val unitPriceMinor: Long,
    val quantity: String,
    val rowTotalMinor: Long,
)

data class InventoryFrozenStatisticsDto(
    val expectedCount: Int,
    val inspectedCount: Int,
    val missingCount: Int,
    val readyCount: Int,
    val withWorkCount: Int,
    val addedCount: Int,
    val unexpectedExistingCount: Int,
    val conflictCount: Int,
    val workLineCount: Int,
    val materialLineCount: Int,
    val workTotalMinor: Long,
    val materialTotalMinor: Long,
    val grandTotalMinor: Long,
    val roundingAdjustmentMinor: Int,
    val normativeMinutes: String,
    val durationSeconds: Long,
    val aggregateLines: List<InventoryStatisticsLineDto> = emptyList(),
)

data class InventoryValidatedFindingDto(
    val findingId: String,
    val currentSnapshot: InventoryCurrentSnapshotDto? = null,
    val conflicts: List<InventoryConflictDto> = emptyList(),
)

data class InventoryCompletionPreviewDto(
    val inventoryId: String,
    val sessionRevision: Long,
    val findingRevisions: List<InventoryRevisionExpectationDto>,
    val validationSha256: String,
    val validatedAt: String,
    val acknowledgementSha256: String,
    val statistics: InventoryFrozenStatisticsDto,
    val risks: List<InventoryCompletionRiskDto>,
    val validatedFindings: List<InventoryValidatedFindingDto> = emptyList(),
)

data class RentalItemDto(
    val id: String,
    val version: Long,
    val warehouseId: String,
    val number: String,
    val status: String,
    val rentalTypeId: String? = null,
    val rentalType: String? = null,
    val dimensionId: String? = null,
    val dimensions: String? = null,
    val finishingId: String? = null,
    val finishing: String? = null,
    val category: String? = null,
    /** UUID-backed cabin characteristics from the canonical asset projection. */
    val characteristics: List<CabinCatalogValueDto> = emptyList(),
    val linoleum: Boolean? = null,
    val generalComment: String? = null,
    val passport: Map<String, Any?> = emptyMap(),
    val tags: List<String> = emptyList(),
    val contents: List<EquipmentContentDto> = emptyList(),
    val activeOrderReservation: ActiveOrderReservationDto? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** Public display value for a UUID-backed cabin passport catalog entry. */
data class CabinCatalogValueDto(
    val id: String,
    val name: String,
)

/** The allowed dimension for a rental-item type, ordered by the asset service. */
data class CabinTypeDimensionDto(
    val typeId: String,
    val dimensionId: String,
    val sortOrder: Int,
)

data class RentalItemCreationOptionsDto(
    val newCategory: String,
    val usedCategories: List<String>,
    val rentalTypes: List<CabinCatalogValueDto>,
    val dimensions: List<CabinCatalogValueDto>,
    val finishings: List<CabinCatalogValueDto>,
    val characteristics: List<CabinCatalogValueDto>,
    val typeDimensions: List<CabinTypeDimensionDto>,
)

data class EquipmentContentDto(
    val equipmentId: String,
    val equipmentName: String,
    val quantity: Long,
    val locationKind: String,
)

data class EquipmentCatalogItemDto(
    val id: String,
    val version: Long,
    val name: String,
    val category: String,
    val active: Boolean,
)

data class EquipmentItemViewDto(
    val equipment: EquipmentCatalogItemDto,
)

data class ActiveOrderReservationDto(
    val reservationId: String,
    val orderId: String,
    val clientId: String? = null,
    val tenantSnapshot: String? = null,
    val reservedAt: String,
)

data class RentalItemPageDto(
    val content: List<RentalItemDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class LogisticsDocumentDto(
    val id: String,
    val version: Long,
    val documentType: String,
    val state: String,
    val warehouseId: String,
    val destinationWarehouseId: String? = null,
    val partySnapshot: String? = null,
    val driverSnapshot: String? = null,
    val clientId: String? = null,
    val equipmentMovementTaskId: String? = null,
    val scheduledDate: String? = null,
    val scheduledAt: String? = null,
    val rentalOrderId: String? = null,
    val lines: List<LogisticsLineDto>,
    val createdAt: String,
    val updatedAt: String,
)

data class LogisticsLineDto(
    val id: String,
    val version: Long,
    val lineNumber: Int,
    val assetId: String,
    val assetVersion: Long,
    val state: String,
    val tenantSnapshot: String? = null,
    val rentalOrderId: String? = null,
)

data class AcceptReturnRequest(val lines: List<AcceptReturnLineRequest>)

data class AcceptReturnLineRequest(
    val lineId: String,
    val equipmentConfirmed: Boolean,
    val references: List<MediaReferenceDto>,
    val additionalEquipment: List<AdditionalEquipmentRequest> = emptyList(),
)

data class AdditionalEquipmentRequest(
    val equipmentId: String,
    val quantity: Long,
)

data class StartReturnEstimatesRequest(
    val lines: List<StartReturnEstimateLine>,
)

data class StartReturnEstimateLine(
    val lineId: String,
    val references: List<MediaReferenceDto>,
)

data class ReturnEstimateSourceDto(
    val returnId: String,
    val lineId: String,
    val warehouseId: String,
    val rentalItemId: String,
    val estimateId: String,
)

data class ShipmentEquipmentAllocationRequest(
    val equipmentId: String,
    val quantity: Long,
    val expectedStockVersion: Long,
)

data class ShipmentLineRequest(
    val assetId: String,
    val assetVersion: Long,
    val allocations: List<ShipmentEquipmentAllocationRequest>,
)

data class CreateShipmentRequest(
    val warehouseId: String,
    @param:ExplicitNull val clientId: String?,
    @param:ExplicitNull val rentalOrderId: String?,
    val partySnapshot: String,
    val driverSnapshot: String,
    val lines: List<ShipmentLineRequest>,
)

data class ShipmentPlanRequest(
    val driverSnapshot: String,
    val scheduledDate: String,
)

data class ShipmentFurnitureTaskDto(
    val rentalItemId: String,
    val unitNumber: String,
    val taskId: String?,
    val lineCount: Int,
)

data class ShipmentFurnitureTaskResultDto(
    val shipmentId: String,
    val shipmentVersion: Long,
    val tasks: List<ShipmentFurnitureTaskDto>,
)

data class FurnitureTaskStatusDto(
    val rentalItemId: String,
    val unitNumber: String,
    val taskId: String,
    val externalTaskId: String,
    val taskBoardTaskId: String?,
    val taskState: String,
    val lineCount: Int,
)

data class ShipmentFurnitureReadinessDto(
    val shipmentId: String,
    val shipmentVersion: Long,
    val state: String,
    val tasks: List<FurnitureTaskStatusDto>,
)

data class TransferFurnitureReadinessDto(
    val transferId: String,
    val transferVersion: Long,
    val state: String,
    val tasks: List<FurnitureTaskStatusDto>,
)

data class TransferLineRequest(
    val assetId: String,
    val assetVersion: Long,
)

data class CabinFurnitureRequirementDto(
    val equipmentId: String,
    val quantity: Long,
)

data class CreateCabinFurnitureTaskRequest(
    val warehouseId: String,
    val scheduledDate: String,
    val contents: List<CabinFurnitureRequirementDto>,
)

data class CabinFurnitureTaskResultDto(
    val rentalItemId: String,
    val unitNumber: String,
    val taskId: String?,
    val lineCount: Int,
)

data class TransferFurnitureReplacementRequest(
    val assetId: String,
    val contents: List<CabinFurnitureRequirementDto>,
)

data class CreateTransferRequest(
    val warehouseId: String,
    val destinationWarehouseId: String,
    @param:ExplicitNull val driverSnapshot: String?,
    val scheduledDate: String,
    val lines: List<TransferLineRequest>,
    val furnitureReplacements: List<TransferFurnitureReplacementRequest>,
)

data class ArriveTransferLineRequest(
    val references: List<MediaReferenceDto>,
)

data class ReconcileLogisticsRequest(
    val reason: String,
)

data class CatalogVersionDto(
    val id: String,
    val warehouseId: String,
    val version: Long,
    val lifecycle: String,
    val sourceSha256: String,
    val counts: CatalogCountsDto,
    val validation: CatalogValidationReportDto,
    val createdAt: String,
    val activatedAt: String? = null,
    val routingSync: CatalogRoutingSyncDto? = null,
)

data class CatalogCountsDto(
    val nodes: Int,
    val links: Int,
)

data class CatalogValidationReportDto(
    val valid: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val reportSha256: String,
)

data class CatalogRoutingSyncDto(
    val state: String,
    val registrationsRequired: Int,
    val registrationsConfirmed: Int,
    val cleanupRequired: Int,
    val cleanupConfirmed: Int,
    val attempts: Int,
    val updatedAt: String,
)

data class CatalogVersionPageDto(
    val items: List<CatalogVersionDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

data class RoutingSnapshotDto(
    val queueId: String,
    val queueName: String,
    val queueType: String,
)

data class FurnitureEquipmentReferenceDto(
    val equipmentId: String,
    val equipmentName: String,
)

data class CatalogNodeDto(
    val id: String,
    val catalogVersionId: String,
    val nodeType: String,
    val name: String,
    val active: Boolean,
    val parentNodeId: String? = null,
    val furnitureCategory: Boolean = false,
    val furnitureEquipment: FurnitureEquipmentReferenceDto? = null,
    val unit: String? = null,
    val unitPrice: String? = null,
    val durationMinutes: Int = 0,
    val includeInEstimate: Boolean = true,
    val commonItem: Boolean = false,
    val showInMainMenu: Boolean = false,
    val canvasX: Int? = null,
    val canvasY: Int? = null,
    val routing: RoutingSnapshotDto? = null,
    /** Optional RGB color configured by the shared maintenance-catalog settings. */
    val displayColor: String? = null,
    val comment: String? = null,
)

data class CatalogLinkDto(
    val id: String,
    val catalogVersionId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val linkType: String,
    val sourceAnchor: String? = null,
    val targetAnchor: String? = null,
    val sortOrder: Int,
)

data class CatalogNodeSnapshotDto(
    val catalogVersionId: String,
    val nodeId: String,
    val nodeType: String,
    val name: String,
    @param:ExplicitNull val unit: String?,
    @param:ExplicitNull val unitPrice: String?,
    val durationMinutes: Int,
    @param:ExplicitNull val routing: RoutingSnapshotDto?,
    @param:ExplicitNull val furnitureEquipment: FurnitureEquipmentReferenceDto?,
)

data class EstimateLineInputDto(
    val id: String,
    @param:ExplicitNull val catalogSnapshot: CatalogNodeSnapshotDto?,
    val lineType: String,
    val description: String,
    @param:ExplicitNull val unit: String?,
    val quantity: String,
    val unitPrice: String,
    val normativeMinutes: Int? = null,
    @param:ExplicitNull val comment: String?,
    val mediaReferences: List<MediaReferenceDto>,
)

data class EstimateLineDto(
    val id: String,
    val catalogSnapshot: CatalogNodeSnapshotDto? = null,
    val lineType: String = when (catalogSnapshot?.nodeType) {
        "MATERIAL", "OPTION" -> "MATERIAL"
        else -> "WORK"
    },
    val description: String,
    val unit: String? = null,
    val quantity: String,
    val unitPrice: String,
    val lineTotal: String,
    val normativeMinutes: Int = catalogSnapshot?.durationMinutes ?: 0,
    val comment: String? = null,
    val mediaReferences: List<MediaReferenceDto> = emptyList(),
    val disposition: String? = null,
    val sourceRepairId: String? = null,
    val sourceLineId: String? = null,
    val lineageRootLineId: String? = null,
)

data class PlanStageInputDto(
    val id: String,
    val kind: String,
    val order: Int,
    val routing: RoutingSnapshotDto,
    val includedLineIds: List<String>,
    @param:ExplicitNull val primaryLineId: String?,
    val groupComment: String,
    @param:ExplicitNull val taskDeadline: String?,
)

data class EstimateRevisionDto(
    val revision: Int,
    val dispatchDate: String,
    val sourceParty: String? = null,
    val lines: List<EstimateLineDto> = emptyList(),
    val plan: List<PlanStageInputDto> = emptyList(),
    val total: String,
    val reason: String? = null,
    val recordedAt: String,
    val coverMediaId: String? = null,
)

data class ActorSnapshotDto(
    val actorId: String,
    val actorType: String,
)

data class EstimateDto(
    val id: String,
    val warehouseId: String,
    val rentalItemId: String,
    val version: Long,
    val lifecycle: String,
    val currentRevision: Int,
    val revisions: List<EstimateRevisionDto> = emptyList(),
    val repairId: String? = null,
    val mediaReferences: List<MediaReferenceDto> = emptyList(),
    val coverMediaId: String? = null,
    val createdAt: String,
    val completedAt: String? = null,
    val actor: ActorSnapshotDto? = null,
)

data class EstimatePageDto(
    val items: List<EstimateDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

data class CreateEstimateRequest(
    val warehouseId: String,
    val rentalItemId: String,
    val dispatchDate: String,
    @param:ExplicitNull val sourceParty: String?,
    val lines: List<EstimateLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
)

data class ReplaceEstimateRequest(
    val expectedVersion: Long,
    val dispatchDate: String,
    @param:ExplicitNull val sourceParty: String?,
    val lines: List<EstimateLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
)

data class AmendEstimateRequest(
    val expectedVersion: Long,
    @param:ExplicitNull val expectedLinkedRepairVersion: Long?,
    val dispatchDate: String,
    val reason: String,
    @param:ExplicitNull val sourceParty: String?,
    val lines: List<EstimateLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
)

data class PriorityVersionRequest(
    val expectedVersion: Long,
    val priority: Int,
    val movementToRepair: Boolean,
    @param:ExplicitNull val logisticsPlanningMode: String?,
    @param:ExplicitNull val logisticsScheduledDate: String?,
)

data class CompleteEstimateRequest(
    val expectedVersion: Long,
    val priority: Int,
    val movementToRepair: Boolean,
    @param:ExplicitNull val logisticsPlanningMode: String?,
    @param:ExplicitNull val logisticsScheduledDate: String?,
    val allowUnaccountedFurniture: Boolean = false,
)

data class DeliverySnapshotDto(
    val state: String,
    val attempts: Int,
    val updatedAt: String,
)

data class LeaseSnapshotDto(
    val leaseId: String,
    val fencingToken: Long,
    val expiresAt: String,
    val reconciliationState: String,
)

data class TaskSyncSnapshotDto(
    val externalTaskId: String,
    val taskBoardEntryId: String? = null,
    val taskBoardRegistrationVersion: Long? = null,
    val generationState: String,
    val delivery: DeliverySnapshotDto,
)

data class TaskEvidenceDto(
    val evidenceId: String,
    val entryId: String,
    val workerId: String,
    val workerGroupId: String? = null,
    val mediaId: String,
    val mediaGeneration: Long,
    val capturedAt: String,
    val recordedAt: String,
    val state: String,
)

data class RepairStageDto(
    val id: String,
    val kind: String,
    val order: Int,
    val state: String,
    val routing: RoutingSnapshotDto,
    val workLines: List<EstimateLineDto> = emptyList(),
    val materialLines: List<EstimateLineDto> = emptyList(),
    val primaryLineId: String? = null,
    val groupComment: String = "",
    val evidence: List<TaskEvidenceDto> = emptyList(),
    val taskDeadline: String? = null,
    val taskSync: TaskSyncSnapshotDto? = null,
    val completedAt: String? = null,
)

data class RepairPlanDto(
    val repairId: String,
    val repairVersion: Long,
    val stages: List<RepairStageDto> = emptyList(),
)

data class InventorySourceReferenceDto(
    val inventoryId: String,
    val findingId: String,
    val sourceRevision: Long,
    val planFingerprint: String,
    val sourceFingerprint: String,
)

data class RepairDto(
    val id: String,
    val rootRepairId: String,
    val sourceRepairId: String? = null,
    val estimateId: String? = null,
    val warehouseId: String,
    val rentalItemId: String,
    val origin: String,
    val kind: String,
    val executionState: String,
    val acceptanceState: String,
    val version: Long,
    val dispatchDate: String,
    val priority: Int = 3,
    val sourceParty: String? = null,
    val movementToRepair: Boolean = false,
    val logisticsPlanningMode: String? = null,
    val logisticsScheduledDate: String? = null,
    val plan: RepairPlanDto,
    val inventorySource: InventorySourceReferenceDto? = null,
    val lease: LeaseSnapshotDto? = null,
    val mediaReferences: List<MediaReferenceDto> = emptyList(),
    val coverMediaId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val actor: ActorSnapshotDto? = null,
)

data class RepairPageDto(
    val items: List<RepairDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

data class AcceptanceProjectionDto(
    val repairId: String,
    val rootRepairId: String,
    val warehouseId: String,
    val rentalItemId: String,
    val executionState: String,
    val acceptanceState: String,
    val repairVersion: Long,
    val readyAt: String,
)

data class AcceptanceProjectionPageDto(
    val items: List<AcceptanceProjectionDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

data class CreateDirectRepairRequest(
    val warehouseId: String,
    val rentalItemId: String,
    val dispatchDate: String,
    @param:ExplicitNull val sourceParty: String?,
    val lines: List<EstimateLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
)

data class ReplaceRepairPlanRequest(
    val expectedVersion: Long,
    val lines: List<EstimateLineInputDto>,
    val stages: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
)

data class ReworkCandidateDto(
    val sourceRepairId: String,
    val sourceLineId: String,
    val lineageRootLineId: String,
    val line: EstimateLineDto,
)

data class ReworkCandidatesDto(
    val items: List<ReworkCandidateDto> = emptyList(),
)

data class ReworkLineInputDto(
    val id: String,
    val disposition: String,
    val sourceRepairId: String? = null,
    val sourceLineId: String? = null,
    val quantity: String? = null,
    val comment: String? = null,
    val line: EstimateLineInputDto? = null,
)

data class CreateReworkRequest(
    val expectedVersion: Long,
    val reason: String,
    val lines: List<ReworkLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
)

data class RepairDecisionRequest(
    val expectedVersion: Long,
    @param:ExplicitNull val comment: String?,
    val mediaReferences: List<MediaReferenceDto>,
)

data class EstimateCommandResultDto(
    val estimate: EstimateDto,
    val repair: RepairDto? = null,
    val delivery: DeliverySnapshotDto,
)

data class RepairCommandResultDto(
    val repair: RepairDto,
    val affectedSourceRepairs: List<RepairDto> = emptyList(),
    val delivery: DeliverySnapshotDto,
)

data class TaskBoardSourceDto(
    val type: String,
    val sourceId: String,
)

data class TaskBoardAssignmentDto(
    val id: String,
    val version: Long,
    val workerId: String? = null,
    val workerName: String? = null,
    val workerGroupId: String? = null,
    val workerGroupName: String? = null,
    val status: String,
    val assignedAt: String,
    val startedAt: String? = null,
    val pausedAt: String? = null,
    val finishedAt: String? = null,
)

data class TaskBoardEntryDto(
    val id: String,
    val version: Long,
    val taskId: String,
    val externalTaskId: String? = null,
    val source: TaskBoardSourceDto? = null,
    val taskVersion: Long,
    val title: String,
    val unitNumber: String? = null,
    val taskStatus: String,
    val scheduledDate: String,
    val priority: Int,
    val pinned: Boolean,
    val queueId: String,
    val routeIndex: Int,
    val queuePosition: Int,
    val entryType: String,
    val status: String,
    val taskText: String? = null,
    val plannedDurationMinutes: Int? = null,
    val activeStartedAt: String? = null,
    val pausedAt: String? = null,
    val activeWorkSeconds: Long,
    val assignments: List<TaskBoardAssignmentDto> = emptyList(),
)

data class TaskBoardColumnDto(
    val queueId: String,
    val queueName: String,
    val queueType: String,
    val sortOrder: Int,
    val entries: List<TaskBoardEntryDto> = emptyList(),
)

data class TaskBoardSnapshotDto(
    val warehouseId: String,
    val selectedDate: String? = null,
    val availableDates: List<String> = emptyList(),
    val columns: List<TaskBoardColumnDto> = emptyList(),
)

data class MoveTaskBoardEntryRequest(
    val expectedVersion: Long,
    val expectedTaskVersion: Long,
    val targetQueueId: String,
    val targetIndex: Int,
    val targetDate: String,
)

/** Exact optimistic-concurrency evidence for both visible date columns. */
data class TaskBoardDateEntryExpectationDto(
    val entryId: String,
    val expectedVersion: Long,
    val expectedTaskVersion: Long,
)

data class SwapTaskBoardDatesRequest(
    val firstDate: String,
    val secondDate: String,
    val entries: List<TaskBoardDateEntryExpectationDto>,
)

data class CreateUploadSessionRequest(
    val ownerType: String,
    val ownerId: String? = null,
    val documentId: String? = null,
    val lineId: String? = null,
    val warehouseId: String,
    val context: String,
    val folderId: String,
    val fileName: String,
    val contentType: String,
    val contentLength: Long,
    val checksumSha256: String,
    val sortOrder: Int,
)

data class UploadSessionDto(
    val uploadSessionId: String,
    val mediaId: String,
    val expiresAt: String,
    val contentUploadUrl: String,
)

data class UploadedObjectDto(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

data class FinalizeUploadRequest(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

data class MediaAssetDto(
    val id: String,
    val folderId: String,
    val clientReferenceId: String?,
    val fileName: String,
    val contentType: String,
    val kind: String,
    val status: String,
    val version: Long,
    val generation: Long,
    val rotationDegrees: Int,
    val sortOrder: Int,
    val sizeBytes: Long?,
    val createdAt: String,
    val variants: List<MediaVariantDto> = emptyList(),
)

data class MediaVariantDto(
    val kind: String,
    val contentType: String,
    val contentPath: String,
    val width: Int? = null,
    val height: Int? = null,
)

data class MediaPageDto(
    val items: List<MediaAssetDto>,
    val next: String? = null,
)

data class MediaReferenceDto(
    val mediaId: String,
    val generation: Long,
)

data class ProblemDetailsDto(
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val code: String? = null,
)
