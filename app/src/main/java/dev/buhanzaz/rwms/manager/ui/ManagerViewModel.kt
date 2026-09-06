package dev.buhanzaz.rwms.manager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.buhanzaz.rwms.manager.auth.ManagerAuthState
import dev.buhanzaz.rwms.manager.media.MediaDownloader
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeSnapshotDto
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.CabinCatalogValueDto
import dev.buhanzaz.rwms.manager.network.RentalItemCreationOptionsDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.ReworkCandidateDto
import dev.buhanzaz.rwms.manager.network.ReturnEstimateSourceDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.network.ShipmentFurnitureReadinessDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import dev.buhanzaz.rwms.manager.network.TransferFurnitureReadinessDto
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadOperation
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
data class ManagerUiState(
    val authState: ManagerAuthState = ManagerAuthState.Loading,
    val currentUser: CurrentUserDto? = null,
    val warehouses: List<WarehouseDto> = emptyList(),
    val selectedWarehouseId: String? = null,
    val warehouseSelectionLocked: Boolean = true,
    val serverReachable: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val inventorySession: InventorySessionDto? = null,
    val inventoryFindings: List<InventoryFindingDto> = emptyList(),
    val inventoryRentalItems: List<RentalItemDto> = emptyList(),
    val inventoryEditor: InventoryEditorState? = null,
    /** One-shot process-death navigation target restored with the durable inventory draft. */
    val inventoryResumeRoute: String? = null,
    val returns: List<LogisticsDocumentDto> = emptyList(),
    val selectedReturn: LogisticsDocumentDto? = null,
    val returnPhotoUris: Map<String, List<String>> = emptyMap(),
    val returnReadyMedia: Map<String, List<MediaReferenceDto>> = emptyMap(),
    val returnEquipmentConfirmed: Set<String> = emptySet(),
    val shipments: List<LogisticsDocumentDto> = emptyList(),
    val selectedShipment: LogisticsDocumentDto? = null,
    val shipmentFurnitureReadiness: ShipmentFurnitureReadinessDto? = null,
    val transfers: List<LogisticsDocumentDto> = emptyList(),
    val selectedTransfer: LogisticsDocumentDto? = null,
    val transferFurnitureReadiness: TransferFurnitureReadinessDto? = null,
    val transferEditor: TransferEditorState? = null,
    val transferArrival: TransferArrivalState? = null,
    val transferPhotoUris: List<String> = emptyList(),
    val transferReadyMedia: List<MediaReferenceDto> = emptyList(),
    val logisticsAssetLabels: Map<String, String> = emptyMap(),
    val estimates: List<EstimateDto> = emptyList(),
    val repairs: List<RepairDto> = emptyList(),
    val capitalRepairs: List<RepairDto> = emptyList(),
    /** Last server-verified aggregate ordinary repair board for the selected warehouse. */
    val repairTaskBoard: TaskBoardSnapshotDto? = null,
    val acceptanceRepairs: List<RepairDto> = emptyList(),
    val acceptanceEditor: MaintenanceAcceptanceEditorState? = null,
    val assetSearch: String = "",
    val assetSearchResults: List<RentalItemDto> = emptyList(),
    val assetSearchBusy: Boolean = false,
    val assetSearchCompletedQuery: String? = null,
    val assetSearchFailedQuery: String? = null,
    val maintenanceCatalogNodes: List<CatalogNodeDto> = emptyList(),
    val maintenanceCatalogLinks: List<CatalogLinkDto> = emptyList(),
    val maintenanceCatalogRefreshAvailable: Boolean = false,
    val maintenanceAssetLabels: Map<String, String> = emptyMap(),
    val maintenanceReturnMetadata: Map<String, MaintenanceReturnMetadata> = emptyMap(),
    val maintenanceEditor: MaintenanceEditorState? = null,
    val maintenanceFurnitureEditor: MaintenanceFurnitureEditorState? = null,
) {
    val transferArrivalLineId: String?
        get() = transferArrival?.lineId
}

