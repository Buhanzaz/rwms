package dev.buhanzaz.rwms.manager.network

/**
 * Public-manager-gateway response/read payload for CurrentUserDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for WarehouseAccessDto. It is a transport boundary model, not persisted domain state.
 */
data class WarehouseAccessDto(
    val warehouseId: String,
    val level: String,
)

/**
 * Public-manager-gateway response/read payload for WarehouseDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventorySessionDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventoryFindingDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventorySnapshotDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventoryCurrentSnapshotDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventoryRepairRegistryFactDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryRepairRegistryFactDto(
    val repairId: String,
    val rootRepairId: String,
    val origin: String,
    val kind: String,
    val executionState: String,
    val acceptanceState: String,
    val planFingerprintSha256: String,
)

/**
 * Public-manager-gateway response/read payload for InventoryConflictDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryConflictDto(
    val code: String,
    val message: String,
    val expected: String? = null,
    val actual: String? = null,
)

/**
 * Public-manager-gateway response/read payload for InventoryConflictResolutionDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryConflictResolutionDto(
    val strategy: String,
    val reason: String? = null,
    val resolvedAt: String,
)

/**
 * Public-manager-gateway response/read payload for ObservationDto. It is a transport boundary model, not persisted domain state.
 */
data class ObservationDto(
    val presence: String,
    val value: Any? = null,
)

/**
 * Public-manager-gateway response/read payload for InventoryFindingPageDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryFindingPageDto(
    val content: List<InventoryFindingDto>,
    val page: PageMetadataDto,
)

/**
 * Public-manager-gateway response/read payload for PageMetadataDto. It is a transport boundary model, not persisted domain state.
 */