/** Keeps the preflight and chosen priority tied to the exact document and line versions. */
data class TransferArrivalState(
    val documentId: String,
    val documentVersion: Long,
    val lineId: String,
    val lineVersion: Long,
    val priorityRequired: Boolean,
    val priority: Int? = null,
) {
    fun isCurrentFor(document: LogisticsDocumentDto?): Boolean =
        document?.id == documentId && document.version == documentVersion &&
            document.state in setOf("IN_TRANSIT", "ARRIVING") &&
            document.lines.any { line ->
                line.id == lineId && line.version == lineVersion && line.state == "DEPARTED"
            }
}

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
data class TransferEditorState(
    val destinationWarehouseId: String = "",
    val destinationWarehouseIds: Set<String> = emptySet(),
    val driverSnapshot: String = "",
    val scheduledDate: String = "",
    val candidates: List<RentalItemDto> = emptyList(),
    val selectedAssetIds: Set<String> = emptySet(),
    val furnitureCatalog: List<EquipmentCatalogItemDto> = emptyList(),
    val furnitureReplacements: Map<String, Map<String, Long>> = emptyMap(),
    val idempotencyKey: String = UUID.randomUUID().toString(),
)

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
enum class MaintenanceEditorMode { ESTIMATE, REPAIR }

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
data class MaintenanceLineEditorState(
    val id: String,
    val catalogNodeId: String?,
    val description: String,
    val lineType: String,
    val unit: String,
    val quantity: String,
    val unitPrice: String,
    val normativeMinutes: Int,
    val comment: String,
    val catalogSnapshot: CatalogNodeSnapshotDto? = null,
    val customRouting: RoutingSnapshotDto? = null,
    val mediaReferences: List<MediaReferenceDto> = emptyList(),
    /** App-owned originals waiting for background upload for this work line. */
    val photoUris: List<String> = emptyList(),
    val reworkDisposition: String? = null,
    val sourceRepairId: String? = null,
    val sourceLineId: String? = null,
    val lineageRootLineId: String? = null,
    val repeatSourceDescription: String? = null,
)

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
data class MaintenanceStageEditorState(
    val id: String,
    val kind: String,
    val routing: RoutingSnapshotDto,
    val includedLineIds: List<String>,
    val primaryLineId: String?,
    val groupComment: String,
    val taskDeadline: String? = null,
    val originalOrder: Int = 0,
)

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
data class MaintenanceEditorState(
    val mode: MaintenanceEditorMode,
    val entityId: String?,
    val expectedVersion: Long?,
    val readOnly: Boolean,
    val selectedAsset: RentalItemDto?,
    val dispatchDate: String,
    val sourceParty: String,
    val lines: List<MaintenanceLineEditorState>,
    val photoUris: List<String>,
    val readyMedia: List<MediaReferenceDto>,
    val readyPhotoUris: Map<String, String> = emptyMap(),
    val priority: Int,
    /** Explicit operator choice; catalog policy may independently force the same outcome. */
    val forceCapitalRepair: Boolean = false,
    /** Delivery to repair and automatic removal after completion are owned by logistics. */
    val movementToRepair: Boolean = false,
    val logisticsPlanningMode: String? = null,
    val logisticsScheduledDate: String? = null,
    val step: Int,
    val stages: List<MaintenanceStageEditorState> = emptyList(),
    val createIdempotencyKey: String = UUID.randomUUID().toString(),
    val submitIdempotencyKey: String? = null,
    val documentState: String = "DRAFT",
    val linkedRepairExpectedVersion: Long? = null,
    val amendmentIdempotencyKey: String? = null,
    val repairKind: String = "PRIMARY",
    val sourceRepairId: String? = null,
    val sourceRepairExpectedVersion: Long? = null,
    val reworkReason: String = "",
    val coverPhotoKey: String? = null,
    val reworkCandidates: List<ReworkCandidateDto> = emptyList(),
)

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
enum class MaintenanceFurnitureCompletionAction {
    KEEP_IN_CABIN,
    MOVE_TO_STOCK,
}

/**
 * A temporary composition editor for a maintenance estimate or repair.
 * The task command is logistics-owned; this state only captures its requested final total.
 */
data class MaintenanceFurnitureEditorState(
    val mode: MaintenanceEditorMode,
    val rentalItem: RentalItemDto,
    val furnitureCatalog: List<EquipmentCatalogItemDto>,
    val quantities: Map<String, String>,
    val scheduledDate: String = LocalDate.now().toString(),
)

/**
 * Holds one pending repair, its resolved inline review media and the manager's local decisions.
 * The maintenance command and its required acceptance evidence remain server-authoritative.
 */