data class PageMetadataDto(
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/**
 * Public-manager-gateway request payload for ResolveNumberRequest. It is a transport boundary model, not persisted domain state.
 */
data class ResolveNumberRequest(
    val expectedSessionRevision: Long,
    val submittedNumber: String,
)

/**
 * Public-manager-gateway response/read payload for NumberResolutionDto. It is a transport boundary model, not persisted domain state.
 */
data class NumberResolutionDto(
    val displayCanonicalNumber: String,
    val identityMatchKey: String,
    val outcome: String,
    val finding: InventoryFindingDto? = null,
)

/**
 * Public-manager-gateway request payload for CreateFindingAssetRequest. It is a transport boundary model, not persisted domain state.
 */
data class CreateFindingAssetRequest(
    val expectedSessionRevision: Long,
    val expectedFindingRevision: Long,
    val sourceRevision: Long = 1,
    val origin: String,
    val displayCanonicalNumber: String,
    val safePassport: Map<String, Any?>,
)

/**
 * Public-manager-gateway request payload for ObservationInput. It is a transport boundary model, not persisted domain state.
 */
data class ObservationInput(
    val presence: String,
    @param:ExplicitNull val value: Any?,
)

/**
 * Public-manager-gateway request payload for SaveInspectionRequest. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventoryPlanLineInputDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventoryPlanStageSelectionDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryPlanStageSelectionDto(
    val catalogNodeId: String,
    val kind: String,
    val order: Int,
)

/**
 * Public-manager-gateway response/read payload for InventoryPlanSelectionDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryPlanSelectionDto(
    val mode: String,
    val priority: Int = 3,
    @param:ExplicitNull val coverMediaId: String? = null,
    val movementToRepair: Boolean,
    val forceCapitalRepair: Boolean = false,
    @param:ExplicitNull val logisticsPlanningMode: String?,
    @param:ExplicitNull val logisticsScheduledDate: String? = null,
    val lines: List<InventoryPlanLineInputDto>,
    val stages: List<InventoryPlanStageSelectionDto>,
)

/**
 * Public-manager-gateway response/read payload for InventoryFrozenPlanLineDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryFrozenPlanLineDto(
    val id: String,
    val sourceKind: String,
    val lineType: String,
    val catalogVersionId: String? = null,
    val catalogNodeId: String? = null,
    val routingQueueId: String? = null,
    val routingQueueName: String? = null,
    val routingQueueType: String? = null,
    val description: String,
    val normalizedDescription: String? = null,
    val unit: String,
    val quantity: String,
    val unitPriceMinor: Long,
    val normativeMinutes: String,
    val groupComment: String? = null,
    val mediaReferences: List<MediaReferenceDto> = emptyList(),
)

/**
 * Public-manager-gateway response/read payload for InventoryFrozenPlanStageDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventoryFrozenPlanDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryFrozenPlanDto(
    val mode: String,
    val catalogVersionId: String,
    val fingerprintSha256: String,
    val priority: Int = 3,
    val coverMediaId: String? = null,
    val movementToRepair: Boolean = false,
    val forceCapitalRepair: Boolean = false,
    val logisticsPlanningMode: String? = null,
    val logisticsScheduledDate: String? = null,
    val lines: List<InventoryFrozenPlanLineDto> = emptyList(),
    val stages: List<InventoryFrozenPlanStageDto> = emptyList(),
)

/**
 * Public-manager-gateway request payload for ResolveInventoryConflictRequest. It is a transport boundary model, not persisted domain state.
 */
data class ResolveInventoryConflictRequest(
    val expectedSessionRevision: Long,
    val expectedFindingRevision: Long,
    val strategy: String,
    @param:ExplicitNull val reason: String?,
)

/**
 * Public-manager-gateway response/read payload for InventoryRevisionExpectationDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryRevisionExpectationDto(
    val findingId: String,
    val expectedFindingRevision: Long,
)

/**
 * Public-manager-gateway response/read payload for InventoryCompletionRiskDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryCompletionRiskDto(
    val findingId: String,
    val code: String,
)

/**
 * Public-manager-gateway response/read payload for InventoryStatisticsLineDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventoryFrozenStatisticsDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for InventoryValidatedFindingDto. It is a transport boundary model, not persisted domain state.
 */
data class InventoryValidatedFindingDto(
    val findingId: String,
    val currentSnapshot: InventoryCurrentSnapshotDto? = null,
    val conflicts: List<InventoryConflictDto> = emptyList(),
)

/**
 * Public-manager-gateway response/read payload for InventoryCompletionPreviewDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for RentalItemDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for RentalItemCreationOptionsDto. It is a transport boundary model, not persisted domain state.
 */
data class RentalItemCreationOptionsDto(
    val newCategory: String,
    val usedCategories: List<String>,
    val rentalTypes: List<CabinCatalogValueDto>,
    val dimensions: List<CabinCatalogValueDto>,
    val finishings: List<CabinCatalogValueDto>,
    val characteristics: List<CabinCatalogValueDto>,
    val typeDimensions: List<CabinTypeDimensionDto>,
)

/**
 * Public-manager-gateway response/read payload for EquipmentContentDto. It is a transport boundary model, not persisted domain state.
 */
data class EquipmentContentDto(
    val equipmentId: String,
    val equipmentName: String,
    val quantity: Long,
    val locationKind: String,
)

/**
 * Public-manager-gateway response/read payload for EquipmentCatalogItemDto. It is a transport boundary model, not persisted domain state.
 */
data class EquipmentCatalogItemDto(
    val id: String,
    val version: Long,
    val name: String,
    val category: String,
    val active: Boolean,
)

/**
 * Public-manager-gateway response/read payload for EquipmentItemViewDto. It is a transport boundary model, not persisted domain state.
 */
data class EquipmentItemViewDto(
    val equipment: EquipmentCatalogItemDto,
)

/**
 * Public-manager-gateway response/read payload for ActiveOrderReservationDto. It is a transport boundary model, not persisted domain state.
 */
data class ActiveOrderReservationDto(
    val reservationId: String,
    val orderId: String,
    val clientId: String? = null,
    val tenantSnapshot: String? = null,
    val reservedAt: String,
)

/**
 * Public-manager-gateway response/read payload for RentalItemPageDto. It is a transport boundary model, not persisted domain state.
 */
data class RentalItemPageDto(
    val content: List<RentalItemDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/**
 * Public-manager-gateway response/read payload for LogisticsDocumentDto. It is a transport boundary model, not persisted domain state.
 */
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
    val rentalOrderId: String? = null,
    val lines: List<LogisticsLineDto>,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Public-manager-gateway response/read payload for LogisticsLineDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway request payload for AcceptReturnRequest. It is a transport boundary model, not persisted domain state.
 */
data class AcceptReturnRequest(val lines: List<AcceptReturnLineRequest>)

/**
 * Public-manager-gateway request payload for AcceptReturnLineRequest. It is a transport boundary model, not persisted domain state.
 */
data class AcceptReturnLineRequest(
    val lineId: String,
    val equipmentConfirmed: Boolean,
    val references: List<MediaReferenceDto>,
    val additionalEquipment: List<AdditionalEquipmentRequest> = emptyList(),
)

/**
 * Public-manager-gateway request payload for AdditionalEquipmentRequest. It is a transport boundary model, not persisted domain state.
 */
data class AdditionalEquipmentRequest(
    val equipmentId: String,
    val quantity: Long,
)

/**
 * Public-manager-gateway request payload for StartReturnEstimatesRequest. It is a transport boundary model, not persisted domain state.
 */
data class StartReturnEstimatesRequest(
    val lines: List<StartReturnEstimateLine>,
)

/**
 * Public-manager-gateway response/read payload for StartReturnEstimateLine. It is a transport boundary model, not persisted domain state.
 */
data class StartReturnEstimateLine(
    val lineId: String,
    val references: List<MediaReferenceDto>,
)

/**
 * Public-manager-gateway response/read payload for ReturnEstimateSourceDto. It is a transport boundary model, not persisted domain state.
 */
data class ReturnEstimateSourceDto(
    val returnId: String,
    val lineId: String,
    val warehouseId: String,
    val rentalItemId: String,
    val estimateId: String,
)

/**
 * Public-manager-gateway request payload for ShipmentEquipmentAllocationRequest. It is a transport boundary model, not persisted domain state.
 */
data class ShipmentEquipmentAllocationRequest(
    val equipmentId: String,
    val quantity: Long,
    val expectedStockVersion: Long,
)

/**
 * Public-manager-gateway request payload for ShipmentLineRequest. It is a transport boundary model, not persisted domain state.
 */
data class ShipmentLineRequest(
    val assetId: String,
    val assetVersion: Long,
    val allocations: List<ShipmentEquipmentAllocationRequest>,
)

/**
 * Public-manager-gateway request payload for CreateShipmentRequest. It is a transport boundary model, not persisted domain state.
 */
data class CreateShipmentRequest(
    val warehouseId: String,
    @param:ExplicitNull val clientId: String?,
    @param:ExplicitNull val rentalOrderId: String?,
    val partySnapshot: String,
    val driverSnapshot: String,
    val lines: List<ShipmentLineRequest>,
)

/**
 * Public-manager-gateway request payload for ShipmentPlanRequest. It is a transport boundary model, not persisted domain state.
 */
data class ShipmentPlanRequest(
    val driverSnapshot: String,
    val scheduledDate: String,
)

/**
 * Public-manager-gateway response/read payload for ShipmentFurnitureTaskDto. It is a transport boundary model, not persisted domain state.
 */
data class ShipmentFurnitureTaskDto(
    val rentalItemId: String,
    val unitNumber: String,
    val taskId: String?,
    val lineCount: Int,
)

/**
 * Public-manager-gateway response/read payload for ShipmentFurnitureTaskResultDto. It is a transport boundary model, not persisted domain state.
 */
data class ShipmentFurnitureTaskResultDto(
    val shipmentId: String,
    val shipmentVersion: Long,
    val tasks: List<ShipmentFurnitureTaskDto>,
)

/**
 * Public-manager-gateway response/read payload for FurnitureTaskStatusDto. It is a transport boundary model, not persisted domain state.
 */
data class FurnitureTaskStatusDto(
    val rentalItemId: String,
    val unitNumber: String,
    val taskId: String,
    val externalTaskId: String,
    val taskBoardTaskId: String?,
    val taskState: String,
    val lineCount: Int,
)

/**
 * Public-manager-gateway response/read payload for ShipmentFurnitureReadinessDto. It is a transport boundary model, not persisted domain state.
 */
data class ShipmentFurnitureReadinessDto(
    val shipmentId: String,
    val shipmentVersion: Long,
    val state: String,
    val tasks: List<FurnitureTaskStatusDto>,
)

/**
 * Public-manager-gateway response/read payload for TransferFurnitureReadinessDto. It is a transport boundary model, not persisted domain state.
 */
data class TransferFurnitureReadinessDto(
    val transferId: String,
    val transferVersion: Long,
    val state: String,
    val tasks: List<FurnitureTaskStatusDto>,
)

/**
 * Public-manager-gateway request payload for TransferLineRequest. It is a transport boundary model, not persisted domain state.
 */
data class TransferLineRequest(
    val assetId: String,
    val assetVersion: Long,
)

/**
 * Public-manager-gateway response/read payload for CabinFurnitureRequirementDto. It is a transport boundary model, not persisted domain state.
 */
data class CabinFurnitureRequirementDto(
    val equipmentId: String,
    val quantity: Long,
)

/**
 * Public-manager-gateway request payload for CreateCabinFurnitureTaskRequest. It is a transport boundary model, not persisted domain state.
 */
data class CreateCabinFurnitureTaskRequest(
    val warehouseId: String,
    val scheduledDate: String,
    val contents: List<CabinFurnitureRequirementDto>,
)

/**
 * Public-manager-gateway response/read payload for CabinFurnitureTaskResultDto. It is a transport boundary model, not persisted domain state.
 */
data class CabinFurnitureTaskResultDto(
    val rentalItemId: String,
    val unitNumber: String,
    val taskId: String?,
    val lineCount: Int,
)

/**
 * Public-manager-gateway request payload for TransferFurnitureReplacementRequest. It is a transport boundary model, not persisted domain state.
 */
data class TransferFurnitureReplacementRequest(
    val assetId: String,
    val contents: List<CabinFurnitureRequirementDto>,
)

/**
 * Public-manager-gateway request payload for CreateTransferRequest. It is a transport boundary model, not persisted domain state.
 */
data class CreateTransferRequest(
    val warehouseId: String,
    val destinationWarehouseId: String,
    @param:ExplicitNull val driverSnapshot: String?,
    val scheduledDate: String,
    val lines: List<TransferLineRequest>,
    val furnitureReplacements: List<TransferFurnitureReplacementRequest>,
)

/**
 * Public-manager-gateway request payload for ArriveTransferLineRequest. It is a transport boundary model, not persisted domain state.
 */
data class ArriveTransferLineRequest(
    val references: List<MediaReferenceDto>,
    @param:ExplicitNull val priority: Int?,
)

/** Server-owned repair continuation and destination queue requirements for one fenced arrival. */
data class TransferArrivalPreflightDto(
    val transferId: String,
    val lineId: String,
    val activeRepairId: String?,
    val priorityRequired: Boolean,
    val missingQueueDefinitionIds: List<String>,
)

/**
 * Public-manager-gateway request payload for ReconcileLogisticsRequest. It is a transport boundary model, not persisted domain state.
 */
data class ReconcileLogisticsRequest(
    val reason: String,
)

/**
 * Public-manager-gateway response/read payload for CatalogVersionDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for CatalogCountsDto. It is a transport boundary model, not persisted domain state.
 */
data class CatalogCountsDto(
    val nodes: Int,
    val links: Int,
)

/**
 * Public-manager-gateway response/read payload for CatalogValidationReportDto. It is a transport boundary model, not persisted domain state.
 */
data class CatalogValidationReportDto(
    val valid: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val reportSha256: String,
)

/**
 * Public-manager-gateway response/read payload for CatalogRoutingSyncDto. It is a transport boundary model, not persisted domain state.
 */
data class CatalogRoutingSyncDto(
    val state: String,
    val registrationsRequired: Int,
    val registrationsConfirmed: Int,
    val cleanupRequired: Int,
    val cleanupConfirmed: Int,
    val attempts: Int,
    val updatedAt: String,
)

/**
 * Public-manager-gateway response/read payload for CatalogVersionPageDto. It is a transport boundary model, not persisted domain state.
 */
data class CatalogVersionPageDto(
    val items: List<CatalogVersionDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

/**
 * Public-manager-gateway response/read payload for RoutingSnapshotDto. It is a transport boundary model, not persisted domain state.
 */
data class RoutingSnapshotDto(
    val queueId: String,
    val queueName: String,
    val queueType: String,
)

/**
 * Public-manager-gateway response/read payload for FurnitureEquipmentReferenceDto. It is a transport boundary model, not persisted domain state.
 */
data class FurnitureEquipmentReferenceDto(
    val equipmentId: String,
    val equipmentName: String,
)

/**
 * Public-manager-gateway response/read payload for CatalogNodeDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for CatalogLinkDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for CatalogNodeSnapshotDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for EstimateLineInputDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for EstimateLineDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for PlanStageInputDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for EstimateRevisionDto. It is a transport boundary model, not persisted domain state.
 */
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
    val forceCapitalRepair: Boolean = false,
)

/**
 * Public-manager-gateway response/read payload for ActorSnapshotDto. It is a transport boundary model, not persisted domain state.
 */
data class ActorSnapshotDto(
    val actorId: String,
    val actorType: String,
)

/**
 * Public-manager-gateway response/read payload for EstimateDto. It is a transport boundary model, not persisted domain state.
 */
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
    val forceCapitalRepair: Boolean = false,
)

/**
 * Public-manager-gateway response/read payload for EstimatePageDto. It is a transport boundary model, not persisted domain state.
 */
data class EstimatePageDto(
    val items: List<EstimateDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

/**
 * Public-manager-gateway request payload for CreateEstimateRequest. It is a transport boundary model, not persisted domain state.
 */
data class CreateEstimateRequest(
    val warehouseId: String,
    val rentalItemId: String,
    val dispatchDate: String,
    @param:ExplicitNull val sourceParty: String?,
    val lines: List<EstimateLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
    val forceCapitalRepair: Boolean = false,
)

/**
 * Public-manager-gateway request payload for ReplaceEstimateRequest. It is a transport boundary model, not persisted domain state.
 */
data class ReplaceEstimateRequest(
    val expectedVersion: Long,
    val dispatchDate: String,
    @param:ExplicitNull val sourceParty: String?,
    val lines: List<EstimateLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
    val forceCapitalRepair: Boolean = false,
)

/**
 * Public-manager-gateway request payload for AmendEstimateRequest. It is a transport boundary model, not persisted domain state.
 */
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
    val forceCapitalRepair: Boolean = false,
)

/**
 * Public-manager-gateway request payload for PriorityVersionRequest. It is a transport boundary model, not persisted domain state.
 */
data class PriorityVersionRequest(
    val expectedVersion: Long,
    val priority: Int,
    val movementToRepair: Boolean,
    @param:ExplicitNull val logisticsPlanningMode: String?,
    @param:ExplicitNull val logisticsScheduledDate: String?,
)

/**
 * Public-manager-gateway request payload for CompleteEstimateRequest. It is a transport boundary model, not persisted domain state.
 */
data class CompleteEstimateRequest(
    val expectedVersion: Long,
    val priority: Int,
    val movementToRepair: Boolean,
    @param:ExplicitNull val logisticsPlanningMode: String?,
    @param:ExplicitNull val logisticsScheduledDate: String?,
    val allowUnaccountedFurniture: Boolean = false,
)

/**
 * Public-manager-gateway response/read payload for DeliverySnapshotDto. It is a transport boundary model, not persisted domain state.
 */
data class DeliverySnapshotDto(
    val state: String,
    val attempts: Int,
    val updatedAt: String,
)

/**
 * Public-manager-gateway response/read payload for LeaseSnapshotDto. It is a transport boundary model, not persisted domain state.
 */
data class LeaseSnapshotDto(
    val leaseId: String,
    val fencingToken: Long,
    val expiresAt: String,
    val reconciliationState: String,
)

/**
 * Public-manager-gateway response/read payload for TaskSyncSnapshotDto. It is a transport boundary model, not persisted domain state.
 */
data class TaskSyncSnapshotDto(
    val externalTaskId: String,
    val taskBoardEntryId: String? = null,
    val taskBoardRegistrationVersion: Long? = null,
    val generationState: String,
    val delivery: DeliverySnapshotDto,
)

/**
 * Public-manager-gateway response/read payload for TaskEvidenceDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for RepairStageDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for RepairPlanDto. It is a transport boundary model, not persisted domain state.
 */
data class RepairPlanDto(
    val repairId: String,
    val repairVersion: Long,
    val stages: List<RepairStageDto> = emptyList(),
)

/** Server-calculated repair complexity used to keep capital work out of the ordinary list. */
data class RepairComplexityDto(
    val type: String,
    val name: String,
    val color: String,
    val plannedMinutes: String,
    val forcedCapital: Boolean,
)

/**
 * Public-manager-gateway response/read payload for InventorySourceReferenceDto. It is a transport boundary model, not persisted domain state.
 */
data class InventorySourceReferenceDto(
    val inventoryId: String,
    val findingId: String,
    val sourceRevision: Long,
    val planFingerprint: String,
    val sourceFingerprint: String,
)

/**
 * Public-manager-gateway response/read payload for RepairDto. It is a transport boundary model, not persisted domain state.
 */
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
    val forceCapitalRepair: Boolean = false,
    val logisticsPlanningMode: String? = null,
    val logisticsScheduledDate: String? = null,
    /** Nullable only while reading an app cache written before complexity became client-visible. */
    val complexity: RepairComplexityDto? = null,
    val plan: RepairPlanDto,
    val inventorySource: InventorySourceReferenceDto? = null,
    val lease: LeaseSnapshotDto? = null,
    val mediaReferences: List<MediaReferenceDto> = emptyList(),
    val coverMediaId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val actor: ActorSnapshotDto? = null,
)

/**
 * Public-manager-gateway response/read payload for RepairPageDto. It is a transport boundary model, not persisted domain state.
 */
data class RepairPageDto(
    val items: List<RepairDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

/**
 * Public-manager-gateway response/read payload for AcceptanceProjectionDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for AcceptanceProjectionPageDto. It is a transport boundary model, not persisted domain state.
 */
data class AcceptanceProjectionPageDto(
    val items: List<AcceptanceProjectionDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

/**
 * Public-manager-gateway request payload for CreateDirectRepairRequest. It is a transport boundary model, not persisted domain state.
 */
data class CreateDirectRepairRequest(
    val warehouseId: String,
    val rentalItemId: String,
    val dispatchDate: String,
    @param:ExplicitNull val sourceParty: String?,
    val lines: List<EstimateLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
    val forceCapitalRepair: Boolean = false,
)

/**
 * Public-manager-gateway request payload for ReplaceRepairPlanRequest. It is a transport boundary model, not persisted domain state.
 */
data class ReplaceRepairPlanRequest(
    val expectedVersion: Long,
    val lines: List<EstimateLineInputDto>,
    val stages: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
    val forceCapitalRepair: Boolean = false,
)

/**
 * Public-manager-gateway response/read payload for ReworkCandidateDto. It is a transport boundary model, not persisted domain state.
 */
data class ReworkCandidateDto(
    val sourceRepairId: String,
    val sourceLineId: String,
    val lineageRootLineId: String,
    val line: EstimateLineDto,
)

/**
 * Public-manager-gateway response/read payload for ReworkCandidatesDto. It is a transport boundary model, not persisted domain state.
 */
data class ReworkCandidatesDto(
    val items: List<ReworkCandidateDto> = emptyList(),
)

/**
 * Public-manager-gateway response/read payload for ReworkLineInputDto. It is a transport boundary model, not persisted domain state.
 */
data class ReworkLineInputDto(
    val id: String,
    val disposition: String,
    val sourceRepairId: String? = null,
    val sourceLineId: String? = null,
    val quantity: String? = null,
    val comment: String? = null,
    val line: EstimateLineInputDto? = null,
)

/**
 * Public-manager-gateway request payload for CreateReworkRequest. It is a transport boundary model, not persisted domain state.
 */
data class CreateReworkRequest(
    val expectedVersion: Long,
    val reason: String,
    val lines: List<ReworkLineInputDto>,
    val plan: List<PlanStageInputDto>,
    val mediaReferences: List<MediaReferenceDto>,
    @param:ExplicitNull val coverMediaId: String? = null,
)

/**
 * Public-manager-gateway request payload for RepairDecisionRequest. It is a transport boundary model, not persisted domain state.
 */
data class RepairDecisionRequest(
    val expectedVersion: Long,
    @param:ExplicitNull val comment: String?,
    val mediaReferences: List<MediaReferenceDto>,
)

/**
 * Public-manager-gateway response/read payload for EstimateCommandResultDto. It is a transport boundary model, not persisted domain state.
 */
data class EstimateCommandResultDto(
    val estimate: EstimateDto,
    val repair: RepairDto? = null,
    val delivery: DeliverySnapshotDto,
)

/**
 * Public-manager-gateway response/read payload for RepairCommandResultDto. It is a transport boundary model, not persisted domain state.
 */
data class RepairCommandResultDto(
    val repair: RepairDto,
    val affectedSourceRepairs: List<RepairDto> = emptyList(),
    val delivery: DeliverySnapshotDto,
)

/**
 * Public-manager-gateway response/read payload for TaskBoardSourceDto. It is a transport boundary model, not persisted domain state.
 */
data class TaskBoardSourceDto(
    val type: String,
    val sourceId: String,
)

/**
 * Public-manager-gateway response/read payload for TaskBoardAssignmentDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for TaskBoardEntryDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for TaskBoardColumnDto. It is a transport boundary model, not persisted domain state.
 */
data class TaskBoardColumnDto(
    val queueId: String,
    val queueName: String,
    val queueType: String,
    val sortOrder: Int,
    val entries: List<TaskBoardEntryDto> = emptyList(),
)

/** Public aggregate ordinary-board snapshot; task dates and shadow routes are not read dimensions. */
data class TaskBoardSnapshotDto(
    val warehouseId: String,
    val columns: List<TaskBoardColumnDto> = emptyList(),
)

/**
 * Starts either one compatibility source upload or one pre-encoded image bundle.
 *
 * Still images set [imageVariants] and omit the three nullable legacy source fields. Video and
 * compatibility callers set those source fields and omit [imageVariants]. The media service owns
 * validation of the mutually exclusive request shapes.
 */
data class CreateUploadSessionRequest(
    val ownerType: String,
    val ownerId: String? = null,
    val documentId: String? = null,
    val lineId: String? = null,
    val warehouseId: String,
    val context: String,
    val folderId: String,
    val fileName: String,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val checksumSha256: String? = null,
    val sortOrder: Int,
    val imageVariants: List<CreateImageVariantRequest>? = null,
)

/**
 * Declares one client-prepared WebP part before an image-bundle upload session is created.
 */
data class CreateImageVariantRequest(
    val kind: String,
    val contentLength: Long,
    val checksumSha256: String,
    val width: Int,
    val height: Int,
)

/**
 * Public media upload session. [contentUploadUrl] belongs to a compatibility source upload;
 * [variantUploadUrls] belongs to a client-prepared image bundle.
 */
data class UploadSessionDto(
    val uploadSessionId: String,
    val mediaId: String,
    val expiresAt: String,
    val contentUploadUrl: String? = null,
    val variantUploadUrls: List<ImageVariantUploadUrlDto> = emptyList(),
)

/** Same-origin content route issued for one declared image-bundle variant. */
data class ImageVariantUploadUrlDto(
    val kind: String,
    val contentUploadUrl: String,
)

/**
 * Public-manager-gateway response/read payload for UploadedObjectDto. It is a transport boundary model, not persisted domain state.
 */
data class UploadedObjectDto(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

/**
 * Completes either one compatibility source object or one three-part image bundle.
 * Nullable source fields are omitted when [variants] is present.
 */
data class FinalizeUploadRequest(
    val objectVersionId: String? = null,
    val etag: String? = null,
    val checksumSha256: String? = null,
    val variants: List<FinalizeImageVariantRequest>? = null,
)

/** Object-store receipt supplied while finalizing one client-prepared WebP variant. */
data class FinalizeImageVariantRequest(
    val kind: String,
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

/**
 * Public-manager-gateway response/read payload for MediaAssetDto. It is a transport boundary model, not persisted domain state.
 */
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

/**
 * Public-manager-gateway response/read payload for MediaVariantDto. It is a transport boundary model, not persisted domain state.
 */
data class MediaVariantDto(
    val kind: String,
    val contentType: String,
    val contentPath: String,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Public-manager-gateway response/read payload for MediaPageDto. It is a transport boundary model, not persisted domain state.
 */
data class MediaPageDto(
    val items: List<MediaAssetDto>,
    val next: String? = null,
)

/**
 * Public-manager-gateway response/read payload for MediaReferenceDto. It is a transport boundary model, not persisted domain state.
 */
data class MediaReferenceDto(
    val mediaId: String,
    val generation: Long,
)

/**
 * Public-manager-gateway response/read payload for ProblemDetailsDto. It is a transport boundary model, not persisted domain state.
 */
data class ProblemDetailsDto(
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val code: String? = null,
)