data class MaintenanceAcceptanceEditorState(
    val repair: RepairDto,
    val asset: RentalItemDto,
    val reviewMedia: AcceptanceReviewMediaState,
    /** Local review state; the server decision remains the existing accept/rework command flow. */
    val acceptedWorkLineIds: Set<String> = emptySet(),
    val comment: String = "",
    val photoUris: List<String> = emptyList(),
    val readyMedia: List<MediaReferenceDto> = emptyList(),
    val idempotencyKey: String = UUID.randomUUID().toString(),
)


/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
data class InventoryEditorState(
    val findingId: String,
    val number: String,
    val outcome: String,
    /** True while a saved inspection is being traversed without mutable controls. */
    val readOnly: Boolean = false,
    val finding: InventoryFindingDto? = null,
    val creationOptions: RentalItemCreationOptionsDto? = null,
    val creationOrigin: String? = null,
    val rentalType: String = "",
    val dimensions: String = "",
    val finishing: String = "",
    val category: String = "",
    val characteristics: List<String> = emptyList(),
    val sanitaryToilets: Int = 0,
    val sanitarySinks: Int = 0,
    val sanitaryShowers: Int = 0,
    val linoleum: Boolean? = null,
    val comment: String = "",
    val photoUris: List<String> = emptyList(),
    val coverPhotoUri: String? = null,
    /**
     * Previously saved inventory media that was downloaded into the app cache for this editor.
     * A reference missing from this map was not necessarily removed: it can simply be unavailable
     * offline, so [finding] remains the authoritative complete source until the user removes a
     * photo that is actually visible in this editor.
     */
    val persistedPhotoMedia: Map<String, MediaReferenceDto> = emptyMap(),
    /** Previous inventory media explicitly removed by the operator. */
    val removedPersistedMediaIds: Set<String> = emptySet(),
    val uploadedPhotoMedia: Map<String, MediaReferenceDto> = emptyMap(),
    val equipmentCatalog: List<EquipmentCatalogItemDto> = emptyList(),
    val equipmentObservationRequested: Boolean? = null,
    val equipmentQuantities: Map<String, String> = emptyMap(),
    val planLines: List<MaintenanceLineEditorState> = emptyList(),
    val planStages: List<MaintenanceStageEditorState> = emptyList(),
    val planPriority: Int = DEFAULT_MAINTENANCE_PRIORITY,
    val planForceCapitalRepair: Boolean = false,
    val planMovementToRepair: Boolean = false,
    val planLogisticsPlanningMode: String? = null,
    val planLogisticsScheduledDate: String? = null,
) {
    val isCreation: Boolean
        get() = outcome == "NOT_FOUND"
    val canInspect: Boolean
        get() = outcome == "MATCHED" || outcome == "NOT_FOUND"
    val selectedRentalTypeOption: CabinCatalogValueDto?
        get() = creationOptions?.rentalTypes?.firstOrNull { it.name == rentalType }
    /**
     * The asset contract exposes type catalog values, not a second sanitary flag. The established
     * catalog type label is therefore the only existing source used by this legacy inventory
     * sub-form.
     */
    val isSanitary: Boolean
        get() = rentalType.contains("санблок", ignoreCase = true)
}

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 *
 * This Android-facing facade owns the single observable state flow and preserves the existing
 * screen API. Cohesive coordinators own individual workflows behind it.
 */
class ManagerViewModel(
    application: Application,
    private val backend: RwmsBackend,
) : AndroidViewModel(application) {
    private val preference = WarehousePreference(application)
    private val maintenanceCatalogCache = MaintenanceCatalogCache(application)
    private val managerReadCache = ManagerReadCache(application)
    private val inventoryDraftStore = InventoryDraftStore(application)
    /* Remote media is not needed for the signed-out screen. Avoid creating Retrofit merely to
     * construct a downloader during the first composition. */
    private val mediaDownloader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MediaDownloader(backend.api, application.cacheDir)
    }
    private val backgroundUploads = viewModelScope.async(Dispatchers.IO) {
        BackgroundUploadCoordinator(application).also { coordinator -> coordinator.initialize() }
    }
    private val mutableState = MutableStateFlow(ManagerUiState())
    private val mutableUploadOperations =
        MutableStateFlow<List<BackgroundUploadOperation>>(emptyList())
    val state: StateFlow<ManagerUiState> = mutableState.asStateFlow()
    val uploadOperations: StateFlow<List<BackgroundUploadOperation>> =
        mutableUploadOperations.asStateFlow()
    private val commandKeys = StableCommandKeys()
    private val commandRuntime = ManagerCommandRuntime(
        mutableState = mutableState,
        scope = viewModelScope,
        problemMessage = backend::problemMessage,
        invalidateSession = { reason -> backend.auth.invalidate(reason) },
    )

    private val mediaCoordinator = ManagerMediaCoordinator(backend, mediaDownloader)
    private val maintenanceCatalogCoordinator = ManagerMaintenanceCatalogCoordinator(
        runtime = commandRuntime,
        backend = backend,
        maintenanceCatalogCache = maintenanceCatalogCache,
    )
    private val maintenanceReadCoordinator = ManagerMaintenanceReadCoordinator(
        runtime = commandRuntime,
        backend = backend,
        backgroundUploads = backgroundUploads,
        managerReadCache = managerReadCache,
        catalogAccess = maintenanceCatalogCoordinator,
        media = mediaCoordinator,
    )
    private val maintenanceEditorCoordinator = ManagerMaintenanceEditorCoordinator(
        runtime = commandRuntime,
        backend = backend,
        commandKeys = commandKeys,
        catalogAccess = maintenanceCatalogCoordinator,
        maintenanceAssetRead = maintenanceReadCoordinator,
        maintenanceRefresh = maintenanceReadCoordinator,
        media = mediaCoordinator,
    )
    private val maintenancePersistenceCoordinator = ManagerMaintenancePersistenceCoordinator(
        runtime = commandRuntime,
        backend = backend,
        backgroundUploads = backgroundUploads,
        maintenanceRefresh = maintenanceReadCoordinator,
        catalogAccess = maintenanceCatalogCoordinator,
        editorClose = maintenanceEditorCoordinator,
    )
    private val inventoryCoordinator = ManagerInventoryCoordinator(
        runtime = commandRuntime,
        backend = backend,
        backgroundUploads = backgroundUploads,
        commandKeys = commandKeys,
        managerReadCache = managerReadCache,
        catalogAccess = maintenanceCatalogCoordinator,
        media = mediaCoordinator,
        draftStore = inventoryDraftStore,
    )
    private val shipmentCoordinator = ManagerShipmentCoordinator(
        runtime = commandRuntime,
        backend = backend,
        commandKeys = commandKeys,
    )
    private val transferCoordinator = ManagerTransferCoordinator(
        runtime = commandRuntime,
        apiProvider = { backend.api },
        resolveAssetLabels = backend::resolveLogisticsAssetLabels,
        enqueueUpload = { backgroundUploads.await().enqueue(it) },
        commandKeys = commandKeys,
    )
    private val returnCoordinator = ManagerReturnCoordinator(
        runtime = commandRuntime,
        backend = backend,
        backgroundUploads = backgroundUploads,
        commandKeys = commandKeys,
        maintenanceRefresh = maintenanceReadCoordinator,
    )
    private val workspaceCoordinator = ManagerWorkspaceCoordinator(
        runtime = commandRuntime,
        backend = backend,
        backgroundUploads = backgroundUploads,
        preference = preference,
        commandKeys = commandKeys,
        catalogWorkspace = maintenanceCatalogCoordinator,
        editorReset = maintenanceEditorCoordinator,
    )

    init {
        viewModelScope.launch {
            backgroundUploads.await().operations.collectLatest { operations ->
                mutableUploadOperations.value = operations
            }
        }
        viewModelScope.launch {
            backend.auth.state.collectLatest { authState ->
                mutableState.update { it.copy(authState = authState) }
                workspaceCoordinator.onAuthState(authState)
            }
        }
        viewModelScope.launch {
            state.map { current ->
                if (current.authState == ManagerAuthState.SignedIn) {
                    val accountId = current.currentUser?.id
                    val warehouseId = current.selectedWarehouseId
                    if (accountId != null && warehouseId != null) {
                        InventoryDraftScope(accountId, warehouseId)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }.distinctUntilChanged().collectLatest(inventoryCoordinator::activateDraftScope)
        }
    }

    fun login(username: String, password: String) =
        workspaceCoordinator.login(username, password)

    fun logout() = workspaceCoordinator.logout()

    fun dismissMessage() = workspaceCoordinator.dismissMessage()

    fun retryBackgroundUpload(operationId: String) =
        workspaceCoordinator.retryBackgroundUpload(operationId)

    fun retryBackgroundPhoto(operationId: String, photoId: String) =
        workspaceCoordinator.retryBackgroundPhoto(operationId, photoId)

    fun confirmUnaccountedFurnitureBackgroundUpload(operationId: String) =
        workspaceCoordinator.confirmUnaccountedFurnitureBackgroundUpload(operationId)

    fun cancelBackgroundUpload(operationId: String) =
        workspaceCoordinator.cancelBackgroundUpload(operationId)

    fun checkServerConnection() = workspaceCoordinator.checkServerConnection()

    fun selectWarehouse(warehouseId: String) =
        workspaceCoordinator.selectWarehouse(warehouseId)

    fun loadInventory() = inventoryCoordinator.loadInventory()

    fun resolveInventoryNumber(
        number: String,
        onInspectionChoiceRequired: (InventoryFindingDto) -> Unit,
        onReady: () -> Unit,
    ) = inventoryCoordinator.resolveInventoryNumber(
        number,
        onInspectionChoiceRequired,
        onReady,
    )

    fun prepareNewInventoryNumber(
        number: String,
        onInspectionChoiceRequired: (InventoryFindingDto) -> Unit,
        onReady: () -> Unit,
    ) = inventoryCoordinator.prepareNewInventoryNumber(
        number,
        onInspectionChoiceRequired,
        onReady,
    )

    fun openInventoryFinding(
        finding: InventoryFindingDto,
        mode: InventoryReinspectionMode,
        onReady: () -> Unit,
    ) = inventoryCoordinator.openInventoryFinding(finding, mode, onReady)

    /** Enables a retained saved inspection without changing the current navigation step. */
    fun beginInventorySupplement() = inventoryCoordinator.beginInventorySupplement()

    fun resolveInventoryConflict(
        finding: InventoryFindingDto,
        strategy: String,
        reason: String?,
        onResolved: () -> Unit,
    ) = inventoryCoordinator.resolveInventoryConflict(
        finding,
        strategy,
        reason,
        onResolved,
    )

    fun editInventory(update: (InventoryEditorState) -> InventoryEditorState) =
        inventoryCoordinator.editInventory(update)

    fun editInventoryPlan(
        update: (MaintenanceEditorState) -> MaintenanceEditorState,
    ) = inventoryCoordinator.editInventoryPlan(update)

    fun addInventoryCatalogNodes(
        nodes: List<CatalogNodeDto>,
        quantity: String,
        comment: String,
        existingWorkLineId: String? = null,
        photoUris: List<String> = emptyList(),
        mediaReferences: List<MediaReferenceDto> = emptyList(),
    ): Boolean = inventoryCoordinator.addInventoryCatalogNodes(
        nodes = nodes,
        quantity = quantity,
        comment = comment,
        existingWorkLineId = existingWorkLineId,
        photoUris = photoUris,
        mediaReferences = mediaReferences,
    )

    fun chooseInventoryOrigin(origin: String) =
        inventoryCoordinator.chooseInventoryOrigin(origin)

    fun addInventoryPhoto(uri: String) = inventoryCoordinator.addInventoryPhoto(uri)

    fun removeInventoryPhoto(uri: String) = inventoryCoordinator.removeInventoryPhoto(uri)

    fun selectInventoryCoverPhoto(uri: String) =
        inventoryCoordinator.selectInventoryCoverPhoto(uri)

    fun recordInventoryRoute(route: String) =
        inventoryCoordinator.recordInventoryRoute(route)

    fun consumeInventoryResumeRoute() =
        inventoryCoordinator.consumeInventoryResumeRoute()

    fun closeInventoryEditor() = inventoryCoordinator.closeInventoryEditor()

    fun saveInventoryInspection(onSaved: () -> Unit) =
        inventoryCoordinator.saveInventoryInspection(onSaved)

    fun loadReturns() = returnCoordinator.loadReturns()

    fun loadShipments() = shipmentCoordinator.loadShipments()

    fun openShipment(documentId: String, onReady: () -> Unit) =
        shipmentCoordinator.openShipment(documentId, onReady)

    fun closeShipment() = shipmentCoordinator.closeShipment()

    fun planShipment(driverSnapshot: String, scheduledDate: String) =
        shipmentCoordinator.planShipment(driverSnapshot, scheduledDate)

    fun createShipmentFurnitureTasks() = shipmentCoordinator.createShipmentFurnitureTasks()

    fun confirmShipment() = shipmentCoordinator.confirmShipment()

    fun cancelShipment() = shipmentCoordinator.cancelShipment()

    fun loadTransfers() = transferCoordinator.loadTransfers()

    fun startTransferEditor(onReady: () -> Unit) =
        transferCoordinator.startTransferEditor(onReady)

    fun editTransferEditor(update: (TransferEditorState) -> TransferEditorState) =
        transferCoordinator.editTransferEditor(update)

    fun toggleTransferAsset(assetId: String, selected: Boolean) =
        transferCoordinator.toggleTransferAsset(assetId, selected)

    fun updateTransferFurnitureQuantity(
        assetId: String,
        equipmentId: String,
        quantity: String,
    ) = transferCoordinator.updateTransferFurnitureQuantity(assetId, equipmentId, quantity)

    fun resetTransferFurniture(assetId: String) =
        transferCoordinator.resetTransferFurniture(assetId)

    fun closeTransferEditor() = transferCoordinator.closeTransferEditor()

    fun createTransfer(onSaved: () -> Unit) =
        transferCoordinator.createTransfer(onSaved)

    fun openTransfer(documentId: String, onReady: () -> Unit) =
        transferCoordinator.openTransfer(documentId, onReady)

    fun closeTransfer() = transferCoordinator.closeTransfer()

    fun departTransfer() = transferCoordinator.departTransfer()

    fun arriveTransfer() = transferCoordinator.arriveTransfer()

    fun departTransferLine(lineId: String) =
        transferCoordinator.departTransferLine(lineId)

    fun startTransferArrival(lineId: String, onReady: () -> Unit) =
        transferCoordinator.startTransferArrival(lineId, onReady)

    fun closeTransferArrival() = transferCoordinator.closeTransferArrival()

    fun selectTransferArrivalPriority(priority: Int) =
        transferCoordinator.selectTransferArrivalPriority(priority)

    fun addTransferPhoto(uri: String) = transferCoordinator.addTransferPhoto(uri)

    fun removeTransferPhoto(uri: String) = transferCoordinator.removeTransferPhoto(uri)

    fun arriveTransferLine(onSaved: () -> Unit) =
        transferCoordinator.arriveTransferLine(onSaved)

    fun cancelTransfer() = transferCoordinator.cancelTransfer()

    fun reconcileTransfer(reason: String) =
        transferCoordinator.reconcileTransfer(reason)

    fun openReturn(document: LogisticsDocumentDto) =
        returnCoordinator.openReturn(document)

    fun closeReturn() = returnCoordinator.closeReturn()

    fun addReturnPhoto(lineId: String, uri: String) =
        returnCoordinator.addReturnPhoto(lineId, uri)

    fun removeReturnPhoto(lineId: String, uri: String) =
        returnCoordinator.removeReturnPhoto(lineId, uri)

    fun confirmReturnEquipment(lineId: String, confirmed: Boolean) =
        returnCoordinator.confirmReturnEquipment(lineId, confirmed)

    fun acceptReturn(onSaved: () -> Unit) = returnCoordinator.acceptReturn(onSaved)

    fun startReturnEstimates(
        onSourcesReady: (List<ReturnEstimateSourceDto>) -> Unit,
        onQueued: () -> Unit,
    ) = returnCoordinator.startReturnEstimates(onSourcesReady, onQueued)

    fun loadMaintenance() = maintenanceReadCoordinator.loadMaintenance()

    fun loadRepairQueue() = maintenanceReadCoordinator.loadRepairQueue()

    fun loadAcceptance() = maintenanceReadCoordinator.loadAcceptance()

    fun openAcceptance(repairId: String) =
        maintenanceReadCoordinator.openAcceptance(repairId)

    fun closeAcceptance() = maintenanceReadCoordinator.closeAcceptance()

    fun editAcceptanceComment(value: String) =
        maintenanceReadCoordinator.editAcceptanceComment(value)

    fun acceptAcceptanceWork(workLineId: String) =
        maintenanceReadCoordinator.acceptAcceptanceWork(workLineId)

    fun addAcceptancePhoto(uri: String) = maintenanceReadCoordinator.addAcceptancePhoto(uri)

    fun removeAcceptancePhoto(uri: String) =
        maintenanceReadCoordinator.removeAcceptancePhoto(uri)

    fun acceptMaintenanceRepair(onSaved: () -> Unit) =
        maintenanceReadCoordinator.acceptMaintenanceRepair(onSaved)

    fun checkMaintenanceCatalogOnResume() =
        maintenanceCatalogCoordinator.checkMaintenanceCatalogOnResume()

    fun refreshMaintenanceCatalog() =
        maintenanceCatalogCoordinator.refreshMaintenanceCatalog()

    fun startMaintenanceEditor(
        mode: MaintenanceEditorMode,
        onReady: () -> Unit,
    ) = maintenanceEditorCoordinator.startMaintenanceEditor(mode, onReady)

    fun openEstimateEditor(id: String, onReady: () -> Unit) =
        maintenanceEditorCoordinator.openEstimateEditor(id, onReady)

    fun openRepairEditor(id: String, onReady: () -> Unit) =
        maintenanceEditorCoordinator.openRepairEditor(id, onReady)

    fun startReworkEditor(sourceRepairId: String, onReady: () -> Unit) =
        maintenanceEditorCoordinator.startReworkEditor(sourceRepairId, onReady)

    fun startReworkEditorForLine(
        sourceRepairId: String,
        sourceLineId: String,
        onReady: () -> Unit,
    ) = maintenanceEditorCoordinator.startReworkEditorForLine(
        sourceRepairId,
        sourceLineId,
        onReady,
    )

    fun closeMaintenanceEditor() = maintenanceEditorCoordinator.closeMaintenanceEditor()

    fun openMaintenanceFurniture(onReady: () -> Unit) =
        maintenanceEditorCoordinator.openMaintenanceFurniture(onReady)

    fun closeMaintenanceFurniture() =
        maintenanceEditorCoordinator.closeMaintenanceFurniture()

    fun updateMaintenanceFurnitureQuantity(equipmentId: String, quantity: String) =
        maintenanceEditorCoordinator.updateMaintenanceFurnitureQuantity(equipmentId, quantity)

    fun completeMaintenanceFurniture(
        action: MaintenanceFurnitureCompletionAction,
        onCompleted: () -> Unit,
    ) = maintenanceEditorCoordinator.completeMaintenanceFurniture(action, onCompleted)

    fun editMaintenance(update: (MaintenanceEditorState) -> MaintenanceEditorState) =
        maintenanceEditorCoordinator.editMaintenance(update)

    fun selectMaintenanceAsset(item: RentalItemDto) =
        maintenanceEditorCoordinator.selectMaintenanceAsset(item)

    fun addMaintenanceCatalogNodes(
        nodes: List<CatalogNodeDto>,
        quantity: String,
        comment: String,
        existingWorkLineId: String? = null,
        photoUris: List<String> = emptyList(),
        mediaReferences: List<MediaReferenceDto> = emptyList(),
    ): Boolean = maintenanceEditorCoordinator.addMaintenanceCatalogNodes(
        nodes = nodes,
        quantity = quantity,
        comment = comment,
        existingWorkLineId = existingWorkLineId,
        photoUris = photoUris,
        mediaReferences = mediaReferences,
    )

    fun toggleReworkCandidate(candidate: ReworkCandidateDto) =
        maintenanceEditorCoordinator.toggleReworkCandidate(candidate)

    fun addMaintenancePhoto(uri: String) =
        maintenanceEditorCoordinator.addMaintenancePhoto(uri)

    fun removeMaintenancePhoto(uri: String) =
        maintenanceEditorCoordinator.removeMaintenancePhoto(uri)

    fun selectMaintenanceCoverPhoto(photoKey: String) =
        maintenanceEditorCoordinator.selectMaintenanceCoverPhoto(photoKey)

    fun saveMaintenanceDraft(onSaved: () -> Unit) =
        maintenancePersistenceCoordinator.saveMaintenanceDraft(onSaved)

    fun submitMaintenance(onSaved: () -> Unit) =
        maintenancePersistenceCoordinator.submitMaintenance(onSaved)

    fun updateAssetSearch(value: String) =
        maintenanceEditorCoordinator.updateAssetSearch(value)

    fun searchAssets() = maintenanceEditorCoordinator.searchAssets()

    fun createEstimate(item: RentalItemDto) =
        maintenancePersistenceCoordinator.createEstimate(item)

    override fun onCleared() {
        backend.auth.close()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val backend: RwmsBackend,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ManagerViewModel(application, backend) as T
        }
    }
}
