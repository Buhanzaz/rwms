package dev.buhanzaz.rwms.manager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.JsonDataException
import dev.buhanzaz.rwms.manager.auth.ManagerAuthState
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.MediaDownloader
import dev.buhanzaz.rwms.manager.media.retryMediaReadAfterOwnerProof
import dev.buhanzaz.rwms.manager.network.AmendEstimateRequest
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeSnapshotDto
import dev.buhanzaz.rwms.manager.network.CreateCabinFurnitureTaskRequest
import dev.buhanzaz.rwms.manager.network.CreateDirectRepairRequest
import dev.buhanzaz.rwms.manager.network.CreateEstimateRequest
import dev.buhanzaz.rwms.manager.network.CreateFindingAssetRequest
import dev.buhanzaz.rwms.manager.network.CreateReworkRequest
import dev.buhanzaz.rwms.manager.network.CreateTransferRequest
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.EquipmentShortageRequest
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.EstimateCommandResultDto
import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.EstimateLineInputDto
import dev.buhanzaz.rwms.manager.network.PlanStageInputDto
import dev.buhanzaz.rwms.manager.network.PriorityVersionRequest
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanStageSelectionDto
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.MoveTaskBoardEntryRequest
import dev.buhanzaz.rwms.manager.network.ObservationInput
import dev.buhanzaz.rwms.manager.network.CabinCatalogValueDto
import dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto
import dev.buhanzaz.rwms.manager.network.RentalItemCreationOptionsDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.network.ReworkCandidateDto
import dev.buhanzaz.rwms.manager.network.ReworkLineInputDto
import dev.buhanzaz.rwms.manager.network.ReconcileLogisticsRequest
import dev.buhanzaz.rwms.manager.network.ReplaceEstimateRequest
import dev.buhanzaz.rwms.manager.network.ReplaceRepairPlanRequest
import dev.buhanzaz.rwms.manager.network.ResolveInventoryConflictRequest
import dev.buhanzaz.rwms.manager.network.ResolveNumberRequest
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.network.ShipmentFurnitureReadinessDto
import dev.buhanzaz.rwms.manager.network.ShipmentPlanRequest
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import dev.buhanzaz.rwms.manager.network.TaskBoardDateEntryExpectationDto
import dev.buhanzaz.rwms.manager.network.SwapTaskBoardDatesRequest
import dev.buhanzaz.rwms.manager.network.TransferFurnitureReadinessDto
import dev.buhanzaz.rwms.manager.network.TransferFurnitureReplacementRequest
import dev.buhanzaz.rwms.manager.network.TransferLineRequest
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import dev.buhanzaz.rwms.manager.uploads.AcceptanceUploadCommand
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraft
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadOperation
import dev.buhanzaz.rwms.manager.uploads.InventoryExistingMediaRotation
import dev.buhanzaz.rwms.manager.uploads.InventoryUploadCommand
import dev.buhanzaz.rwms.manager.uploads.MaintenanceReplaceKind
import dev.buhanzaz.rwms.manager.uploads.MaintenanceUploadCommand
import dev.buhanzaz.rwms.manager.uploads.PendingBackgroundPhoto
import dev.buhanzaz.rwms.manager.uploads.ReturnUploadAction
import dev.buhanzaz.rwms.manager.uploads.ReturnUploadCommand
import dev.buhanzaz.rwms.manager.uploads.ReturnUploadLineCommand
import dev.buhanzaz.rwms.manager.uploads.TransferArrivalUploadCommand
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

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
    val returns: List<LogisticsDocumentDto> = emptyList(),
    val selectedReturn: LogisticsDocumentDto? = null,
    val returnPhotoUris: Map<String, List<String>> = emptyMap(),
    val returnReadyMedia: Map<String, List<MediaReferenceDto>> = emptyMap(),
    val returnEquipmentConfirmed: Set<String> = emptySet(),
    val returnEquipmentCatalog: List<EquipmentCatalogItemDto> = emptyList(),
    val returnEquipmentCatalogStatus: ReturnEquipmentCatalogStatus =
        ReturnEquipmentCatalogStatus.NOT_LOADED,
    val returnShortageEquipment: Map<String, String> = emptyMap(),
    val returnShortageQuantity: Map<String, String> = emptyMap(),
    val shipments: List<LogisticsDocumentDto> = emptyList(),
    val selectedShipment: LogisticsDocumentDto? = null,
    val shipmentFurnitureReadiness: ShipmentFurnitureReadinessDto? = null,
    val transfers: List<LogisticsDocumentDto> = emptyList(),
    val selectedTransfer: LogisticsDocumentDto? = null,
    val transferFurnitureReadiness: TransferFurnitureReadinessDto? = null,
    val transferEditor: TransferEditorState? = null,
    val transferArrivalLineId: String? = null,
    val transferPhotoUris: List<String> = emptyList(),
    val transferReadyMedia: List<MediaReferenceDto> = emptyList(),
    val logisticsAssetLabels: Map<String, String> = emptyMap(),
    val estimates: List<EstimateDto> = emptyList(),
    val repairs: List<RepairDto> = emptyList(),
    val repairTaskBoards: List<TaskBoardSnapshotDto> = emptyList(),
    val acceptanceRepairs: List<RepairDto> = emptyList(),
    val acceptanceEditor: MaintenanceAcceptanceEditorState? = null,
    val acceptanceGallery: AcceptanceGalleryState? = null,
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
)

data class TransferEditorState(
    val destinationWarehouseId: String = "",
    val destinationWarehouseIds: Set<String> = emptySet(),
    val driverSnapshot: String = "",
    val scheduledDate: String = LocalDate.now().toString(),
    val candidates: List<RentalItemDto> = emptyList(),
    val selectedAssetIds: Set<String> = emptySet(),
    val furnitureCatalog: List<EquipmentCatalogItemDto> = emptyList(),
    val furnitureReplacements: Map<String, Map<String, Long>> = emptyMap(),
    val idempotencyKey: String = UUID.randomUUID().toString(),
)

enum class MaintenanceEditorMode { ESTIMATE, REPAIR }

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

data class MaintenanceAcceptanceEditorState(
    val repair: RepairDto,
    val asset: RentalItemDto,
    val cabinPhotos: AcceptanceMediaCollection,
    /** Local review state; the server decision remains the existing accept/rework command flow. */
    val acceptedWorkLineIds: Set<String> = emptySet(),
    val comment: String = "",
    val photoUris: List<String> = emptyList(),
    val readyMedia: List<MediaReferenceDto> = emptyList(),
    val idempotencyKey: String = UUID.randomUUID().toString(),
)

data class AcceptanceGalleryState(
    val title: String,
    val photoUris: List<String>,
    val emptyMessage: String,
)

private data class MaintenanceMediaScope(
    val ownerType: String,
    val ownerId: String,
    val context: String,
)

private data class ScopedMediaDownload(
    val reference: MediaReferenceDto,
    val scopes: List<MaintenanceMediaScope>,
)

data class InventoryEditorState(
    val findingId: String,
    val number: String,
    val outcome: String,
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
    /** Canonical server orientation for a cached previous photo, by local URI. */
    val persistedPhotoRotationDegrees: Map<String, Int> = emptyMap(),
    /** Previous inventory media explicitly removed by the operator. */
    val removedPersistedMediaIds: Set<String> = emptySet(),
    val uploadedPhotoMedia: Map<String, MediaReferenceDto> = emptyMap(),
    /** Absolute orientation requested for the same server-side media asset, by local URI. */
    val photoRotationDegrees: Map<String, Int> = emptyMap(),
    val equipmentCatalog: List<EquipmentCatalogItemDto> = emptyList(),
    val equipmentObservationRequested: Boolean? = null,
    val equipmentQuantities: Map<String, String> = emptyMap(),
    val planLines: List<MaintenanceLineEditorState> = emptyList(),
    val planStages: List<MaintenanceStageEditorState> = emptyList(),
    val planPriority: Int = DEFAULT_MAINTENANCE_PRIORITY,
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

class ManagerViewModel(
    application: Application,
    private val backend: RwmsBackend,
) : AndroidViewModel(application) {
    private val preference = WarehousePreference(application)
    private val maintenanceCatalogCache = MaintenanceCatalogCache(application)
    private val managerReadCache = ManagerReadCache(application)
    /* Remote media is not needed for the signed-out screen.  Avoid building Retrofit merely to
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
    private var maintenanceCatalogWarehouseId: String? = null
    private var maintenanceCatalogNodesById: Map<String, CatalogNodeDto> = emptyMap()
    private var maintenanceCatalogRevision: ActiveMaintenanceCatalogRevision? = null
    private var maintenanceCatalogActiveVersionEtag: String? = null
    private val maintenanceCatalogMutex = Mutex()
    private val maintenanceReadMutex = Mutex()
    private val repairQueueMutex = Mutex()
    private val inventoryReadMutex = Mutex()
    private var maintenanceCatalogSyncJob: Job? = null
    private var maintenanceCatalogSchedulerJob: Job? = null
    private var connectivityMonitorJob: Job? = null
    private var connectivityProbeJob: Job? = null
    private var backgroundUploadsLifecycleJob: Job? = null
    private var assetSearchGeneration = 0L
    private val commandKeys = StableCommandKeys()

    init {
        viewModelScope.launch {
            backgroundUploads.await().operations.collectLatest { operations ->
                mutableUploadOperations.value = operations
            }
        }
        viewModelScope.launch {
            backend.auth.state.collectLatest { authState ->
                mutableState.update { it.copy(authState = authState) }
                if (authState == ManagerAuthState.SignedIn) {
                    resumePendingBackgroundUploads()
                    loadWorkspace()
                    startConnectivityMonitor()
                } else if (authState == ManagerAuthState.SignedOut) {
                    pauseBackgroundUploads()
                    stopConnectivityMonitor()
                    clearMaintenanceCatalogMemory()
                    mutableState.value = ManagerUiState(authState = authState)
                }
            }
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch { backend.auth.login(username, password) }
    }

    fun logout() {
        viewModelScope.launch {
            pauseBackgroundUploads()
            stopConnectivityMonitor()
            backend.auth.logout()
            clearMaintenanceCatalogMemory()
            commandKeys.clear()
            mutableState.value = ManagerUiState(authState = ManagerAuthState.SignedOut)
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun retryBackgroundUpload(operationId: String) {
        viewModelScope.launch {
            backgroundUploads.await().retry(operationId)
        }
    }

    fun retryBackgroundPhoto(operationId: String, photoId: String) {
        viewModelScope.launch {
            backgroundUploads.await().retryPhoto(operationId, photoId)
        }
    }

    fun cancelBackgroundUpload(operationId: String) {
        viewModelScope.launch {
            try {
                backgroundUploads.await().cancel(operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                handleFailure(failure)
            }
        }
    }

    private fun resumePendingBackgroundUploads() {
        backgroundUploadsLifecycleJob?.cancel()
        backgroundUploadsLifecycleJob = viewModelScope.launch {
            try {
                backgroundUploads.await().resumePending()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                handleFailure(failure)
            }
        }
    }

    private fun pauseBackgroundUploads() {
        backgroundUploadsLifecycleJob?.cancel()
        backgroundUploadsLifecycleJob = viewModelScope.launch {
            backgroundUploads.await().pause()
        }
    }

    fun checkServerConnection() {
        if (mutableState.value.authState != ManagerAuthState.SignedIn ||
            connectivityProbeJob?.isActive == true
        ) {
            return
        }
        connectivityProbeJob = viewModelScope.launch { probeServerConnection() }
    }

    fun selectWarehouse(warehouseId: String) {
        val current = mutableState.value
        if (current.warehouseSelectionLocked ||
            current.warehouses.none { it.id == warehouseId }
        ) {
            return
        }
        preference.save(warehouseId)
        commandKeys.clear()
        clearMaintenanceCatalogMemory()
        mutableState.update {
            it.copy(
                selectedWarehouseId = warehouseId,
                inventorySession = null,
                inventoryFindings = emptyList(),
                inventoryRentalItems = emptyList(),
                returns = emptyList(),
                shipments = emptyList(),
                selectedShipment = null,
                shipmentFurnitureReadiness = null,
                transfers = emptyList(),
                selectedTransfer = null,
                transferFurnitureReadiness = null,
                transferEditor = null,
                transferArrivalLineId = null,
                transferPhotoUris = emptyList(),
                transferReadyMedia = emptyList(),
                logisticsAssetLabels = emptyMap(),
                estimates = emptyList(),
                repairs = emptyList(),
                repairTaskBoards = emptyList(),
                acceptanceRepairs = emptyList(),
                acceptanceEditor = null,
                acceptanceGallery = null,
                maintenanceCatalogNodes = emptyList(),
                maintenanceCatalogLinks = emptyList(),
                maintenanceCatalogRefreshAvailable = false,
                maintenanceAssetLabels = emptyMap(),
                maintenanceReturnMetadata = emptyMap(),
                maintenanceEditor = null,
                maintenanceFurnitureEditor = null,
                assetSearch = "",
                assetSearchResults = emptyList(),
                assetSearchBusy = false,
                assetSearchCompletedQuery = null,
                assetSearchFailedQuery = null,
            )
        }
        viewModelScope.launch {
            restoreMaintenanceCatalogFromDisk(warehouseId)
            startMaintenanceCatalogScheduler()
            requestMaintenanceCatalogSyncIfDue()
        }
    }

    fun loadInventory() = command {
        refreshInventory()
    }

    fun resolveInventoryNumber(
        number: String,
        onInspectionChoiceRequired: (InventoryFindingDto) -> Unit,
        onReady: () -> Unit,
    ) = command {
        val session = mutableState.value.inventorySession
            ?: throw IllegalStateException("Сначала начните инвентаризацию")
        val normalizedNumber = number.trim()
        if (normalizedNumber.isBlank()) throw IllegalArgumentException("Введите номер бытовки")
        ensureMaintenanceCatalog(session.warehouseId)
        val signature =
            "inventory-resolve:${session.id}:${session.sessionRevision}:$normalizedNumber"
        val resolution = backend.api.resolveInventoryNumber(
            inventoryId = session.id,
            idempotencyKey = logisticsCommandKey(signature),
            request = ResolveNumberRequest(
                expectedSessionRevision = session.sessionRevision,
                submittedNumber = normalizedNumber,
            ),
        )
        val finding = resolution.finding
        if (finding?.requiresInventoryReinspectionChoice() == true) {
            // Never seed an editor with an implicit mode.  The operator must explicitly choose
            // whether the prior review is kept as a draft or fully replaced.
            mutableState.update { current -> current.copy(inventoryEditor = null) }
            commandKeys.complete(signature)
            onInspectionChoiceRequired(finding)
            return@command
        }
        val passport = finding?.inspectionPassport().orEmpty()
        val creationOptions = if (
            resolution.outcome == "MATCHED" || resolution.outcome == "NOT_FOUND"
        ) {
            backend.api.rentalItemCreationOptions(requireWarehouseId())
        } else {
            null
        }
        val equipmentCatalog = backend.api.equipment(session.warehouseId)
            .map { it.equipment }
            .inventoryFurnitureCatalog()
        val parsedCharacteristics = parseInventoryCharacteristics(
            passport["characteristics"],
        )
        val planContent = finding.inventoryPlanEditorContent()
        mutableState.update {
            it.copy(
                inventoryEditor = InventoryEditorState(
                    findingId = finding?.id ?: UUID.randomUUID().toString(),
                    number = resolution.displayCanonicalNumber,
                    outcome = resolution.outcome,
                    finding = finding,
                    creationOptions = creationOptions,
                    rentalType = passport.text("rentalType"),
                    dimensions = passport.text("dimensions"),
                    finishing = passport.text("finishing"),
                    category = passport.text("category"),
                    characteristics = if (resolution.outcome == "NOT_FOUND") {
                        // The canonical asset contract deliberately has no browser/app default
                        // characteristics. A new passport starts empty until the operator picks
                        // values from the service-provided catalog.
                        emptyList<String>()
                    } else {
                        parsedCharacteristics.selected
                    },
                    sanitaryToilets = parsedCharacteristics.toilets,
                    sanitarySinks = parsedCharacteristics.sinks,
                    sanitaryShowers = parsedCharacteristics.showers,
                    linoleum = passport["linoleum"] as? Boolean,
                    comment = finding?.comment.orEmpty(),
                    equipmentCatalog = equipmentCatalog,
                    equipmentQuantities = finding
                        ?.inventoryFurnitureInitialQuantities(equipmentCatalog)
                        .orEmpty(),
                    planLines = planContent.lines,
                    planStages = planContent.stages,
                    planPriority = finding?.frozenPlan?.priority
                        ?: DEFAULT_MAINTENANCE_PRIORITY,
                    planMovementToRepair = finding?.frozenPlan?.movementToRepair ?: false,
                    planLogisticsPlanningMode = if (
                        finding?.frozenPlan?.movementToRepair == true
                    ) {
                        LOGISTICS_PLANNING_MODE_AUTO
                    } else {
                        null
                    },
                    planLogisticsScheduledDate = null,
                ),
            )
        }
        commandKeys.complete(signature)
        onReady()
    }

    /** Opens the inspection flow for either a registered or a new rental-item number. */
    fun prepareNewInventoryNumber(
        number: String,
        onInspectionChoiceRequired: (InventoryFindingDto) -> Unit,
        onReady: () -> Unit,
    ) = resolveInventoryNumber(number, onInspectionChoiceRequired, onReady)

    fun openInventoryFinding(
        finding: InventoryFindingDto,
        mode: InventoryReinspectionMode,
        onReady: () -> Unit,
    ) = command {
        val warehouseId = requireWarehouseId()
        val latest = if (finding.requiresInventoryReinspectionChoice()) {
            // A stale revision is not a safe starting point for a repeat inspection. In
            // particular, an old background upload must never overwrite a newer inspection.
            // When offline, force=true deliberately fails instead of opening cached data as an
            // editable reinspection. A first, not-yet-saved inspection keeps its existing
            // offline-capable flow.
            refreshInventory(force = true)
            mutableState.value.inventoryFindings
                .firstOrNull { it.id == finding.id }
                ?: throw IllegalStateException(
                    "Проверка больше недоступна. Обновите инвентаризацию и выберите бытовку снова",
                )
        } else {
            mutableState.value.inventoryFindings
                .firstOrNull { it.id == finding.id }
                ?: finding
        }
        if (mode == InventoryReinspectionMode.REPLACE &&
            !latest.requiresInventoryReinspectionChoice()
        ) {
            throw IllegalStateException("Бытовка ещё не проверена: перезаписывать нечего")
        }
        ensureMaintenanceCatalog(warehouseId)
        val seed = latest.inventoryReinspectionSeed(mode)
        val passport = seed.passport
        val creationOptions = backend.api.rentalItemCreationOptions(warehouseId)
        val equipmentCatalog = backend.api.equipment(warehouseId)
            .map { it.equipment }
            .inventoryFurnitureCatalog()
        val furnitureSeed = latest.inventoryFurnitureReinspectionSeed(equipmentCatalog, mode)
        val persistedPhotos = if (seed.retainPreviousInspection) {
            loadInventoryPhotoUris(latest, warehouseId)
        } else {
            emptyList()
        }
        val persistedPhotoMedia = persistedPhotos.associate { photo ->
            photo.uri to photo.reference
        }
        val persistedPhotoRotationDegrees = persistedPhotos.associate { photo ->
            photo.uri to photo.rotationDegrees
        }
        val parsedCharacteristics = parseInventoryCharacteristics(
            passport["characteristics"],
        )
        val planContent = if (seed.retainPreviousInspection) {
            latest.inventoryPlanEditorContent()
        } else {
            RepairEditorContent(emptyList(), emptyList())
        }
        val previousPlan = latest.frozenPlan.takeIf { seed.retainPreviousInspection }
        mutableState.update {
            it.copy(
                inventoryEditor = InventoryEditorState(
                    findingId = latest.id,
                    number = latest.displayCanonicalNumber,
                    outcome = "MATCHED",
                    finding = latest,
                    creationOptions = creationOptions,
                    rentalType = passport.text("rentalType"),
                    dimensions = passport.text("dimensions"),
                    finishing = passport.text("finishing"),
                    category = passport.text("category"),
                    characteristics = parsedCharacteristics.selected,
                    sanitaryToilets = parsedCharacteristics.toilets,
                    sanitarySinks = parsedCharacteristics.sinks,
                    sanitaryShowers = parsedCharacteristics.showers,
                    linoleum = passport["linoleum"] as? Boolean,
                    comment = seed.comment,
                    photoUris = persistedPhotos.map(ScopedMediaResult::uri),
                    coverPhotoUri = persistedPhotos
                        .firstOrNull { photo -> photo.reference.mediaId == latest.coverMediaId }
                        ?.uri,
                    persistedPhotoMedia = persistedPhotoMedia,
                    persistedPhotoRotationDegrees = persistedPhotoRotationDegrees,
                    removedPersistedMediaIds = latest.inventoryReinspectionRemovedMediaIds(mode),
                    equipmentCatalog = equipmentCatalog,
                    equipmentObservationRequested = furnitureSeed.observationRequested,
                    equipmentQuantities = furnitureSeed.quantities,
                    planLines = planContent.lines,
                    planStages = planContent.stages,
                    planPriority = previousPlan?.priority ?: DEFAULT_MAINTENANCE_PRIORITY,
                    planMovementToRepair = previousPlan?.movementToRepair ?: false,
                    planLogisticsPlanningMode = if (previousPlan?.movementToRepair == true) {
                        LOGISTICS_PLANNING_MODE_AUTO
                    } else {
                        null
                    },
                    planLogisticsScheduledDate = null,
                ),
            )
        }
        onReady()
    }

    fun resolveInventoryConflict(
        finding: InventoryFindingDto,
        strategy: String,
        reason: String?,
        onResolved: () -> Unit,
    ) = command {
        require(strategy == "ACCEPT_REGISTRY" || strategy == "KEEP_INSPECTION") {
            "Неизвестный способ разрешения конфликта"
        }
        val session = mutableState.value.inventorySession
            ?: throw IllegalStateException("Активная инвентаризация не найдена")
        val latest = mutableState.value.inventoryFindings
            .firstOrNull { it.id == finding.id }
            ?: finding
        if (latest.conflicts.isEmpty()) {
            throw IllegalStateException("Конфликт уже разрешён. Обновите данные")
        }
        val normalizedReason = reason?.trim()?.takeIf(String::isNotEmpty)
        if (strategy == "KEEP_INSPECTION" && normalizedReason == null) {
            throw IllegalArgumentException(
                "Укажите причину, почему нужно оставить данные осмотра",
            )
        }
        if ((normalizedReason?.length ?: 0) > 2_000) {
            throw IllegalArgumentException("Причина не может быть длиннее 2000 символов")
        }
        try {
            backend.api.resolveInventoryConflict(
                inventoryId = session.id,
                findingId = latest.id,
                request = ResolveInventoryConflictRequest(
                    expectedSessionRevision = session.sessionRevision,
                    expectedFindingRevision = latest.findingRevision,
                    strategy = strategy,
                    reason = if (strategy == "KEEP_INSPECTION") {
                        normalizedReason
                    } else {
                        null
                    },
                ),
            )
        } catch (failure: HttpException) {
            if (failure.code() == 409) {
                refreshInventory()
                throw IllegalStateException(
                    "Конфликт уже изменился. Данные обновлены — выберите действие снова",
                    failure,
                )
            }
            throw failure
        }
        refreshInventory()
        message(
            if (strategy == "ACCEPT_REGISTRY") {
                "Данные реестра приняты"
            } else {
                "Данные осмотра сохранены"
            },
        )
        onResolved()
    }

    fun editInventory(update: (InventoryEditorState) -> InventoryEditorState) {
        mutableState.update { current ->
            current.copy(inventoryEditor = current.inventoryEditor?.let(update))
        }
    }

    fun editInventoryPlan(
        update: (MaintenanceEditorState) -> MaintenanceEditorState,
    ) {
        editInventory { inventory ->
            val current = inventory.toMaintenancePlanEditor()
            inventory.withMaintenancePlanEditor(
                normalizeMaintenanceEditor(update(current)),
            )
        }
    }

    fun addInventoryCatalogNodes(
        nodes: List<CatalogNodeDto>,
        quantity: String,
        comment: String,
        existingWorkLineId: String? = null,
        photoUris: List<String> = emptyList(),
        mediaReferences: List<MediaReferenceDto> = emptyList(),
    ): Boolean {
        val inventory = mutableState.value.inventoryEditor ?: return false
        val canonicalQuantity = runCatching {
            canonicalMaintenanceQuantity(quantity)
        }.getOrElse { failure ->
            message(failure.message ?: "Проверьте количество")
            return false
        }
        val canonicalComment = comment.trim()
        if (canonicalComment.length > 2_000) {
            message("Комментарий строки не может быть длиннее 2000 символов")
            return false
        }
        val canonicalNodes = nodes
            .mapNotNull { node -> maintenanceCatalogNodesById[node.id] }
            .filter(CatalogNodeDto::isOperationalEstimateNode)
            .filter { node ->
                node.isAvailableForMaintenanceMode(
                    mode = MaintenanceEditorMode.REPAIR,
                    nodesById = maintenanceCatalogNodesById,
                )
            }
            .distinctBy(CatalogNodeDto::id)
        if (canonicalNodes.isEmpty()) {
            message("Выбранные позиции больше недоступны в активном каталоге")
            return false
        }
        if ((photoUris.isNotEmpty() || mediaReferences.isNotEmpty()) &&
            canonicalNodes.count { node -> node.nodeType == "WORK" } != 1
        ) {
            message("Фото можно прикрепить только к одной выбранной работе")
            return false
        }
        if (!inventory.toMaintenancePlanEditor().hasSelectedCatalogWork(
                nodes = canonicalNodes,
                lineId = existingWorkLineId,
            )
        ) {
            message("Выбранная работа уже изменилась. Выберите её снова.")
            return false
        }
        editInventoryPlan { editor ->
            applyMaintenanceCatalogNodes(
                editor = editor,
                nodes = canonicalNodes,
                quantity = canonicalQuantity,
                comment = canonicalComment,
                existingWorkLineId = existingWorkLineId,
                photoUris = photoUris,
                mediaReferences = mediaReferences,
            )
        }
        return true
    }

    fun chooseInventoryOrigin(origin: String) {
        require(origin == "ADDED_NEW" || origin == "ADDED_USED")
        editInventory { it.withInventoryCreationOrigin(origin) }
    }

    fun addInventoryPhoto(uri: String) {
        editInventory { editor ->
            if (uri in editor.photoUris) editor
            else editor.copy(photoUris = editor.photoUris + uri)
        }
    }

    fun removeInventoryPhoto(uri: String) {
        editInventory { editor -> editor.removeInventoryPhoto(uri) }
    }

    fun selectInventoryCoverPhoto(uri: String) {
        editInventory { editor ->
            require(uri in editor.photoUris) { "Выбранная фотография не найдена" }
            editor.copy(coverPhotoUri = uri)
        }
    }

    /** Marks the original media asset for an absolute 90° clockwise rotation on save. */
    fun rotateInventoryPhoto(uri: String) {
        editInventory { editor ->
            editor.rotateInventoryPhoto(uri)
        }
    }

    fun closeInventoryEditor() {
        mutableState.update { it.copy(inventoryEditor = null) }
    }

    fun saveInventoryInspection(onSaved: () -> Unit) = command {
        val initialSession = mutableState.value.inventorySession
            ?: throw IllegalStateException("Активная инвентаризация не найдена")
        val editor = mutableState.value.inventoryEditor
            ?: throw IllegalStateException("Бытовка не выбрана")
        if (!editor.canInspect) {
            throw IllegalStateException(
                editor.finding?.conflicts?.joinToString { it.message }
                    ?: "Конфликт сверки не позволяет подтвердить бытовку",
            )
        }
        editor.inventoryPhotoValidationError()
            ?.let { throw IllegalArgumentException(it) }
        editor.inventoryEquipmentObservationValidationError()
            ?.let { throw IllegalArgumentException(it) }
        val passport = editor.passport()
        val equipmentObservation = editor.inventoryEquipmentObservation()
        var finding = editor.finding
        var session = initialSession
        var createSignature: String? = null
        if (editor.isCreation) {
            editor.inventoryCreationValidationError(
                hasCreationPhoto = editor.photoUris.isNotEmpty(),
                hasCoverPhoto = editor.coverPhotoUri in editor.photoUris,
            )
                ?.let { throw IllegalArgumentException(it) }
            val origin = editor.creationOrigin
                ?: throw IllegalArgumentException("Выберите: новая или б/у")
            val signature = buildString {
                append(
                    "inventory-create:${session.id}:${session.sessionRevision}:" +
                        "${editor.findingId}:$origin:${editor.number}:",
                )
                append(passport.toSortedMap().entries.joinToString())
            }
            createSignature = signature
            finding = backend.api.createInventoryAsset(
                inventoryId = session.id,
                findingId = editor.findingId,
                idempotencyKey = logisticsCommandKey(signature),
                request = CreateFindingAssetRequest(
                    expectedSessionRevision = session.sessionRevision,
                    expectedFindingRevision = 0,
                    origin = origin,
                    displayCanonicalNumber = editor.number,
                    safePassport = passport,
                ),
            )
            session = backend.api.inventory(session.id)
        }
        val attached = requireNotNull(finding) { "Сервис не вернул найденную бытовку" }
        val orderedPhotoUris = editor.inventoryPhotoUrisForUpload()
        val pendingPhotoUris = editor.pendingInventoryPhotoUris()
        val photoSortOrderByUri = orderedPhotoUris.withIndex().associate { (index, uri) ->
            uri to index
        }
        val owner = MediaOwner(
            ownerType = "INVENTORY_FINDING",
            ownerId = attached.id,
            warehouseId = session.warehouseId,
            context = "INSPECTION",
        )
        val persistedMedia = editor.persistedInventoryMediaReferences()
        val readyPersistedMedia = persistedMedia.takeIf { it.isNotEmpty() }
            ?.let { persisted ->
                val readyReferences = retryMediaReadAfterOwnerProof {
                    backend.api.ownerMedia(
                        ownerType = "INVENTORY_FINDING",
                        ownerId = attached.id,
                        warehouseId = session.warehouseId,
                        context = "INSPECTION",
                    ).items
                        .asSequence()
                        .filter { it.status == "READY" && it.generation > 0 }
                        .map { MediaReferenceDto(it.id, it.generation) }
                        .toSet()
                }
                inventoryReadyPersistedMediaReferences(persisted, readyReferences)
            }
            .orEmpty()
        require(readyPersistedMedia.size == persistedMedia.size) {
            "Не все сохранённые фотографии готовы. Проверьте связь и повторите сохранение."
        }
        val existingMedia = inventoryExistingMediaReferences(
            persisted = readyPersistedMedia,
            uploadedByUri = editor.uploadedPhotoMedia,
        )
        if (orderedPhotoUris.isEmpty() && existingMedia.isEmpty()) {
            throw IllegalArgumentException("Добавьте хотя бы одну фотографию")
        }
        val existingCoverMediaId = editor.coverPhotoUri
            ?.let { uri ->
                editor.persistedPhotoMedia[uri] ?: editor.uploadedPhotoMedia[uri]
            }
            ?.mediaId
            ?: attached.coverMediaId?.takeIf { mediaId ->
                existingMedia.any { reference -> reference.mediaId == mediaId }
            }
            ?: existingMedia.firstOrNull()?.mediaId
        val planSelection = inventoryPlanSelection(
            editor = editor,
            coverMediaId = existingCoverMediaId,
        )
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.INVENTORY,
                title = "Проверка бытовки ${editor.number}",
                subtitle = "Инвентаризация",
                photos = pendingPhotoUris.map { uri ->
                    PendingBackgroundPhoto(
                        uri = uri,
                        owner = owner,
                        sortOrder = requireNotNull(photoSortOrderByUri[uri]),
                        cover = uri == editor.coverPhotoUri,
                        rotationDegrees = editor.photoRotationDegrees[uri] ?: 0,
                    )
                } + editor.planLines
                    .asSequence()
                    .filter { line -> line.lineType == "WORK" }
                    .flatMapIndexed { lineIndex, line ->
                        line.photoUris.distinct().mapIndexed { photoIndex, uri ->
                        PendingBackgroundPhoto(
                            uri = uri,
                            owner = owner,
                            sortOrder = orderedPhotoUris.size + lineIndex * 100 + photoIndex,
                            lineId = line.id,
                        )
                        }
                    }
                    .toList(),
                inventory = InventoryUploadCommand(
                    inventoryId = session.id,
                    findingId = attached.id,
                    expectedFindingRevision = attached.findingRevision,
                    inspection = if (planSelection == null) "READY" else "WORK_STAGED",
                    comment = editor.comment.trim(),
                    passportObservation = if (editor.observationPassport().isEmpty()) {
                        ObservationInput("ABSENT", null)
                    } else {
                        ObservationInput("PRESENT", editor.observationPassport())
                    },
                    equipmentObservation = equipmentObservation,
                    existingMedia = existingMedia,
                    existingMediaRotations = editor.persistedInventoryPhotoRotations()
                        .filter { rotation ->
                            existingMedia.any { media -> media == rotation.reference }
                        },
                    existingCoverMediaId = existingCoverMediaId,
                    planSelection = planSelection,
                    planLineIds = editor.planLines.map(MaintenanceLineEditorState::id),
                    planWorkLineIds = editor.planLines
                        .asSequence()
                        .filter { line -> line.lineType == "WORK" }
                        .map(MaintenanceLineEditorState::id)
                        .toList(),
                ),
            ),
        )
        createSignature?.let(commandKeys::complete)
        mutableState.update { current ->
            current.copy(
                inventoryEditor = current.inventoryEditor?.takeUnless {
                    it.findingId == editor.findingId
                },
            )
        }
        message("Проверка добавлена в фоновые загрузки")
        onSaved()
    }

    fun loadReturns() = command {
        val warehouseId = requireWarehouseId()
        val documents = backend.api.returns(warehouseId)
        if (mutableState.value.selectedWarehouseId != warehouseId) return@command
        mutableState.update { it.copy(returns = documents) }
    }

    fun loadShipments() = command {
        refreshShipments()
    }

    fun openShipment(documentId: String, onReady: () -> Unit) = command {
        val document = backend.api.shipment(documentId)
        require(document.documentType == "SHIPMENT") {
            "Сервис вернул не документ отгрузки"
        }
        val readinessFailure: Throwable?
        val readiness = if (document.needsShipmentFurnitureReadiness()) {
            val result = runCatching {
                backend.api.shipmentFurnitureReadiness(document.id)
            }
            readinessFailure = result.exceptionOrNull()
            result.getOrNull()
        } else {
            readinessFailure = null
            null
        }
        val labels = resolveLogisticsAssetLabels(listOf(document))
        mutableState.update {
            it.copy(
                selectedShipment = document,
                shipmentFurnitureReadiness = readiness,
                logisticsAssetLabels = it.logisticsAssetLabels + labels,
            )
        }
        onReady()
        if (readinessFailure != null) {
            message(
                "Не удалось проверить мебель. Отгрузка доступна для просмотра, " +
                    "но действия заблокированы",
            )
        }
    }

    fun closeShipment() {
        mutableState.update {
            it.copy(
                selectedShipment = null,
                shipmentFurnitureReadiness = null,
            )
        }
    }

    fun planShipment(
        driverSnapshot: String,
        scheduledDate: String,
    ) = command {
        val document = requireNotNull(mutableState.value.selectedShipment) {
            "Откройте отгрузку"
        }
        requireWarehouseAccess(document.warehouseId, "EDIT")
        require(document.state == "DRAFT" || document.state == "AWAITING_CONFIRMATION") {
            "План этой отгрузки больше нельзя изменить"
        }
        val driver = driverSnapshot.trim()
        require(driver.isNotEmpty()) { "Выберите водителя" }
        require(driver.length <= 512) { "Имя водителя не может быть длиннее 512 символов" }
        val date = scheduledDate.trim()
        runCatching { LocalDate.parse(date) }
            .getOrElse { throw IllegalArgumentException("Укажите корректную дату отгрузки") }
        if (document.shipmentPlanRequiresFurnitureReadiness()) {
            requireShipmentFurnitureReady(document)
        }
        val signature = "shipment-plan:${document.id}:${document.version}:$driver:$date"
        val updated = backend.api.replaceShipmentPlan(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = logisticsCommandKey(signature),
            request = ShipmentPlanRequest(driver, date),
        )
        commandKeys.complete(signature)
        applyShipmentProjection(updated)
        refreshShipmentReadiness(updated)
        message("План отгрузки сохранён")
    }

    fun createShipmentFurnitureTasks() = command {
        val document = requireNotNull(mutableState.value.selectedShipment) {
            "Откройте отгрузку"
        }
        requireWarehouseAccess(document.warehouseId, "EDIT")
        require(document.state == "DRAFT") {
            "Задания по мебели можно создать только до планирования отгрузки"
        }
        val readiness = requireNotNull(mutableState.value.shipmentFurnitureReadiness) {
            "Не удалось проверить комплектность мебели"
        }
        require(readiness.state == "REQUIRES_TASK_CREATION") {
            "Задания по мебели уже созданы или не требуются"
        }
        val signature = "shipment-furniture:${document.id}:${document.version}"
        backend.api.createShipmentFurnitureTasks(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        val refreshed = backend.api.shipment(document.id)
        applyShipmentProjection(refreshed)
        refreshShipmentReadiness(refreshed)
        message("Задания по мебели созданы")
    }

    fun confirmShipment() = command {
        val document = requireNotNull(mutableState.value.selectedShipment) {
            "Откройте отгрузку"
        }
        requireWarehouseAccess(document.warehouseId, "EDIT")
        require(document.state == "AWAITING_CONFIRMATION") {
            "Отгрузка больше не ожидает подтверждения"
        }
        requireShipmentFurnitureReady(document)
        val signature = "shipment-confirm:${document.id}:${document.version}"
        val updated = backend.api.confirmShipmentPreparation(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyShipmentProjection(updated)
        refreshShipmentReadiness(updated)
        message("Отгрузка подтверждена")
    }

    fun cancelShipment() = command {
        val document = requireNotNull(mutableState.value.selectedShipment) {
            "Откройте отгрузку"
        }
        requireWarehouseAccess(document.warehouseId, "EDIT")
        require(document.state in SHIPMENT_CANCELLABLE_STATES) {
            "Эту отгрузку больше нельзя отменить"
        }
        val signature = "shipment-cancel:${document.id}:${document.version}"
        val updated = backend.api.cancelShipment(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyShipmentProjection(updated)
        refreshShipmentReadiness(updated)
        message("Отгрузка отменена")
    }

    fun loadTransfers() = command {
        refreshTransfers()
    }

    fun startTransferEditor(onReady: () -> Unit) = command {
        val warehouseId = requireWarehouseId()
        val user = requireNotNull(mutableState.value.currentUser)
        requireWarehouseAccess(warehouseId, "EDIT")
        val destinations = mutableState.value.warehouses.filter { warehouse ->
            warehouse.id != warehouseId &&
                WarehouseAccessPolicy.hasAccess(user, warehouse.id, "EDIT")
        }
        require(destinations.isNotEmpty()) {
            "Нет другого активного склада с правом редактирования"
        }
        val candidates = backend.api.rentalItems(
            warehouseId = warehouseId,
            page = 0,
            size = 200,
        ).content
            .filter { it.status == "FREE" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RentalItemDto::number))
        val furnitureCatalog = backend.api.equipment(warehouseId)
            .map { it.equipment }
            .filter { it.active && it.category == "FURNITURE" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EquipmentCatalogItemDto::name))
        mutableState.update {
            it.copy(
                transferEditor = TransferEditorState(
                    destinationWarehouseIds = destinations.mapTo(mutableSetOf()) { it.id },
                    candidates = candidates,
                    furnitureCatalog = furnitureCatalog,
                ),
            )
        }
        onReady()
    }

    fun editTransferEditor(update: (TransferEditorState) -> TransferEditorState) {
        mutableState.update { current ->
            current.copy(
                transferEditor = current.transferEditor?.let { editor ->
                    update(editor).copy(idempotencyKey = UUID.randomUUID().toString())
                },
            )
        }
    }

    fun toggleTransferAsset(assetId: String, selected: Boolean) {
        editTransferEditor { editor ->
            if (editor.candidates.none { it.id == assetId && it.status == "FREE" }) {
                editor
            } else if (selected) {
                editor.copy(selectedAssetIds = editor.selectedAssetIds + assetId)
            } else {
                editor.copy(
                    selectedAssetIds = editor.selectedAssetIds - assetId,
                    furnitureReplacements = editor.furnitureReplacements - assetId,
                )
            }
        }
    }

    fun updateTransferFurnitureQuantity(
        assetId: String,
        equipmentId: String,
        quantity: String,
    ) {
        val parsed = quantity.trim().takeIf(String::isNotEmpty)?.toLongOrNull()
            ?: if (quantity.isBlank()) 0L else return
        if (parsed !in 0..999_999L) return
        editTransferEditor { editor ->
            val cabin = editor.candidates.firstOrNull {
                it.id == assetId && assetId in editor.selectedAssetIds
            } ?: return@editTransferEditor editor
            if (editor.furnitureCatalog.none { it.id == equipmentId }) {
                return@editTransferEditor editor
            }
            val initial = editor.furnitureReplacements[assetId]
                ?: cabin.contents
                    .filter { content ->
                        editor.furnitureCatalog.any { catalog ->
                            catalog.id == content.equipmentId
                        }
                    }
                    .associate { it.equipmentId to it.quantity }
            val updated = if (parsed == 0L) {
                initial - equipmentId
            } else {
                initial + (equipmentId to parsed)
            }
            editor.copy(
                furnitureReplacements = editor.furnitureReplacements + (assetId to updated),
            )
        }
    }

    fun resetTransferFurniture(assetId: String) {
        editTransferEditor { editor ->
            editor.copy(furnitureReplacements = editor.furnitureReplacements - assetId)
        }
    }

    fun closeTransferEditor() {
        mutableState.update { it.copy(transferEditor = null) }
    }

    fun createTransfer(onSaved: () -> Unit) = command {
        val editor = requireNotNull(mutableState.value.transferEditor) {
            "Откройте создание перемещения"
        }
        val sourceWarehouseId = requireWarehouseId()
        val destinationWarehouseId = editor.destinationWarehouseId
        require(destinationWarehouseId.isNotBlank()) { "Выберите склад назначения" }
        require(destinationWarehouseId != sourceWarehouseId) {
            "Склад назначения должен отличаться от склада отправления"
        }
        requireWarehouseAccess(sourceWarehouseId, "EDIT")
        requireWarehouseAccess(destinationWarehouseId, "EDIT")
        require(editor.selectedAssetIds.isNotEmpty()) {
            "Выберите хотя бы одну свободную бытовку"
        }
        require(editor.selectedAssetIds.size <= 100) {
            "В одном перемещении может быть не больше 100 бытовок"
        }
        val selected = editor.selectedAssetIds.map { id ->
            editor.candidates.firstOrNull { it.id == id && it.status == "FREE" }
                ?: throw IllegalArgumentException(
                    "Список свободных бытовок изменился. Откройте создание заново",
                )
        }
        val date = runCatching { LocalDate.parse(editor.scheduledDate) }
            .getOrElse { throw IllegalArgumentException("Укажите корректную дату задания") }
        require(!date.isBefore(LocalDate.now())) {
            "Для перемещения укажите дату, начиная с сегодняшней"
        }
        val driver = editor.driverSnapshot.trim().takeIf(String::isNotEmpty)
        require((driver?.length ?: 0) <= 512) {
            "Имя водителя не может быть длиннее 512 символов"
        }
        val created = backend.api.createTransfer(
            idempotencyKey = editor.idempotencyKey,
            request = CreateTransferRequest(
                warehouseId = sourceWarehouseId,
                destinationWarehouseId = destinationWarehouseId,
                driverSnapshot = driver,
                scheduledDate = date.toString(),
                lines = selected.map { item ->
                    TransferLineRequest(item.id, item.version)
                },
                furnitureReplacements = editor.furnitureReplacements
                    .filterKeys(editor.selectedAssetIds::contains)
                    .toSortedMap()
                    .map { (assetId, contents) ->
                        TransferFurnitureReplacementRequest(
                            assetId = assetId,
                            contents = contents
                                .filterValues { it > 0 }
                                .toSortedMap()
                                .map { (equipmentId, quantity) ->
                                    require(editor.furnitureCatalog.any { it.id == equipmentId }) {
                                        "Состав мебели изменился. Откройте создание заново"
                                    }
                                    CabinFurnitureRequirementDto(equipmentId, quantity)
                                },
                        )
                    },
            ),
        )
        closeTransferEditor()
        applyTransferProjection(created)
        message("Перемещение создано")
        onSaved()
    }

    fun openTransfer(documentId: String, onReady: () -> Unit) = command {
        val document = backend.api.transfer(documentId)
        require(document.documentType == "TRANSFER") {
            "Сервис вернул не документ перемещения"
        }
        val readinessFailure: Throwable?
        val readiness = if (document.needsTransferFurnitureReadiness()) {
            val result = runCatching {
                backend.api.transferFurnitureReadiness(document.id)
            }
            readinessFailure = result.exceptionOrNull()
            result.getOrNull()
        } else {
            readinessFailure = null
            null
        }
        val labels = resolveLogisticsAssetLabels(listOf(document))
        mutableState.update {
            it.copy(
                selectedTransfer = document,
                transferFurnitureReadiness = readiness,
                transferArrivalLineId = null,
                transferPhotoUris = emptyList(),
                transferReadyMedia = emptyList(),
                logisticsAssetLabels = it.logisticsAssetLabels + labels,
            )
        }
        onReady()
        if (readinessFailure != null) {
            message(
                "Не удалось проверить мебель. Перемещение доступно для просмотра, " +
                    "но отправка заблокирована",
            )
        }
    }

    fun closeTransfer() {
        mutableState.update {
            it.copy(
                selectedTransfer = null,
                transferFurnitureReadiness = null,
                transferArrivalLineId = null,
                transferPhotoUris = emptyList(),
                transferReadyMedia = emptyList(),
            )
        }
    }

    fun departTransferLine(lineId: String) = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        val line = document.lines.firstOrNull { it.id == lineId }
            ?: throw IllegalArgumentException("Строка перемещения не найдена")
        require(
            line.state == "PENDING" &&
                (document.state == "DRAFT" || document.state == "DEPARTING"),
        ) {
            "Эту бытовку больше нельзя отправить"
        }
        requireTransferFurnitureReady(document)
        val signature =
            "transfer-depart:${document.id}:${document.version}:${line.id}:${line.version}"
        val updated = backend.api.departTransferLine(
            documentId = document.id,
            lineId = line.id,
            expectedVersion = document.version,
            expectedLineVersion = line.version,
            idempotencyKey = logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyTransferProjection(updated)
        refreshTransferReadiness(updated)
        message("Бытовка отправлена")
    }

    fun startTransferArrival(lineId: String, onReady: () -> Unit) = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        val line = document.lines.firstOrNull { it.id == lineId }
            ?: throw IllegalArgumentException("Строка перемещения не найдена")
        require(
            line.state == "DEPARTED" &&
                (document.state == "IN_TRANSIT" || document.state == "ARRIVING"),
        ) {
            "Эту бытовку сейчас нельзя принять"
        }
        val ready = retryMediaReadAfterOwnerProof {
            backend.api.ownerMedia(
                ownerType = "LOGISTICS_TRANSFER",
                documentId = document.id,
                lineId = line.id,
                warehouseId = requireNotNull(document.destinationWarehouseId),
                context = "TRANSFER",
            )
        }.items
            .filter { media -> media.status == "READY" && media.generation > 0 }
            .sortedBy { media -> media.sortOrder }
            .map { media -> MediaReferenceDto(media.id, media.generation) }
        mutableState.update {
            it.copy(
                transferArrivalLineId = line.id,
                transferPhotoUris = emptyList(),
                transferReadyMedia = ready,
            )
        }
        onReady()
    }

    fun closeTransferArrival() {
        mutableState.update {
            it.copy(
                transferArrivalLineId = null,
                transferPhotoUris = emptyList(),
                transferReadyMedia = emptyList(),
            )
        }
    }

    fun addTransferPhoto(uri: String) {
        mutableState.update {
            it.copy(
                transferPhotoUris =
                    if (uri in it.transferPhotoUris) it.transferPhotoUris
                    else it.transferPhotoUris + uri,
            )
        }
    }

    fun removeTransferPhoto(uri: String) {
        mutableState.update { it.copy(transferPhotoUris = it.transferPhotoUris - uri) }
    }

    fun arriveTransferLine(onSaved: () -> Unit) = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        val lineId = requireNotNull(mutableState.value.transferArrivalLineId) {
            "Выберите бытовку для приёмки"
        }
        val line = document.lines.firstOrNull { it.id == lineId }
            ?: throw IllegalArgumentException("Строка перемещения не найдена")
        val localUris = mutableState.value.transferPhotoUris
        val existingMedia = mutableState.value.transferReadyMedia
            .distinctBy(MediaReferenceDto::mediaId)
        require(existingMedia.isNotEmpty() || localUris.isNotEmpty()) {
            "Добавьте хотя бы одну фотографию приёмки"
        }
        require(existingMedia.size + localUris.size <= 20) {
            "Для строки можно приложить не больше 20 фотографий"
        }
        val owner = MediaOwner(
            ownerType = "LOGISTICS_TRANSFER",
            documentId = document.id,
            lineId = line.id,
            warehouseId = requireNotNull(document.destinationWarehouseId),
            context = "TRANSFER",
        )
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.LOGISTICS,
                title = "Приёмка перемещения",
                subtitle = mutableState.value.logisticsAssetLabels[line.assetId]
                    ?: line.assetId,
                photos = localUris.mapIndexed { index, uri ->
                    PendingBackgroundPhoto(uri = uri, owner = owner, sortOrder = index)
                },
                transferArrival = TransferArrivalUploadCommand(
                    documentId = document.id,
                    lineId = line.id,
                    expectedDocumentVersion = document.version,
                    expectedLineVersion = line.version,
                    existingMedia = existingMedia,
                    idempotencyKey = UUID.randomUUID().toString(),
                ),
            ),
        )
        closeTransferArrival()
        message("Приёмка перемещения добавлена в фоновые загрузки")
        onSaved()
    }

    fun cancelTransfer() = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        require(document.state == "DRAFT") { "Это перемещение больше нельзя отменить" }
        val signature = "transfer-cancel:${document.id}:${document.version}"
        val updated = backend.api.cancelTransfer(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = logisticsCommandKey(signature),
        )
        commandKeys.complete(signature)
        applyTransferProjection(updated)
        refreshTransferReadiness(updated)
        message("Перемещение отменено")
    }

    fun reconcileTransfer(reason: String) = command {
        val document = requireNotNull(mutableState.value.selectedTransfer) {
            "Откройте перемещение"
        }
        requireTransferManageAccess(document)
        require(
            document.state == "CONFLICT" ||
                document.state == "RECONCILIATION_REQUIRED",
        ) {
            "Сверка для этого перемещения не требуется"
        }
        val normalized = reason.trim()
        require(normalized.isNotEmpty()) { "Укажите причину сверки" }
        require(normalized.length <= 500) {
            "Причина сверки не может быть длиннее 500 символов"
        }
        val signature = "transfer-reconcile:${document.id}:${document.version}:$normalized"
        val updated = backend.api.reconcileTransfer(
            documentId = document.id,
            expectedVersion = document.version,
            idempotencyKey = logisticsCommandKey(signature),
            request = ReconcileLogisticsRequest(normalized),
        )
        commandKeys.complete(signature)
        applyTransferProjection(updated)
        refreshTransferReadiness(updated)
        message("Сверка перемещения выполнена")
    }

    fun openReturn(document: LogisticsDocumentDto) {
        mutableState.update {
            it.copy(
                selectedReturn = document,
                returnPhotoUris = document.lines.associate { line -> line.id to emptyList() },
                returnReadyMedia = document.lines.associate { line -> line.id to emptyList() },
                returnEquipmentConfirmed = emptySet(),
                returnEquipmentCatalog = emptyList(),
                returnEquipmentCatalogStatus = ReturnEquipmentCatalogStatus.LOADING,
                returnShortageEquipment = emptyMap(),
                returnShortageQuantity = emptyMap(),
            )
        }
        command {
            val currentDocument = try {
                backend.api.returnDocument(document.id).also { loaded ->
                    require(loaded.documentType == "RETURN") {
                        "RWMS вернул не документ возврата"
                    }
                }
            } catch (failure: Throwable) {
                mutableState.update { current ->
                    current.withUnavailableReturnEquipmentCatalog(document.id)
                }
                throw failure
            }
            applyCurrentReturnDocument(currentDocument)
            val equipment = try {
                backend.api.equipment(currentDocument.warehouseId).map { it.equipment }
            } catch (failure: Throwable) {
                mutableState.update { current ->
                    current.withUnavailableReturnEquipmentCatalog(currentDocument.id)
                }
                throw failure
            }
            mutableState.update { current ->
                current.withLoadedReturnEquipmentCatalog(currentDocument.id, equipment)
            }
            val ready = currentDocument.lines.associate { line ->
                line.id to retryMediaReadAfterOwnerProof {
                    backend.api.ownerMedia(
                        ownerType = "LOGISTICS_RETURN",
                        documentId = currentDocument.id,
                        lineId = line.id,
                        warehouseId = currentDocument.warehouseId,
                        context = "RETURN_INSPECTION",
                    )
                }.items
                    .filter { it.status == "READY" && it.generation > 0 }
                    .map { MediaReferenceDto(it.id, it.generation) }
            }
            mutableState.update { current ->
                current.withLoadedReturnReadyMedia(
                    documentId = currentDocument.id,
                    readyMedia = ready,
                )
            }
        }
    }

    fun closeReturn() {
        mutableState.update {
            it.copy(
                selectedReturn = null,
                returnPhotoUris = emptyMap(),
                returnReadyMedia = emptyMap(),
                returnEquipmentConfirmed = emptySet(),
                returnEquipmentCatalog = emptyList(),
                returnEquipmentCatalogStatus = ReturnEquipmentCatalogStatus.NOT_LOADED,
                returnShortageEquipment = emptyMap(),
                returnShortageQuantity = emptyMap(),
            )
        }
    }

    fun addReturnPhoto(lineId: String, uri: String) {
        mutableState.update { current ->
            val photos = current.returnPhotoUris[lineId].orEmpty()
            current.copy(
                returnPhotoUris = current.returnPhotoUris +
                    (lineId to if (uri in photos) photos else photos + uri),
            )
        }
    }

    fun removeReturnPhoto(lineId: String, uri: String) {
        mutableState.update { current ->
            current.copy(
                returnPhotoUris = current.returnPhotoUris +
                    (lineId to (current.returnPhotoUris[lineId].orEmpty() - uri)),
            )
        }
    }

    fun confirmReturnEquipment(lineId: String, confirmed: Boolean) {
        mutableState.update {
            it.copy(
                returnEquipmentConfirmed =
                    if (confirmed) it.returnEquipmentConfirmed + lineId
                    else it.returnEquipmentConfirmed - lineId,
            )
        }
    }

    fun updateReturnShortage(lineId: String, equipmentId: String, quantity: String) {
        mutableState.update {
            it.copy(
                returnShortageEquipment = it.returnShortageEquipment + (lineId to equipmentId),
                returnShortageQuantity = it.returnShortageQuantity + (lineId to quantity),
            )
        }
    }

    fun acceptReturn(onSaved: () -> Unit) = command {
        val displayedDocument = requireNotNull(mutableState.value.selectedReturn)
        val document = currentReturnInspectionDocument(displayedDocument)
        val missingConfirmation = document.lines.firstOrNull {
            it.id !in mutableState.value.returnEquipmentConfirmed
        }
        if (missingConfirmation != null) {
            throw IllegalArgumentException(
                "Подтвердите комплектность мебели для строки ${missingConfirmation.lineNumber}",
            )
        }
        enqueueReturnUpload(
            document = document,
            action = ReturnUploadAction.ACCEPT,
        )
        closeReturn()
        message("Приёмка возврата добавлена в фоновые загрузки")
        onSaved()
    }

    fun requestReturnEstimate(onSaved: () -> Unit) = command {
        val displayedDocument = requireNotNull(mutableState.value.selectedReturn)
        val document = currentReturnInspectionDocument(displayedDocument)
        val state = mutableState.value
        when (state.returnEquipmentCatalogStatus) {
            ReturnEquipmentCatalogStatus.AVAILABLE -> Unit
            ReturnEquipmentCatalogStatus.EMPTY -> throw IllegalStateException(
                "В справочнике выбранного склада нет активного оборудования",
            )
            ReturnEquipmentCatalogStatus.UNAVAILABLE -> throw IllegalStateException(
                "Справочник оборудования недоступен. Повторно откройте возврат после восстановления связи",
            )
            ReturnEquipmentCatalogStatus.NOT_LOADED,
            ReturnEquipmentCatalogStatus.LOADING ->
                throw IllegalStateException("Справочник оборудования ещё загружается")
        }
        val shortageInputs = document.lines.associate { line ->
            val equipment = state.returnShortageEquipment[line.id]
                .let(state.returnEquipmentCatalog::selectedReturnShortageEquipment)
                ?: throw IllegalArgumentException(
                    "Укажите оборудование с недостачей для строки ${line.lineNumber}",
                )
            val quantity = state.returnShortageQuantity[line.id]
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw IllegalArgumentException(
                    "Укажите количество недостачи для строки ${line.lineNumber}",
                )
            line.id to EquipmentShortageRequest(equipment.id, quantity)
        }
        enqueueReturnUpload(
            document = document,
            action = ReturnUploadAction.REQUEST_ESTIMATE,
            shortages = shortageInputs,
        )
        closeReturn()
        message("Запрос на смету добавлен в фоновые загрузки")
        onSaved()
    }

    fun loadMaintenance() = command { refreshMaintenance() }

    fun loadRepairQueue() = command {
        coroutineScope {
            async { refreshMaintenance() }
            async { refreshRepairTaskBoards() }
        }
    }

    fun moveRepairQueueEntry(
        item: RepairQueueItem,
        targetDate: String,
        targetIndex: Int,
    ) = command {
        if (!repairQueueEntryCanMove(item.entry)) {
            throw IllegalStateException("Это задание уже нельзя перемещать")
        }
        val warehouseId = requireWarehouseId()
        try {
            backend.api.moveTaskBoardEntry(
                warehouseId = warehouseId,
                entryId = item.entry.id,
                request = MoveTaskBoardEntryRequest(
                    expectedVersion = item.entry.version,
                    expectedTaskVersion = item.entry.taskVersion,
                    targetQueueId = item.queue.queueId,
                    targetIndex = targetIndex,
                    targetDate = targetDate,
                ),
            )
            refreshRepairTaskBoards()
            message("Очередь ремонта обновлена")
        } catch (failure: HttpException) {
            if (failure.code() != 409) throw failure
            refreshRepairTaskBoards()
            message("Очередь уже изменилась. Данные обновлены — повторите перемещение")
        }
    }

    /**
     * A column exchange is not a series of card moves.  The task-board owns the scheduled date,
     * so submit the full real+shadow snapshot evidence for both columns and let it atomically
     * exchange dates while retaining every queue position.
     */
    fun swapRepairQueueDateColumns(
        firstDate: String,
        secondDate: String,
        onSwapped: () -> Unit,
    ) = command {
        if (firstDate == secondDate) return@command
        val warehouseId = requireWarehouseId()
        val boards = mutableState.value.repairTaskBoards
        val first = boards.firstOrNull { it.selectedDate == firstDate }
            ?: throw IllegalStateException("Колонка даты $firstDate устарела. Обновите очередь")
        val second = boards.firstOrNull { it.selectedDate == secondDate }
            ?: throw IllegalStateException("Колонка даты $secondDate устарела. Обновите очередь")
        val expectations = (first.columns.asSequence().flatMap { it.entries.asSequence() } +
            second.columns.asSequence().flatMap { it.entries.asSequence() })
            .map { entry ->
                TaskBoardDateEntryExpectationDto(
                    entryId = entry.id,
                    expectedVersion = entry.version,
                    expectedTaskVersion = entry.taskVersion,
                )
            }
            .distinctBy(TaskBoardDateEntryExpectationDto::entryId)
            .toList()
        if (expectations.isEmpty()) {
            throw IllegalStateException("В выбранных колонках нет заданий для обмена датами")
        }
        try {
            backend.api.swapTaskBoardDates(
                warehouseId = warehouseId,
                request = SwapTaskBoardDatesRequest(
                    firstDate = firstDate,
                    secondDate = secondDate,
                    entries = expectations,
                ),
            )
            refreshRepairTaskBoards()
            onSwapped()
            message("Даты колонок очереди обменены")
        } catch (failure: HttpException) {
            if (failure.code() != 409) throw failure
            refreshRepairTaskBoards()
            throw IllegalStateException(
                "Очередь уже изменилась. Данные обновлены — повторите обмен датами",
                failure,
            )
        }
    }

    fun loadAcceptance() = command { refreshAcceptance() }

    fun openAcceptance(repairId: String) = command {
        val warehouseId = requireWarehouseId()
        val repair = backend.api.repair(repairId, warehouseId)
        if (repair.executionState != "COMPLETED" || repair.acceptanceState != "PENDING") {
            throw IllegalStateException("Ремонт больше не ожидает приёмки")
        }
        val linkedEstimate = repair.estimateId?.let { estimateId ->
            backend.api.estimate(estimateId, warehouseId)
        }
        val asset = maintenanceRentalItem(repair.rentalItemId, warehouseId)
        val readyAcceptanceMedia = retryMediaReadAfterOwnerProof {
            backend.api.ownerMedia(
                ownerType = "MAINTENANCE_ACCEPTANCE",
                ownerId = repair.id,
                warehouseId = warehouseId,
                context = "ACCEPTANCE",
            )
        }.items
            .filter { media -> media.status == "READY" && media.generation > 0 }
            .sortedBy { media -> media.sortOrder }
            .map { media -> MediaReferenceDto(media.id, media.generation) }
        mutableState.update { current ->
            current.copy(
                maintenanceAssetLabels = current.maintenanceAssetLabels + (asset.id to asset.number),
                acceptanceEditor = MaintenanceAcceptanceEditorState(
                    repair = repair,
                    asset = asset,
                    cabinPhotos = acceptanceCabinMedia(repair, linkedEstimate),
                    readyMedia = readyAcceptanceMedia,
                ),
                acceptanceGallery = null,
            )
        }
    }

    fun closeAcceptance() {
        mutableState.update {
            it.copy(
                acceptanceEditor = null,
                acceptanceGallery = null,
            )
        }
    }

    fun openAcceptanceCabinPhotos() = command {
        val editor = requireNotNull(mutableState.value.acceptanceEditor) {
            "Откройте ремонт для приёмки"
        }
        loadAcceptanceGallery(editor.repair.id, editor.cabinPhotos)
    }

    fun openAcceptanceStagePhotos(stageId: String) = command {
        val editor = requireNotNull(mutableState.value.acceptanceEditor) {
            "Откройте ремонт для приёмки"
        }
        val stage = editor.repair.plan.stages.firstOrNull { it.id == stageId }
            ?: throw IllegalStateException("Этап ремонта больше не найден")
        loadAcceptanceGallery(editor.repair.id, acceptanceStageMedia(stage))
    }

    fun openAcceptanceWorkSourcePhotos(stageId: String, workLineId: String) = command {
        val editor = requireNotNull(mutableState.value.acceptanceEditor) {
            "Откройте ремонт для приёмки"
        }
        val stage = editor.repair.plan.stages.firstOrNull { it.id == stageId }
            ?: throw IllegalStateException("Этап ремонта больше не найден")
        val work = stage.workLines.firstOrNull { line -> line.id == workLineId }
            ?: throw IllegalStateException("Работа больше не найдена в этапе")
        loadAcceptanceGallery(
            editor.repair.id,
            acceptanceWorkSourceMedia(editor.repair, stage, work),
        )
    }

    fun closeAcceptanceGallery() {
        mutableState.update { it.copy(acceptanceGallery = null) }
    }

    fun editAcceptanceComment(value: String) {
        mutableState.update { current ->
            current.copy(
                acceptanceEditor = current.acceptanceEditor?.copy(
                    comment = value.take(2_000),
                ),
            )
        }
    }

    fun acceptAcceptanceWork(workLineId: String) {
        mutableState.update { current ->
            val editor = current.acceptanceEditor ?: return@update current
            val exists = editor.repair.plan.stages.any { stage ->
                stage.workLines.any { line -> line.id == workLineId }
            }
            require(exists) { "Работа больше не найдена в ремонте" }
            current.copy(
                acceptanceEditor = editor.copy(
                    acceptedWorkLineIds = editor.acceptedWorkLineIds + workLineId,
                ),
            )
        }
    }

    fun addAcceptancePhoto(uri: String) {
        mutableState.update { current ->
            val editor = current.acceptanceEditor ?: return@update current
            current.copy(
                acceptanceEditor = editor.copy(
                    photoUris = if (uri in editor.photoUris) {
                        editor.photoUris
                    } else {
                        editor.photoUris + uri
                    },
                ),
            )
        }
    }

    fun removeAcceptancePhoto(uri: String) {
        mutableState.update { current ->
            current.copy(
                acceptanceEditor = current.acceptanceEditor?.let { editor ->
                    editor.copy(photoUris = editor.photoUris - uri)
                },
            )
        }
    }

    fun acceptMaintenanceRepair(onSaved: () -> Unit) = command {
        val editor = requireNotNull(mutableState.value.acceptanceEditor) {
            "Откройте ремонт для приёмки"
        }
        val localUris = editor.photoUris
        val existingMedia = editor.readyMedia.distinctBy(MediaReferenceDto::mediaId)
        require(existingMedia.isNotEmpty() || localUris.isNotEmpty()) {
            "Добавьте хотя бы одно фото приёмки перед принятием"
        }
        require(editor.hasAcceptedAllWorkLines()) {
            "Примите каждую работу или отправьте её на доработку"
        }
        val warehouseId = requireWarehouseId()
        val owner = MediaOwner(
            ownerType = "MAINTENANCE_ACCEPTANCE",
            ownerId = editor.repair.id,
            warehouseId = warehouseId,
            context = "ACCEPTANCE",
        )
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.ACCEPTANCE,
                title = "Приёмка ремонта",
                subtitle = mutableState.value.maintenanceAssetLabels[editor.repair.rentalItemId]
                    ?: editor.repair.rentalItemId,
                photos = localUris.mapIndexed { index, uri ->
                    PendingBackgroundPhoto(uri = uri, owner = owner, sortOrder = index)
                },
                acceptance = AcceptanceUploadCommand(
                    repairId = editor.repair.id,
                    warehouseId = warehouseId,
                    expectedVersion = editor.repair.version,
                    comment = editor.comment.trim().takeIf(String::isNotEmpty),
                    existingMedia = existingMedia,
                    idempotencyKey = editor.idempotencyKey,
                ),
            ),
        )
        closeAcceptance()
        message("Приёмка добавлена в фоновые загрузки")
        onSaved()
    }

    private suspend fun loadAcceptanceGallery(
        repairId: String,
        collection: AcceptanceMediaCollection,
    ) {
        val warehouseId = requireWarehouseId()
        if (mutableState.value.acceptanceEditor?.repair?.id != repairId) return
        val uniqueItems = collection.items.distinctBy { media ->
            listOf(
                media.ownerType,
                media.ownerId,
                media.context,
                media.reference.mediaId,
                media.reference.generation.toString(),
            ).joinToString(":")
        }
        val requests = uniqueItems.map { media ->
            ScopedMediaDownload(
                reference = media.reference,
                scopes = listOf(
                    MaintenanceMediaScope(
                        ownerType = media.ownerType,
                        ownerId = media.ownerId,
                        context = media.context,
                    ),
                ),
            )
        }
        val photoUris = loadScopedPhotoUris(requests, warehouseId)
            .map(ScopedMediaResult::uri)
        if (mutableState.value.acceptanceEditor?.repair?.id != repairId) return
        mutableState.update {
            it.copy(
                acceptanceGallery = AcceptanceGalleryState(
                    title = collection.title,
                    photoUris = photoUris,
                    emptyMessage = if (uniqueItems.isNotEmpty() && photoUris.isEmpty()) {
                        "Фотографии есть, но сейчас не загрузились. Проверьте связь с сервером и повторите."
                    } else {
                        collection.emptyMessage
                    },
                ),
            )
        }
    }

    private data class ScopedMediaResult(
        val reference: MediaReferenceDto,
        val uri: String,
        val rotationDegrees: Int,
    )

    private data class ScopedDownloadedPhoto(
        val uri: String,
        val rotationDegrees: Int,
    )

    private suspend fun loadInventoryPhotoUris(
        finding: InventoryFindingDto,
        warehouseId: String,
    ): List<ScopedMediaResult> = loadScopedPhotoUris(
        requests = finding.media
            .distinctBy(MediaReferenceDto::mediaId)
            .map { reference ->
                ScopedMediaDownload(
                    reference = reference,
                    scopes = listOf(
                        MaintenanceMediaScope(
                            ownerType = "INVENTORY_FINDING",
                            ownerId = finding.id,
                            context = "INSPECTION",
                        ),
                    ),
                )
            },
        warehouseId = warehouseId,
    )

    private suspend fun loadMaintenancePhotoUris(
        references: List<MediaReferenceDto>,
        scopes: List<MaintenanceMediaScope>,
        warehouseId: String,
    ): Map<String, String> = loadScopedPhotoUris(
        requests = references.distinctBy(MediaReferenceDto::mediaId).map { reference ->
            ScopedMediaDownload(reference = reference, scopes = scopes)
        },
        warehouseId = warehouseId,
    ).associate { result -> result.reference.mediaId to result.uri }

    private suspend fun loadScopedPhotoUris(
        requests: List<ScopedMediaDownload>,
        warehouseId: String,
    ): List<ScopedMediaResult> {
        if (requests.isEmpty()) return emptyList()
        val allScopes = requests.flatMap(ScopedMediaDownload::scopes).distinct()
        val assetsByScope = coroutineScope {
            allScopes.map { scope ->
                async {
                    scope to runCatching {
                        retryMediaReadAfterOwnerProof {
                            backend.api.ownerMedia(
                                ownerType = scope.ownerType,
                                ownerId = scope.ownerId,
                                warehouseId = warehouseId,
                                context = scope.context,
                            )
                        }.items
                    }.getOrNull()
                }
            }.map { lookup -> lookup.await() }.toMap()
        }
        return coroutineScope {
            requests.map { request ->
                async {
                    val downloaded = downloadScopedPhoto(
                        request = request,
                        assetsByScope = assetsByScope,
                        warehouseId = warehouseId,
                    ) ?: return@async null
                    ScopedMediaResult(
                        reference = request.reference,
                        uri = downloaded.uri,
                        rotationDegrees = downloaded.rotationDegrees,
                    )
                }
            }.mapNotNull { download -> download.await() }
        }
    }

    private suspend fun downloadScopedPhoto(
        request: ScopedMediaDownload,
        assetsByScope: Map<MaintenanceMediaScope, List<MediaAssetDto>?>,
        warehouseId: String,
    ): ScopedDownloadedPhoto? {
        request.scopes.distinct().forEach { scope ->
            val asset = assetsByScope[scope]
                ?.firstOrNull { candidate ->
                    candidate.id == request.reference.mediaId &&
                        candidate.generation == request.reference.generation &&
                        candidate.status == "READY"
                }
            val preview = asset?.variants
                ?.sortedBy { variant ->
                    when (variant.kind) {
                        "SMALL" -> 0
                        "MEDIUM" -> 1
                        else -> 2
                    }
                }
                ?.firstOrNull()
            if (preview != null) {
                runCatching {
                    mediaDownloader.downloadVariant(
                        mediaId = request.reference.mediaId,
                        generation = request.reference.generation,
                        contentPath = preview.contentPath,
                    )
                }.getOrNull()?.let { uri ->
                    return ScopedDownloadedPhoto(uri, asset.rotationDegrees)
                }
            }
            runCatching {
                mediaDownloader.downloadOriginal(
                    mediaId = request.reference.mediaId,
                    generation = request.reference.generation,
                    ownerType = scope.ownerType,
                    ownerId = scope.ownerId,
                    warehouseId = warehouseId,
                    context = scope.context,
                )
            }.getOrNull()?.let { uri ->
                return ScopedDownloadedPhoto(uri, asset?.rotationDegrees ?: 0)
            }
        }
        return null
    }

    /**
     * A foregrounded app first checks the small active-version projection. If the catalog has
     * changed, it reloads the snapshot; an open editor is left untouched and receives the
     * explicit refresh action instead.
     */
    fun checkMaintenanceCatalogOnResume() {
        if (mutableState.value.authState != ManagerAuthState.SignedIn) return
        startMaintenanceCatalogScheduler()
        requestMaintenanceCatalogSync()
    }

    fun refreshMaintenanceCatalog() = command {
        val warehouseId = requireWarehouseId()
        maintenanceCatalogMutex.withLock {
            val catalog = fetchMaintenanceCatalog(warehouseId)
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock
            persistMaintenanceCatalog(warehouseId, catalog)
            maintenanceCatalogCache.markAttemptSlot(
                maintenanceCatalogSyncSlot(ZonedDateTime.now()),
            )
            applyMaintenanceCatalog(warehouseId, catalog)
        }
    }

    fun startMaintenanceEditor(
        mode: MaintenanceEditorMode,
        onReady: () -> Unit,
    ) = command {
        val warehouseId = requireWarehouseId()
        ensureMaintenanceCatalog(warehouseId)
        refreshRepairTaskBoards()
        assetSearchGeneration += 1
        mutableState.update {
            it.withStartedMaintenanceEditor(newMaintenanceEditor(mode))
        }
        onReady()
    }

    fun openEstimateEditor(id: String, onReady: () -> Unit) = command {
        val warehouseId = requireWarehouseId()
        ensureMaintenanceCatalog(warehouseId)
        refreshRepairTaskBoards()
        val estimate = backend.api.estimate(id, warehouseId)
        val linkedRepair = estimate.repairId?.let { repairId ->
            backend.api.repair(repairId, warehouseId)
        }
        val canAmend = estimate.lifecycle == "COMPLETED" &&
            linkedRepair?.executionState in PRE_START_REPAIR_STATES
        val asset = maintenanceRentalItem(estimate.rentalItemId, warehouseId)
        val revision = estimate.revisions.firstOrNull { it.revision == estimate.currentRevision }
            ?: throw IllegalStateException("Сервис не вернул текущую редакцию сметы")
        val workLineMedia = revision.lines
            .asSequence()
            .filter { line -> line.lineType == "WORK" }
            .flatMap { line -> line.mediaReferences.asSequence() }
            .toList()
        val readyPhotoUris = loadMaintenancePhotoUris(
            references = estimate.mediaReferences + workLineMedia,
            scopes = buildList {
                add(
                    MaintenanceMediaScope(
                        ownerType = "MAINTENANCE_ESTIMATE",
                        ownerId = estimate.id,
                        context = "ESTIMATE",
                    ),
                )
                linkedRepair?.let { repair ->
                    add(
                        MaintenanceMediaScope(
                            ownerType = "MAINTENANCE_REPAIR",
                            ownerId = repair.id,
                            context = "REPAIR",
                        ),
                    )
                }
            },
            warehouseId = warehouseId,
        )
        val routingByLineId = revision.plan.flatMap { stage ->
            stage.includedLineIds.map { lineId -> lineId to stage.routing }
        }.toMap()
        mutableState.update { current ->
            current.copy(
                maintenanceAssetLabels = current.maintenanceAssetLabels + (asset.id to asset.number),
                maintenanceEditor = MaintenanceEditorState(
                    mode = MaintenanceEditorMode.ESTIMATE,
                    entityId = estimate.id,
                    expectedVersion = estimate.version,
                    readOnly = estimate.lifecycle != "DRAFT" && !canAmend,
                    selectedAsset = asset,
                    dispatchDate = revision.dispatchDate,
                    sourceParty = revision.sourceParty.orEmpty(),
                    lines = revision.lines.map { line ->
                        line.toMaintenanceLineEditor(
                            customRouting = routingByLineId[line.id],
                        )
                    },
                    photoUris = emptyList(),
                    readyMedia = estimate.mediaReferences,
                    readyPhotoUris = readyPhotoUris,
                    priority = linkedRepair?.priority ?: DEFAULT_MAINTENANCE_PRIORITY,
                    movementToRepair = linkedRepair?.movementToRepair ?: false,
                    logisticsPlanningMode = linkedRepair?.logisticsPlanningMode,
                    logisticsScheduledDate = linkedRepair?.logisticsScheduledDate,
                    step = 1,
                    stages = revision.plan
                        .filter { stage -> stage.kind == "REPAIR_WORK" }
                        .map { stage -> stage.toMaintenanceStageEditor() },
                    documentState = estimate.lifecycle,
                    linkedRepairExpectedVersion = linkedRepair?.version,
                    coverPhotoKey = estimate.coverMediaId
                        ?.let(::maintenanceReadyPhotoKey)
                        ?: maintenanceInitialCoverPhotoKey(estimate.mediaReferences),
                ),
            )
        }
        onReady()
    }

    fun openRepairEditor(id: String, onReady: () -> Unit) = command {
        val warehouseId = requireWarehouseId()
        ensureMaintenanceCatalog(warehouseId)
        refreshRepairTaskBoards()
        val repair = backend.api.repair(id, warehouseId)
        val linkedEstimate = repair.estimateId?.let { estimateId ->
            backend.api.estimate(estimateId, warehouseId)
        }
        val asset = maintenanceRentalItem(repair.rentalItemId, warehouseId)
        val content = repairEditorContent(repair)
        val reworkCandidates = if (repair.kind == "REWORK") {
            backend.api.reworkCandidates(repair.sourceRepairId ?: repair.id, warehouseId).items
        } else {
            emptyList()
        }
        val readyReferences = (repair.mediaReferences + linkedEstimate?.mediaReferences.orEmpty())
            .distinctBy(MediaReferenceDto::mediaId)
        val workLineMedia = content.lines
            .asSequence()
            .filter { line -> line.lineType == "WORK" }
            .flatMap { line -> line.mediaReferences.asSequence() }
            .toList()
        val readyPhotoUris = loadMaintenancePhotoUris(
            references = readyReferences + workLineMedia,
            scopes = buildList {
                add(
                    MaintenanceMediaScope(
                        ownerType = "MAINTENANCE_REPAIR",
                        ownerId = repair.id,
                        context = "REPAIR",
                    ),
                )
                repair.estimateId?.let { estimateId ->
                    add(
                        MaintenanceMediaScope(
                            ownerType = "MAINTENANCE_ESTIMATE",
                            ownerId = estimateId,
                            context = "ESTIMATE",
                        ),
                    )
                }
                repair.sourceRepairId?.let { sourceId ->
                    add(
                        MaintenanceMediaScope(
                            ownerType = "MAINTENANCE_REPAIR",
                            ownerId = sourceId,
                            context = "REPAIR",
                        ),
                    )
                }
            },
            warehouseId = warehouseId,
        )
        mutableState.update { current ->
            current.copy(
                maintenanceAssetLabels = current.maintenanceAssetLabels + (asset.id to asset.number),
                maintenanceEditor = MaintenanceEditorState(
                    mode = MaintenanceEditorMode.REPAIR,
                    entityId = repair.id,
                    expectedVersion = repair.version,
                    readOnly = repair.executionState !in PRE_START_REPAIR_STATES,
                    selectedAsset = asset,
                    dispatchDate = repair.dispatchDate,
                    sourceParty = repair.sourceParty.orEmpty(),
                    lines = content.lines,
                    photoUris = emptyList(),
                    readyMedia = readyReferences,
                    readyPhotoUris = readyPhotoUris,
                    priority = repair.priority,
                    movementToRepair = repair.movementToRepair,
                    logisticsPlanningMode = repair.logisticsPlanningMode,
                    logisticsScheduledDate = repair.logisticsScheduledDate,
                    step = 1,
                    stages = content.stages,
                    documentState = repair.executionState,
                    repairKind = repair.kind,
                    sourceRepairId = repair.sourceRepairId,
                    coverPhotoKey = repair.coverMediaId
                        ?.let(::maintenanceReadyPhotoKey)
                        ?: maintenanceInitialCoverPhotoKey(readyReferences),
                    reworkCandidates = reworkCandidates,
                ),
            )
        }
        onReady()
    }

    fun startReworkEditor(sourceRepairId: String, onReady: () -> Unit) =
        startReworkEditor(
            sourceRepairId = sourceRepairId,
            selectedSourceLineId = null,
            onReady = onReady,
        )

    fun startReworkEditorForLine(
        sourceRepairId: String,
        sourceLineId: String,
        onReady: () -> Unit,
    ) = startReworkEditor(
        sourceRepairId = sourceRepairId,
        selectedSourceLineId = sourceLineId,
        onReady = onReady,
    )

    private fun startReworkEditor(
        sourceRepairId: String,
        selectedSourceLineId: String?,
        onReady: () -> Unit,
    ) = command {
        val warehouseId = requireWarehouseId()
        ensureMaintenanceCatalog(warehouseId)
        refreshRepairTaskBoards()
        val source = backend.api.repair(sourceRepairId, warehouseId)
        if (source.executionState != "COMPLETED" || source.acceptanceState != "PENDING") {
            throw IllegalStateException("Ремонт больше не ожидает приёмки")
        }
        val asset = maintenanceRentalItem(source.rentalItemId, warehouseId)
        val candidates = backend.api.reworkCandidates(sourceRepairId, warehouseId).items
        if (candidates.isEmpty()) {
            throw IllegalStateException("В исходном ремонте нет плана для доработки")
        }
        val selectedCandidate = selectedSourceLineId?.let { sourceLineId ->
            candidates.singleOrNull { candidate ->
                candidate.sourceLineId == sourceLineId && candidate.line.lineType == "WORK"
            } ?: throw IllegalStateException(
                "Выбранная работа больше не доступна для доработки",
            )
        }
        val editor = MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = asset,
            dispatchDate = LocalDate.now().toString(),
            sourceParty = source.sourceParty.orEmpty(),
            lines = emptyList(),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = source.priority,
            step = 1,
            stages = emptyList(),
            repairKind = "REWORK",
            sourceRepairId = source.id,
            sourceRepairExpectedVersion = source.version,
            reworkCandidates = candidates,
        )
        assetSearchGeneration += 1
        mutableState.update { current ->
            current.copy(
                acceptanceEditor = null,
                acceptanceGallery = null,
                maintenanceAssetLabels = current.maintenanceAssetLabels + (asset.id to asset.number),
                maintenanceEditor = selectedCandidate?.let(editor::toggleReworkCandidate) ?: editor,
            )
        }
        onReady()
    }

    fun closeMaintenanceEditor() {
        assetSearchGeneration += 1
        mutableState.update {
            it.withClosedMaintenanceEditor().copy(maintenanceFurnitureEditor = null)
        }
        mutableState.value.selectedWarehouseId?.let { warehouseId ->
            viewModelScope.launch {
                restoreMaintenanceCatalogFromDisk(warehouseId)
            }
        }
    }

    /**
     * Loads the authoritative cabin contents and warehouse equipment catalogue for either
     * maintenance mode. The editor never derives a movement itself: it only prepares the
     * complete desired composition for the logistics command.
     */
    fun openMaintenanceFurniture(onReady: () -> Unit) = command {
        val maintenanceEditor = requireMaintenanceEditor()
        val selectedAsset = requireNotNull(maintenanceEditor.selectedAsset) {
            "Сначала выберите бытовку"
        }
        val warehouseId = requireWarehouseId()
        requireWarehouseAccess(warehouseId, "EDIT")
        require(selectedAsset.warehouseId == warehouseId) {
            "Выбранная бытовка относится к другому складу"
        }

        val (rentalItem, furnitureCatalog) = coroutineScope {
            val rentalItemRequest = async { backend.api.rentalItem(selectedAsset.id) }
            val catalogRequest = async {
                backend.api.equipment(warehouseId)
                    .map { it.equipment }
                    .maintenanceFurnitureCatalog()
            }
            rentalItemRequest.await() to catalogRequest.await()
        }
        require(rentalItem.warehouseId == warehouseId) {
            "Сервис вернул бытовку другого склада"
        }
        val currentEditor = mutableState.value.maintenanceEditor
        require(
            currentEditor?.mode == maintenanceEditor.mode &&
                currentEditor.selectedAsset?.id == selectedAsset.id,
        ) {
            "Смета или ремонт изменены. Откройте наполнение снова"
        }
        mutableState.update { current ->
            current.copy(
                maintenanceFurnitureEditor = MaintenanceFurnitureEditorState(
                    mode = maintenanceEditor.mode,
                    rentalItem = rentalItem,
                    furnitureCatalog = furnitureCatalog,
                    quantities = rentalItem.maintenanceFurnitureInitialQuantities(furnitureCatalog),
                ),
            )
        }
        onReady()
    }

    fun closeMaintenanceFurniture() {
        mutableState.update { it.copy(maintenanceFurnitureEditor = null) }
    }

    fun updateMaintenanceFurnitureQuantity(equipmentId: String, quantity: String) {
        mutableState.update { current ->
            val editor = current.maintenanceFurnitureEditor
            if (editor == null || editor.furnitureCatalog.none { it.id == equipmentId }) {
                current
            } else {
                current.copy(
                    maintenanceFurnitureEditor = editor.copy(
                        quantities = editor.quantities + (equipmentId to quantity),
                    ),
                )
            }
        }
    }

    fun completeMaintenanceFurniture(
        action: MaintenanceFurnitureCompletionAction,
        onCompleted: () -> Unit,
    ) = command {
        val furnitureEditor = requireNotNull(mutableState.value.maintenanceFurnitureEditor) {
            "Откройте наполнение бытовки"
        }
        val maintenanceEditor = requireMaintenanceEditor()
        require(
            maintenanceEditor.mode == furnitureEditor.mode &&
                maintenanceEditor.selectedAsset?.id == furnitureEditor.rentalItem.id,
        ) {
            "Смета или ремонт изменены. Откройте наполнение снова"
        }
        val warehouseId = requireWarehouseId()
        requireWarehouseAccess(warehouseId, "EDIT")
        require(furnitureEditor.rentalItem.warehouseId == warehouseId) {
            "Бытовка относится к другому складу"
        }
        val scheduledDate = furnitureEditor.scheduledDate.trim()
        maintenanceFurnitureScheduledDateValidationError(scheduledDate)?.let { error ->
            throw IllegalArgumentException(error)
        }
        val contents = when (action) {
            MaintenanceFurnitureCompletionAction.KEEP_IN_CABIN -> {
                furnitureEditor.maintenanceFurnitureCompositionValidationError()?.let { error ->
                    throw IllegalArgumentException(error)
                }
                furnitureEditor.maintenanceFurnitureDesiredContents()
            }

            MaintenanceFurnitureCompletionAction.MOVE_TO_STOCK -> emptyList()
        }
        val signature = buildString {
            append("maintenance-furniture:")
            append(furnitureEditor.mode.name)
            append(':')
            append(furnitureEditor.rentalItem.id)
            append(':')
            append(scheduledDate)
            append(':')
            append(action.name)
            contents.forEach { requirement ->
                append(':')
                append(requirement.equipmentId)
                append('=')
                append(requirement.quantity)
            }
        }
        val result = backend.api.createCabinFurnitureTask(
            rentalItemId = furnitureEditor.rentalItem.id,
            idempotencyKey = logisticsCommandKey(signature),
            request = CreateCabinFurnitureTaskRequest(
                warehouseId = warehouseId,
                scheduledDate = scheduledDate,
                contents = contents,
            ),
        )
        require(result.rentalItemId == furnitureEditor.rentalItem.id) {
            "Сервис логистики вернул задание другой бытовки"
        }
        commandKeys.complete(signature)
        mutableState.update { current ->
            val active = current.maintenanceFurnitureEditor
            if (active?.rentalItem?.id == furnitureEditor.rentalItem.id &&
                active.mode == furnitureEditor.mode
            ) {
                current.copy(maintenanceFurnitureEditor = null)
            } else {
                current
            }
        }
        message(
            if (result.taskId == null) {
                "Наполнение бытовки уже соответствует выбранному составу"
            } else {
                "Задание на изменение наполнения создано"
            },
        )
        onCompleted()
    }

    fun editMaintenance(update: (MaintenanceEditorState) -> MaintenanceEditorState) {
        mutableState.update { current ->
            val editor = current.maintenanceEditor
            if (editor == null) {
                current
            } else if (editor.readOnly) {
                // Started or closed records stay immutable, but the wizard remains navigable.
                current.copy(
                    maintenanceEditor = readOnlyMaintenanceEditorStepChange(editor, update(editor)),
                )
            } else {
                val edited = preserveMaintenanceImmutableFields(
                    original = editor,
                    candidate = update(editor).copy(
                        submitIdempotencyKey = null,
                        amendmentIdempotencyKey = null,
                    ),
                )
                current.copy(
                    maintenanceEditor = normalizeMaintenanceEditor(
                        normalizeReworkEditorLines(edited),
                    ),
                )
            }
        }
    }

    fun selectMaintenanceAsset(item: RentalItemDto) = command {
        val warehouseId = requireWarehouseId()
        val editor = requireMaintenanceEditor()
        if (item.warehouseId != warehouseId) {
            throw IllegalArgumentException("Выберите бытовку текущего склада")
        }
        if (editor.entityId != null) {
            throw IllegalStateException("Нельзя изменить бытовку уже сохранённого документа")
        }
        if (editor.readOnly) return@command
        if (editor.mode == MaintenanceEditorMode.ESTIMATE && item.status != "AFTER_RENT") {
            throw IllegalArgumentException("Для сметы доступна только бытовка после возврата")
        }
        if (editor.mode == MaintenanceEditorMode.REPAIR &&
            item.status in maintenanceExcludedRentalItemStatuses(MaintenanceEditorMode.REPAIR)
        ) {
            throw IllegalArgumentException("Эта бытовка недоступна для прямого ремонта")
        }

        // The picker is not allowed to rely on a stale browser-owned tenant value. For an
        // estimate, retrieve the current return documents again at the moment of selection.
        val returnMetadata = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
            latestMaintenanceReturnMetadata(backend.api.returns(warehouseId), item.id)
        } else {
            null
        }
        mutableState.update { current ->
            val currentEditor = current.maintenanceEditor
            if (currentEditor == null || currentEditor.entityId != null || currentEditor.readOnly) {
                current
            } else {
                val selectedEditor = when (currentEditor.mode) {
                    MaintenanceEditorMode.ESTIMATE -> currentEditor.copy(
                        selectedAsset = item,
                        dispatchDate = returnMetadata?.dispatchDate ?: currentEditor.dispatchDate,
                        sourceParty = returnMetadata?.sourceParty ?: currentEditor.sourceParty,
                        submitIdempotencyKey = null,
                    )

                    MaintenanceEditorMode.REPAIR -> currentEditor.copy(
                        selectedAsset = item,
                        dispatchDate = LocalDate.now().toString(),
                        sourceParty = DIRECT_REPAIR_SOURCE_PARTY,
                        submitIdempotencyKey = null,
                    )
                }.copy(step = 2)
                current.copy(
                    message = null,
                    maintenanceAssetLabels = current.maintenanceAssetLabels + (item.id to item.number),
                    maintenanceReturnMetadata = if (returnMetadata == null) {
                        current.maintenanceReturnMetadata - item.id
                    } else {
                        current.maintenanceReturnMetadata + (item.id to returnMetadata)
                    },
                    maintenanceEditor = normalizeMaintenanceEditor(selectedEditor),
                )
            }
        }
    }

    fun addMaintenanceCatalogNodes(
        nodes: List<CatalogNodeDto>,
        quantity: String,
        comment: String,
        existingWorkLineId: String? = null,
        photoUris: List<String> = emptyList(),
        mediaReferences: List<MediaReferenceDto> = emptyList(),
    ): Boolean {
        val activeEditor = mutableState.value.maintenanceEditor ?: return false
        val canonicalQuantity = runCatching {
            canonicalMaintenanceQuantity(quantity)
        }.getOrElse { failure ->
            message(failure.message ?: "Проверьте количество")
            return false
        }
        val canonicalComment = comment.trim()
        if (canonicalComment.length > 2_000) {
            message("Комментарий строки не может быть длиннее 2000 символов")
            return false
        }
        val editorMode = activeEditor.mode
        val canonicalNodes = nodes
            .mapNotNull { node -> maintenanceCatalogNodesById[node.id] }
            .filter(CatalogNodeDto::isOperationalEstimateNode)
            .filter { node ->
                node.isAvailableForMaintenanceMode(
                    mode = editorMode,
                    nodesById = maintenanceCatalogNodesById,
                )
            }
            .distinctBy(CatalogNodeDto::id)
        if (canonicalNodes.isEmpty()) {
            message("Выбранные позиции больше недоступны в активном каталоге")
            return false
        }
        if ((photoUris.isNotEmpty() || mediaReferences.isNotEmpty()) &&
            canonicalNodes.count { node -> node.nodeType == "WORK" } != 1
        ) {
            message("Фото можно прикрепить только к одной выбранной работе")
            return false
        }
        if (!activeEditor.hasSelectedCatalogWork(
                nodes = canonicalNodes,
                lineId = existingWorkLineId,
            )
        ) {
            message("Выбранная работа уже изменилась. Выберите её снова.")
            return false
        }

        editMaintenance { editor ->
            val updated = applyMaintenanceCatalogNodes(
                editor = editor,
                nodes = canonicalNodes,
                quantity = canonicalQuantity,
                comment = canonicalComment,
                existingWorkLineId = existingWorkLineId,
                photoUris = photoUris,
                mediaReferences = mediaReferences,
            )
            val movedMediaIds = mediaReferences.mapTo(mutableSetOf(), MediaReferenceDto::mediaId)
            if (movedMediaIds.isEmpty()) {
                updated
            } else {
                updated.copy(
                    readyMedia = updated.readyMedia.filterNot { reference ->
                        reference.mediaId in movedMediaIds
                    },
                    coverPhotoKey = updated.coverPhotoKey.takeUnless { key ->
                        key?.removePrefix("media:") in movedMediaIds
                    },
                )
            }
        }
        return true
    }

    fun toggleReworkCandidate(candidate: ReworkCandidateDto) {
        editMaintenance { editor ->
            editor.toggleReworkCandidate(candidate)
        }
    }

    fun addMaintenancePhoto(uri: String) {
        editMaintenance { editor ->
            if (uri in editor.photoUris) editor
            else editor.copy(photoUris = editor.photoUris + uri)
        }
    }

    fun removeMaintenancePhoto(uri: String) {
        editMaintenance { editor ->
            removeMaintenanceLocalPhoto(editor, uri)
        }
    }

    fun selectMaintenanceCoverPhoto(photoKey: String) {
        editMaintenance { editor ->
            val valid = editor.photoUris.any { uri ->
                maintenanceLocalPhotoKey(uri) == photoKey
            } || editor.readyMedia.any { media ->
                maintenanceReadyPhotoKey(media.mediaId) == photoKey
            }
            if (valid) editor.copy(coverPhotoKey = photoKey) else editor
        }
    }

    fun saveMaintenanceDraft(onSaved: () -> Unit) = command {
        val existingDocument = maintenanceDocumentAlreadySubmitted(requireMaintenanceEditor())
        val current = requireMaintenanceEditor()
        val queued = if (current.hasPendingMaintenancePhotos()) {
            enqueueMaintenanceBackground(submit = false)
        } else {
            null
        }
        val editor = queued ?: persistMaintenanceDraft()
        if (queued == null) {
            refreshMaintenance()
            if (editor.repairKind == "REWORK") refreshAcceptance()
        } else {
            closeMaintenanceEditor()
        }
        message(
            if (queued != null) {
                if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                    "Смета добавлена в фоновые загрузки"
                } else {
                    "Ремонт добавлен в фоновые загрузки"
                }
            } else if (existingDocument && editor.mode == MaintenanceEditorMode.ESTIMATE) {
                "Изменения сметы сохранены"
            } else if (existingDocument) {
                "Изменения ремонта сохранены"
            } else if (editor.repairKind == "REWORK") {
                "Черновик доработки сохранён"
            } else if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                "Черновик сметы сохранён"
            } else {
                "Черновик ремонта сохранён"
            },
        )
        onSaved()
    }

    fun submitMaintenance(onSaved: () -> Unit) = command {
        val beforeSave = requireMaintenanceEditor().normalizedLogisticsPlanning()
        mutableState.update { current ->
            current.copy(maintenanceEditor = beforeSave)
        }
        validateMaintenanceSubmit(beforeSave)
        if (maintenanceDocumentAlreadySubmitted(beforeSave)) {
            val saved = enqueueMaintenanceBackground(submit = false)
            closeMaintenanceEditor()
            message(
                if (saved.mode == MaintenanceEditorMode.ESTIMATE) {
                    "Изменения сметы добавлены в фоновые загрузки"
                } else {
                    "Изменения ремонта добавлены в фоновые загрузки"
                },
            )
            onSaved()
            return@command
        }
        val saved = enqueueMaintenanceBackground(submit = true)
        closeMaintenanceEditor()
        message(
            if (saved.mode == MaintenanceEditorMode.ESTIMATE) {
                "Смета добавлена в фоновые загрузки"
            } else if (saved.repairKind == "REWORK") {
                "Доработка добавлена в фоновые загрузки"
            } else {
                "Ремонт добавлен в фоновые загрузки"
            },
        )
        onSaved()
    }

    fun updateAssetSearch(value: String) {
        assetSearchGeneration += 1
        mutableState.update { state ->
            state.copy(
                assetSearch = value,
                assetSearchResults = if (value.isBlank()) emptyList() else state.assetSearchResults,
                assetSearchBusy = false,
                assetSearchCompletedQuery = null,
                assetSearchFailedQuery = null,
            )
        }
    }

    fun searchAssets() {
        val editor = mutableState.value.maintenanceEditor ?: return
        val query = mutableState.value.assetSearch.trim()
        val warehouseId = mutableState.value.selectedWarehouseId ?: return
        val requestGeneration = ++assetSearchGeneration
        mutableState.update {
            it.copy(
                assetSearchBusy = true,
                assetSearchCompletedQuery = null,
                assetSearchFailedQuery = null,
            )
        }
        viewModelScope.launch {
            try {
                val items = backend.api.rentalItems(
                    warehouseId = warehouseId,
                    size = 200,
                    search = query.takeIf(String::isNotEmpty),
                    excludeStatuses = maintenanceExcludedRentalItemStatuses(editor.mode),
                ).content
                val returnMetadata = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                    val documents = backend.api.returns(warehouseId)
                    items.mapNotNull { item ->
                        latestMaintenanceReturnMetadata(documents, item.id)?.let { metadata ->
                            item.id to metadata
                        }
                    }.toMap()
                } else {
                    emptyMap()
                }
                mutableState.update { current ->
                    if (requestGeneration != assetSearchGeneration ||
                        current.selectedWarehouseId != warehouseId ||
                        current.maintenanceEditor?.mode != editor.mode ||
                        current.assetSearch.trim() != query
                    ) {
                        current
                    } else {
                        current.copy(
                            assetSearchResults = items,
                            assetSearchCompletedQuery = query,
                            assetSearchFailedQuery = null,
                            maintenanceReturnMetadata =
                                if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                                    current.maintenanceReturnMetadata + returnMetadata
                                } else {
                                    current.maintenanceReturnMetadata
                                },
                        )
                    }
                }
            } catch (failure: Throwable) {
                if (requestGeneration == assetSearchGeneration) {
                    mutableState.update { current ->
                        if (current.selectedWarehouseId == warehouseId &&
                            current.maintenanceEditor?.mode == editor.mode &&
                            current.assetSearch.trim() == query
                        ) {
                            current.copy(assetSearchFailedQuery = query)
                        } else {
                            current
                        }
                    }
                    handleFailure(failure)
                }
            } finally {
                mutableState.update { current ->
                    if (requestGeneration == assetSearchGeneration) {
                        current.copy(assetSearchBusy = false)
                    } else {
                        current
                    }
                }
            }
        }
    }

    /**
     * Kept for the existing list screen while it migrates to the editor route.
     * It uses the same draft implementation as the explicit editor flow.
     */
    fun createEstimate(item: RentalItemDto) = command {
        val warehouseId = requireWarehouseId()
        if (item.warehouseId != warehouseId) {
            throw IllegalArgumentException("Выберите бытовку текущего склада")
        }
        mutableState.update { current ->
            current.copy(
                maintenanceEditor = newMaintenanceEditor(MaintenanceEditorMode.ESTIMATE).copy(
                    selectedAsset = item,
                ),
            )
        }
        persistMaintenanceDraft()
        refreshMaintenance()
        mutableState.update {
            it.copy(
                assetSearchResults = emptyList(),
                assetSearch = "",
                assetSearchCompletedQuery = null,
                assetSearchFailedQuery = null,
            )
        }
        message("Черновик сметы для ${item.number} создан")
    }

    private data class LoadedMaintenanceCatalog(
        val revision: ActiveMaintenanceCatalogRevision,
        val activeVersionEtag: String?,
        val allNodes: List<CatalogNodeDto>,
        val operationalNodes: List<CatalogNodeDto>,
        val links: List<CatalogLinkDto>,
    )

    private data class MaintenanceDraftContent(
        val asset: RentalItemDto,
        val dispatchDate: String,
        val sourceParty: String?,
        val lines: List<EstimateLineInputDto>,
        val stages: List<PlanStageInputDto>,
        val mediaReferences: List<MediaReferenceDto>,
        val coverMediaId: String?,
    )

    private data class PersistedMaintenanceEntity(
        val id: String,
        val version: Long,
        val mediaReferences: List<MediaReferenceDto>,
        val coverMediaId: String?,
        val linkedRepairVersion: Long? = null,
    )

    private data class RepairEditorContent(
        val lines: List<MaintenanceLineEditorState>,
        val stages: List<MaintenanceStageEditorState>,
    )

    private fun InventoryFindingDto?.inventoryPlanEditorContent(): RepairEditorContent {
        val plan = this?.frozenPlan ?: return RepairEditorContent(emptyList(), emptyList())
        val lines = plan.lines.map { line ->
            MaintenanceLineEditorState(
                id = line.id,
                catalogNodeId = line.catalogNodeId,
                description = line.description,
                lineType = line.lineType,
                unit = line.unit,
                quantity = line.quantity,
                unitPrice = BigDecimal(line.unitPriceMinor)
                    .movePointLeft(2)
                    .stripTrailingZeros()
                    .toPlainString(),
                normativeMinutes = if (line.lineType == "MATERIAL") {
                    0
                } else {
                    BigDecimal(line.normativeMinutes).toInt()
                },
                comment = line.groupComment.orEmpty().takeIf {
                    line.lineType == "WORK"
                }.orEmpty(),
                catalogSnapshot = line.catalogNodeId?.let(maintenanceCatalogNodesById::get)
                    ?.toCatalogSnapshot(),
                mediaReferences = line.mediaReferences.takeIf {
                    line.lineType == "WORK"
                }.orEmpty(),
            ).normalizedMaintenanceAnnotations()
        }
        val remainingLinesByCatalogNode = lines
            .filter { it.catalogNodeId != null }
            .groupBy { requireNotNull(it.catalogNodeId) }
            .mapValues { (_, matchingLines) -> matchingLines.toMutableList() }
        val stages = plan.stages
            .asSequence()
            .filter { stage -> stage.kind == "REPAIR_WORK" }
            .sortedBy { it.order }
            .map { stage ->
                val primary = remainingLinesByCatalogNode[stage.catalogNodeId]
                    ?.let { matchingLines ->
                        if (matchingLines.isEmpty()) null else matchingLines.removeAt(0)
                    }
                    ?: throw IllegalArgumentException(
                        "Сервис вернул этап инвентаризации без соответствующей строки каталога",
                    )
                MaintenanceStageEditorState(
                    id = stage.id,
                    kind = stage.kind,
                    routing = RoutingSnapshotDto(
                        queueId = stage.routingQueueId,
                        queueName = stage.routingQueueName,
                        queueType = stage.routingQueueType,
                    ),
                    includedLineIds = listOf(primary.id),
                    primaryLineId = primary.id.takeIf { primary.lineType == "WORK" },
                    groupComment = primary.comment,
                    originalOrder = stage.order,
                )
            }
            .toList()
        return RepairEditorContent(lines, stages)
    }

    private fun repairEditorContent(repair: RepairDto): RepairEditorContent {
        val stages = repair.plan.stages
            .asSequence()
            .filter { stage -> stage.kind == "REPAIR_WORK" }
            .sortedBy(RepairStageDto::order)
            .toList()
        val linesById = linkedMapOf<String, EstimateLineDto>()
        val routingByLineId = linkedMapOf<String, RoutingSnapshotDto>()
        stages.forEach { stage ->
            (stage.workLines + stage.materialLines).forEach { line ->
                linesById.putIfAbsent(line.id, line)
                routingByLineId.putIfAbsent(line.id, stage.routing)
            }
        }
        return RepairEditorContent(
            lines = linesById.values.map { line ->
                line.toMaintenanceLineEditor(customRouting = routingByLineId[line.id])
            },
            stages = stages.map(RepairStageDto::toMaintenanceStageEditor),
        )
    }

    private fun newMaintenanceEditor(mode: MaintenanceEditorMode): MaintenanceEditorState =
        MaintenanceEditorState(
            mode = mode,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = null,
            dispatchDate = LocalDate.now().toString(),
            sourceParty = if (mode == MaintenanceEditorMode.REPAIR) {
                DIRECT_REPAIR_SOURCE_PARTY
            } else {
                ""
            },
            lines = emptyList(),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = DEFAULT_MAINTENANCE_PRIORITY,
            step = 1,
        )

    private suspend fun refreshMaintenance(force: Boolean = false) {
        val warehouseId = requireWarehouseId()
        val scope = readCacheScope(warehouseId)
        ensureMaintenanceCatalog(warehouseId)
        maintenanceReadMutex.withLock {
            val cached = managerReadCache.readMaintenance(scope)
            val refreshed = try {
                coroutineScope {
                    val estimatesRequest = async {
                        backend.api.estimates(
                            warehouseId = warehouseId,
                            size = 200,
                            ifNoneMatch = if (force) null else cached?.estimatesEtag,
                        )
                    }
                    val repairsRequest = async {
                        backend.api.repairs(
                            warehouseId = warehouseId,
                            size = 200,
                            ifNoneMatch = if (force) null else cached?.repairsEtag,
                        )
                    }
                    val estimates = conditionalRead(
                        response = estimatesRequest.await(),
                        cachedValue = cached?.estimates,
                        cachedEtag = cached?.estimatesEtag,
                        missingCacheMessage = "Сервер подтвердил старую смету, которой нет на телефоне",
                    )
                    val repairs = conditionalRead(
                        response = repairsRequest.await(),
                        cachedValue = cached?.repairs,
                        cachedEtag = cached?.repairsEtag,
                        missingCacheMessage = "Сервер подтвердил старый ремонт, которого нет на телефоне",
                    )
                    estimates to repairs
                }
            } catch (failure: Throwable) {
                if (cached != null && canUseCachedReadAfter(failure)) {
                    applyMaintenanceRead(warehouseId, cached)
                    return@withLock
                }
                throw failure
            }
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock

            val estimates = refreshed.first.value.items
            val repairs = refreshed.second.value.items
            val retainedLabels = cached?.assetLabels.orEmpty()
            val labels = retainedLabels + resolveMaintenanceAssetLabels(
                (estimates.asSequence().map(EstimateDto::rentalItemId) +
                    repairs.asSequence().map(RepairDto::rentalItemId))
                    .distinct()
                    .filterNot(retainedLabels::containsKey)
                    .toList(),
            )
            val snapshot = CachedMaintenanceRead(
                estimates = refreshed.first.value,
                estimatesEtag = refreshed.first.etag,
                repairs = refreshed.second.value,
                repairsEtag = refreshed.second.etag,
                assetLabels = labels,
            )
            managerReadCache.writeMaintenance(scope, snapshot)
            applyMaintenanceRead(warehouseId, snapshot)
        }
    }

    private fun applyMaintenanceRead(
        warehouseId: String,
        snapshot: CachedMaintenanceRead,
    ) {
        mutableState.update { current ->
            if (current.selectedWarehouseId != warehouseId) {
                current
            } else {
                current.copy(
                    estimates = snapshot.estimates?.items.orEmpty(),
                    repairs = snapshot.repairs?.items.orEmpty(),
                    maintenanceAssetLabels = current.maintenanceAssetLabels + snapshot.assetLabels,
                )
            }
        }
    }

    private suspend fun refreshRepairTaskBoards(force: Boolean = false) {
        val warehouseId = requireWarehouseId()
        val scope = readCacheScope(warehouseId)
        repairQueueMutex.withLock {
            val cached = managerReadCache.readRepairQueue(scope)
            val refreshed = try {
                val root = conditionalRead(
                    response = backend.api.taskBoard(
                        warehouseId = warehouseId,
                        includeShadow = true,
                        ifNoneMatch = if (force) null else cached?.rootEtag,
                    ),
                    cachedValue = cached?.rootSnapshot,
                    cachedEtag = cached?.rootEtag,
                    missingCacheMessage = "Сервер подтвердил старую очередь, которой нет на телефоне",
                )
                val cachedBoardsByDate = cached?.snapshots
                    ?.mapNotNull { board -> board.selectedDate?.let { it to board } }
                    ?.toMap()
                    .orEmpty()
                val rootDate = root.value.selectedDate
                val remaining = root.value.availableDates.filterNot { date -> date == rootDate }
                val boards = coroutineScope {
                    remaining.map { date ->
                        async {
                            date to conditionalRead(
                                response = backend.api.taskBoard(
                                    warehouseId = warehouseId,
                                    includeShadow = true,
                                    date = date,
                                    ifNoneMatch = if (force) null else cached?.etagsByDate?.get(date),
                                ),
                                cachedValue = cachedBoardsByDate[date],
                                cachedEtag = cached?.etagsByDate?.get(date),
                                missingCacheMessage =
                                    "Сервер подтвердил старую колонку очереди, которой нет на телефоне",
                            )
                        }
                    }.map { request -> request.await() }
                }
                root to boards
            } catch (failure: Throwable) {
                if (cached != null && canUseCachedReadAfter(failure)) {
                    applyRepairQueueRead(warehouseId, cached)
                    return@withLock
                }
                throw failure
            }
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock

            val root = refreshed.first
            val boardsByDate = linkedMapOf<String, TaskBoardSnapshotDto>()
            root.value.selectedDate?.let { date -> boardsByDate[date] = root.value }
            val etagsByDate = refreshed.second.mapNotNull { (date, board) ->
                board.etag?.let { tag -> date to tag }
            }.toMap()
            refreshed.second.forEach { (_, board) ->
                board.value.selectedDate?.let { date -> boardsByDate[date] = board.value }
            }
            val snapshots = root.value.availableDates.mapNotNull(boardsByDate::get)
                .ifEmpty { listOf(root.value) }
            val snapshot = CachedRepairQueueRead(
                rootSnapshot = root.value,
                rootEtag = root.etag,
                snapshots = snapshots,
                etagsByDate = etagsByDate,
            )
            managerReadCache.writeRepairQueue(scope, snapshot)
            applyRepairQueueRead(warehouseId, snapshot)
        }
    }

    private fun applyRepairQueueRead(
        warehouseId: String,
        snapshot: CachedRepairQueueRead,
    ) {
        val ordered = snapshot.rootSnapshot.availableDates
            .mapNotNull { date -> snapshot.snapshots.firstOrNull { it.selectedDate == date } }
            .ifEmpty { snapshot.snapshots.ifEmpty { listOf(snapshot.rootSnapshot) } }
        mutableState.update { current ->
            if (current.selectedWarehouseId == warehouseId) {
                current.copy(repairTaskBoards = ordered)
            } else {
                current
            }
        }
    }

    private suspend fun refreshAcceptance() {
        val warehouseId = requireWarehouseId()
        val projections = backend.api.acceptance(
            warehouseId = warehouseId,
            size = 200,
        ).items
        val repairs = coroutineScope {
            projections
                .filter { projection ->
                    projection.executionState == "COMPLETED" &&
                        projection.acceptanceState == "PENDING"
                }
                .map { projection ->
                    async { backend.api.repair(projection.repairId, warehouseId) }
                }
                .map { request -> request.await() }
        }
        val labels = resolveMaintenanceAssetLabels(repairs.map(RepairDto::rentalItemId))
        if (mutableState.value.selectedWarehouseId != warehouseId) return
        mutableState.update { current ->
            val retainedEditor = current.acceptanceEditor?.takeIf { editor ->
                repairs.any { repair -> repair.id == editor.repair.id }
            }
            current.copy(
                acceptanceRepairs = repairs,
                maintenanceAssetLabels = current.maintenanceAssetLabels + labels,
                acceptanceEditor = retainedEditor,
                acceptanceGallery = current.acceptanceGallery.takeIf { retainedEditor != null },
            )
        }
    }

    private suspend fun ensureMaintenanceCatalog(warehouseId: String) {
        maintenanceCatalogMutex.withLock {
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock
            restoreMaintenanceCatalogFromDisk(warehouseId)
            val needsInitialCatalog = shouldReloadMaintenanceCatalog(
                requestedWarehouseId = warehouseId,
                cachedWarehouseId = maintenanceCatalogWarehouseId,
                cachedRevision = maintenanceCatalogRevision,
                cachedNodeCount = maintenanceCatalogNodesById.size,
            )
            val activeRevision = if (needsInitialCatalog) {
                fetchActiveMaintenanceCatalogRevision(
                    warehouseId = warehouseId,
                    ifNoneMatch = null,
                    cachedRevision = null,
                )
            } else {
                try {
                    fetchActiveMaintenanceCatalogRevision(
                        warehouseId = warehouseId,
                        ifNoneMatch = maintenanceCatalogActiveVersionEtag,
                        cachedRevision = maintenanceCatalogRevision,
                    )
                } catch (failure: Throwable) {
                    if (canUseCachedReadAfter(failure)) {
                        // A valid local snapshot remains usable while the device is offline.
                        return@withLock
                    }
                    throw failure
                }
            }
            if (!needsInitialCatalog && maintenanceCatalogRevision == activeRevision.value) {
                updateMaintenanceCatalogActiveVersionEtag(warehouseId, activeRevision.etag)
                return@withLock
            }

            val catalog = fetchMaintenanceCatalog(
                warehouseId = warehouseId,
                revision = activeRevision.value,
                activeVersionEtag = activeRevision.etag,
            )
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock
            persistMaintenanceCatalog(warehouseId, catalog)
            maintenanceCatalogCache.markAttemptSlot(
                maintenanceCatalogSyncSlot(ZonedDateTime.now()),
            )
            applyMaintenanceCatalogWhenSafe(warehouseId, catalog)
        }
        requestMaintenanceCatalogSyncIfDue()
    }

    private suspend fun restoreMaintenanceCatalogFromDisk(warehouseId: String): Boolean {
        if (mutableState.value.selectedWarehouseId != warehouseId) return false
        val cached = maintenanceCatalogCache.read() ?: return false
        if (mutableState.value.selectedWarehouseId != warehouseId) return false
        if (cached.warehouseId != warehouseId) return false
        val operationalNodes = cached.nodes.filter(CatalogNodeDto::isOperationalEstimateNode)
        if (operationalNodes.isEmpty()) {
            if (mutableState.value.selectedWarehouseId == warehouseId) {
                maintenanceCatalogCache.clear()
            }
            return false
        }

        val needsInitialCatalog = shouldReloadMaintenanceCatalog(
            requestedWarehouseId = warehouseId,
            cachedWarehouseId = maintenanceCatalogWarehouseId,
            cachedRevision = maintenanceCatalogRevision,
            cachedNodeCount = maintenanceCatalogNodesById.size,
        )
        val canReplaceCurrentCatalog = mutableState.value.maintenanceEditor == null
        if (needsInitialCatalog ||
            canReplaceCurrentCatalog && maintenanceCatalogRevision != cached.revision
        ) {
            applyMaintenanceCatalog(
                warehouseId = warehouseId,
                catalog = LoadedMaintenanceCatalog(
                    revision = cached.revision,
                    activeVersionEtag = cached.activeVersionEtag,
                    allNodes = cached.nodes,
                    operationalNodes = operationalNodes,
                    links = cached.links,
                ),
            )
        }
        return true
    }

    private suspend fun persistMaintenanceCatalog(
        warehouseId: String,
        catalog: LoadedMaintenanceCatalog,
    ) {
        maintenanceCatalogCache.write(
            CachedMaintenanceCatalog(
                warehouseId = warehouseId,
                revision = catalog.revision,
                activeVersionEtag = catalog.activeVersionEtag,
                nodes = catalog.allNodes,
                links = catalog.links,
            ),
        )
    }

    private fun applyMaintenanceCatalog(
        warehouseId: String,
        catalog: LoadedMaintenanceCatalog,
    ) {
        if (mutableState.value.selectedWarehouseId != warehouseId) return
        maintenanceCatalogWarehouseId = warehouseId
        maintenanceCatalogNodesById = catalog.allNodes.associateBy(CatalogNodeDto::id)
        maintenanceCatalogRevision = catalog.revision
        maintenanceCatalogActiveVersionEtag = catalog.activeVersionEtag
        mutableState.update {
            it.copy(
                maintenanceCatalogNodes = catalog.allNodes.filter(CatalogNodeDto::active),
                maintenanceCatalogLinks = catalog.links,
                maintenanceCatalogRefreshAvailable = false,
            )
        }
    }

    private fun applyMaintenanceCatalogWhenSafe(
        warehouseId: String,
        catalog: LoadedMaintenanceCatalog,
    ) {
        val editorIsOpen = mutableState.value.maintenanceEditor != null
        if (editorIsOpen &&
            maintenanceCatalogRevision != null &&
            maintenanceCatalogRevision != catalog.revision
        ) {
            mutableState.update {
                it.copy(maintenanceCatalogRefreshAvailable = true)
            }
        } else {
            applyMaintenanceCatalog(warehouseId, catalog)
        }
    }

    private fun requestMaintenanceCatalogSync() {
        val current = mutableState.value
        val warehouseId = current.selectedWarehouseId ?: return
        if (current.authState != ManagerAuthState.SignedIn) return
        if (maintenanceCatalogSyncJob?.isActive == true) return
        maintenanceCatalogSyncJob = viewModelScope.launch {
            syncMaintenanceCatalog(warehouseId, scheduled = false)
        }
    }

    private fun requestMaintenanceCatalogSyncIfDue() {
        val current = mutableState.value
        val warehouseId = current.selectedWarehouseId ?: return
        if (current.authState != ManagerAuthState.SignedIn) return
        if (maintenanceCatalogSyncJob?.isActive == true) return
        maintenanceCatalogSyncJob = viewModelScope.launch {
            if (!shouldAttemptMaintenanceCatalogSync(
                    lastAttemptSlot = maintenanceCatalogCache.lastAttemptSlot(),
                    now = ZonedDateTime.now(),
                )
            ) {
                return@launch
            }
            syncMaintenanceCatalog(warehouseId, scheduled = true)
        }
    }

    private suspend fun syncMaintenanceCatalog(
        warehouseId: String,
        scheduled: Boolean,
    ) {
        maintenanceCatalogMutex.withLock {
            val now = ZonedDateTime.now()
            if (scheduled && !shouldAttemptMaintenanceCatalogSync(
                    lastAttemptSlot = maintenanceCatalogCache.lastAttemptSlot(),
                    now = now,
                )
            ) {
                return@withLock
            }

            if (scheduled) {
                // Record the slot before the request so a failed scheduled refresh does not
                // hammer the gateway. Foreground checks remain available after that failure.
                maintenanceCatalogCache.markAttemptSlot(maintenanceCatalogSyncSlot(now))
            }
            val activeRevision = try {
                fetchActiveMaintenanceCatalogRevision(
                    warehouseId = warehouseId,
                    ifNoneMatch = maintenanceCatalogActiveVersionEtag,
                    cachedRevision = maintenanceCatalogRevision,
                )
            } catch (_: Throwable) {
                return@withLock
            }
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock
            if (maintenanceCatalogRevision == activeRevision.value) {
                updateMaintenanceCatalogActiveVersionEtag(warehouseId, activeRevision.etag)
                return@withLock
            }

            val catalog = try {
                fetchMaintenanceCatalog(
                    warehouseId = warehouseId,
                    revision = activeRevision.value,
                    activeVersionEtag = activeRevision.etag,
                )
            } catch (_: Throwable) {
                return@withLock
            }
            persistMaintenanceCatalog(warehouseId, catalog)
            applyMaintenanceCatalogWhenSafe(warehouseId, catalog)
        }
    }

    private fun startMaintenanceCatalogScheduler() {
        if (mutableState.value.authState != ManagerAuthState.SignedIn ||
            mutableState.value.selectedWarehouseId == null ||
            maintenanceCatalogSchedulerJob?.isActive == true
        ) {
            return
        }
        maintenanceCatalogSchedulerJob = viewModelScope.launch {
            while (isActive) {
                delay(millisUntilNextMaintenanceCatalogSync(ZonedDateTime.now()))
                requestMaintenanceCatalogSyncIfDue()
            }
        }
    }

    private suspend fun fetchActiveMaintenanceCatalogRevision(
        warehouseId: String,
        ifNoneMatch: String?,
        cachedRevision: ActiveMaintenanceCatalogRevision?,
    ): ConditionalRead<ActiveMaintenanceCatalogRevision> {
        val response = backend.api.catalogVersions(
            warehouseId = warehouseId,
            page = 0,
            size = 200,
            lifecycle = "ACTIVE",
            ifNoneMatch = ifNoneMatch,
        )
        if (response.code() == 304) {
            return ConditionalRead(
                value = requireNotNull(cachedRevision) {
                    "Сервер подтвердил старый каталог, которого нет на телефоне"
                },
                etag = response.headers()["ETag"] ?: ifNoneMatch,
            )
        }
        if (!response.isSuccessful) throw HttpException(response)
        val active = requireNotNull(response.body())
            .items
            .firstOrNull { it.lifecycle == "ACTIVE" }
            ?: throw IllegalStateException(
                "Активный каталог смет и ремонтов ещё не опубликован",
            )
        return ConditionalRead(
            value = ActiveMaintenanceCatalogRevision(active.id, active.version),
            etag = response.headers()["ETag"],
        )
    }

    private suspend fun fetchMaintenanceCatalog(
        warehouseId: String,
    ): LoadedMaintenanceCatalog {
        val active = fetchActiveMaintenanceCatalogRevision(
            warehouseId = warehouseId,
            ifNoneMatch = null,
            cachedRevision = null,
        )
        return fetchMaintenanceCatalog(
            warehouseId = warehouseId,
            revision = active.value,
            activeVersionEtag = active.etag,
        )
    }

    private suspend fun fetchMaintenanceCatalog(
        warehouseId: String,
        revision: ActiveMaintenanceCatalogRevision,
        activeVersionEtag: String?,
    ): LoadedMaintenanceCatalog {
        val nodes = backend.api.catalogNodes(revision.id, warehouseId)
        val links = backend.api.catalogLinks(revision.id, warehouseId)
        val operationalNodes = nodes.filter(CatalogNodeDto::isOperationalEstimateNode)
        if (operationalNodes.isEmpty()) {
            throw IllegalStateException(
                "В активном каталоге нет доступных работ и материалов",
            )
        }
        return LoadedMaintenanceCatalog(
            revision = revision,
            activeVersionEtag = activeVersionEtag,
            allNodes = nodes,
            operationalNodes = operationalNodes,
            links = links,
        )
    }

    private suspend fun updateMaintenanceCatalogActiveVersionEtag(
        warehouseId: String,
        etag: String?,
    ) {
        val retained = etag ?: maintenanceCatalogActiveVersionEtag
        if (retained == maintenanceCatalogActiveVersionEtag) return
        maintenanceCatalogActiveVersionEtag = retained
        val cached = maintenanceCatalogCache.read()
        if (cached?.warehouseId == warehouseId && cached.revision == maintenanceCatalogRevision) {
            maintenanceCatalogCache.write(cached.copy(activeVersionEtag = retained))
        }
    }

    private suspend fun resolveMaintenanceAssetLabels(ids: List<String>): Map<String, String> =
        ids.distinct().associateWith { itemId ->
            backend.api.rentalItem(itemId).number.also { number ->
                require(number.isNotBlank()) { "Сервис не вернул номер бытовки" }
            }
        }

    private suspend fun maintenanceRentalItem(
        rentalItemId: String,
        warehouseId: String,
    ): RentalItemDto {
        val item = backend.api.rentalItem(rentalItemId)
        if (item.warehouseId != warehouseId) {
            throw IllegalStateException("Бытовка не относится к выбранному складу")
        }
        return item
    }

    private suspend fun enqueueMaintenanceBackground(
        submit: Boolean,
    ): MaintenanceEditorState {
        var editor = requireMaintenanceEditor().normalizedLogisticsPlanning()
        mutableState.update { current -> current.copy(maintenanceEditor = editor) }
        if (editor.readOnly) {
            throw IllegalStateException("Документ нельзя изменить после начала работы")
        }
        var content = maintenanceDraftContent(editor)
        val wasNew = editor.entityId == null
        if (wasNew) {
            val created = createMaintenanceEntity(editor, content)
            updateMaintenanceEditorAfterSave(created)
            editor = requireMaintenanceEditor()
            content = maintenanceDraftContent(editor)
        }

        val entityId = requireNotNull(editor.entityId) { "Не удалось сохранить черновик" }
        val expectedVersion = requireNotNull(editor.expectedVersion) {
            "Сервис не вернул версию черновика"
        }
        val warehouseId = requireWarehouseId()
        val localPhotoUris = orderedMaintenanceLocalPhotoUris(editor)
        val linePhotoUris = editor.lines
            .asSequence()
            .filter { line -> line.lineType == "WORK" }
            .flatMap { line ->
                line.photoUris.distinct().map { uri -> line.id to uri }
            }
            .toList()
        val owner = MediaOwner(
            ownerType = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                "MAINTENANCE_ESTIMATE"
            } else {
                "MAINTENANCE_REPAIR"
            },
            ownerId = entityId,
            warehouseId = warehouseId,
            context = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                "ESTIMATE"
            } else {
                "REPAIR"
            },
        )
        val existingMedia = orderedMaintenanceReadyMedia(editor)
        val existingCoverMediaId = editor.coverPhotoKey
            ?.takeIf { it.startsWith("media:") }
            ?.removePrefix("media:")
            ?.takeIf { mediaId -> existingMedia.any { it.mediaId == mediaId } }
        val submittedDocument = maintenanceDocumentAlreadySubmitted(editor)
        val replaceKind = when {
            wasNew && localPhotoUris.isEmpty() && linePhotoUris.isEmpty() ->
                MaintenanceReplaceKind.NONE
            editor.mode == MaintenanceEditorMode.ESTIMATE && submittedDocument ->
                MaintenanceReplaceKind.ESTIMATE_AMENDMENT
            editor.mode == MaintenanceEditorMode.ESTIMATE -> MaintenanceReplaceKind.ESTIMATE
            else -> MaintenanceReplaceKind.REPAIR
        }
        val submitRequest = if (submit) {
            PriorityVersionRequest(
                expectedVersion = expectedVersion,
                priority = editor.priority,
                movementToRepair = editor.movementToRepair,
                logisticsPlanningMode = editor.logisticsPlanningMode,
                logisticsScheduledDate = editor.logisticsScheduledDate,
            )
        } else {
            null
        }
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.MAINTENANCE,
                title = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                    "Смета ${content.asset.number}"
                } else {
                    "Ремонт ${content.asset.number}"
                },
                subtitle = if (submit) "Отправка в очередь" else "Сохранение",
                photos = localPhotoUris.mapIndexed { index, uri ->
                    PendingBackgroundPhoto(
                        uri = uri,
                        owner = owner,
                        sortOrder = existingMedia.size + index,
                        cover = editor.coverPhotoKey == maintenanceLocalPhotoKey(uri),
                    )
                } + linePhotoUris.mapIndexed { index, (lineId, uri) ->
                    PendingBackgroundPhoto(
                        uri = uri,
                        owner = owner,
                        sortOrder = existingMedia.size + localPhotoUris.size + index,
                        lineId = lineId,
                    )
                },
                maintenance = MaintenanceUploadCommand(
                    mode = editor.mode.name,
                    entityId = entityId,
                    warehouseId = warehouseId,
                    expectedVersion = expectedVersion,
                    dispatchDate = content.dispatchDate,
                    sourceParty = content.sourceParty,
                    lines = content.lines,
                    stages = content.stages,
                    existingMedia = existingMedia,
                    existingCoverMediaId = existingCoverMediaId,
                    replaceKind = replaceKind,
                    expectedLinkedRepairVersion = editor.linkedRepairExpectedVersion,
                    amendmentIdempotencyKey = if (
                        replaceKind == MaintenanceReplaceKind.ESTIMATE_AMENDMENT
                    ) {
                        editor.amendmentIdempotencyKey ?: UUID.randomUUID().toString()
                    } else {
                        null
                    },
                    amendmentReason = if (
                        replaceKind == MaintenanceReplaceKind.ESTIMATE_AMENDMENT
                    ) {
                        MAINTENANCE_AMENDMENT_REASON
                    } else {
                        null
                    },
                    submitRequest = submitRequest,
                    submitIdempotencyKey = if (submit) {
                        editor.submitIdempotencyKey ?: UUID.randomUUID().toString()
                    } else {
                        null
                    },
                ),
            ),
        )
        return editor
    }

    private suspend fun persistMaintenanceDraft(): MaintenanceEditorState {
        var editor = requireMaintenanceEditor().normalizedLogisticsPlanning()
        mutableState.update { current ->
            current.copy(maintenanceEditor = editor)
        }
        if (editor.readOnly) {
            throw IllegalStateException("Документ нельзя изменить после начала работы")
        }
        var content = maintenanceDraftContent(editor)
        val wasNew = editor.entityId == null
        if (wasNew) {
            val created = createMaintenanceEntity(editor, content)
            updateMaintenanceEditorAfterSave(created)
            editor = requireMaintenanceEditor()
        }

        require(!editor.hasPendingMaintenancePhotos()) {
            "Фотографии должны отправляться через фоновые загрузки"
        }
        if (!wasNew) {
            val replaced = replaceMaintenanceEntity(editor, content)
            updateMaintenanceEditorAfterSave(replaced)
            editor = requireMaintenanceEditor()
        }
        return editor
    }

    private fun maintenanceDraftContent(
        editor: MaintenanceEditorState,
        mediaReferences: List<MediaReferenceDto> = orderedMaintenanceReadyMedia(editor),
    ): MaintenanceDraftContent {
        val asset = requireNotNull(editor.selectedAsset) { "Выберите бытовку" }
        if (maintenanceRequiresPhotos(editor) && !maintenanceHasPhotos(editor)) {
            throw IllegalArgumentException("Добавьте хотя бы одну фотографию")
        }
        if (maintenanceHasPhotos(editor) && !maintenanceHasCoverPhoto(editor)) {
            throw IllegalArgumentException("Выберите титульную фотографию")
        }
        val warehouseId = requireWarehouseId()
        if (asset.warehouseId != warehouseId) {
            throw IllegalArgumentException("Выберите бытовку текущего склада")
        }
        val dispatchDate = if (editor.mode == MaintenanceEditorMode.REPAIR &&
            editor.entityId == null
        ) {
            LocalDate.now().toString()
        } else {
            editor.dispatchDate.trim().let { date ->
            runCatching { LocalDate.parse(date) }.getOrElse {
                throw IllegalArgumentException("Укажите корректную дату отправки")
            }
            date
            }
        }
        val sourceParty = if (editor.mode == MaintenanceEditorMode.REPAIR &&
            editor.entityId == null &&
            editor.repairKind != "REWORK"
        ) {
            DIRECT_REPAIR_SOURCE_PARTY
        } else {
            editor.sourceParty.trim().takeIf(String::isNotEmpty)
        }
        if (sourceParty != null && sourceParty.length > 512) {
            throw IllegalArgumentException("Источник не может быть длиннее 512 символов")
        }
        if (editor.repairKind == "REWORK" && editor.entityId == null) {
            val reason = editor.reworkReason.trim()
            if (reason.isEmpty()) {
                throw IllegalArgumentException("Укажите причину доработки")
            }
            if (reason.length > 2_000) {
                throw IllegalArgumentException("Причина доработки не может быть длиннее 2000 символов")
            }
        }
        val lines = editor.lines.map(::maintenanceLineInput)
        val stages = buildMaintenanceStages(editor)
        if (editor.mode == MaintenanceEditorMode.REPAIR &&
            editor.lines.isNotEmpty() &&
            stages.none { it.kind == "REPAIR_WORK" }
        ) {
            throw IllegalArgumentException("Для прямого ремонта добавьте хотя бы один этап")
        }
        return MaintenanceDraftContent(
            asset = asset,
            dispatchDate = dispatchDate,
            sourceParty = sourceParty,
            lines = lines,
            stages = stages,
            mediaReferences = mediaReferences.distinctBy(MediaReferenceDto::mediaId),
            coverMediaId = editor.coverPhotoKey
                ?.takeIf { it.startsWith("media:") }
                ?.removePrefix("media:")
                ?.takeIf { mediaId ->
                    mediaReferences.any { it.mediaId == mediaId }
                },
        )
    }

    private fun validateMaintenanceSubmit(editor: MaintenanceEditorState) {
        if (editor.readOnly) {
            throw IllegalStateException("Завершённые данные нельзя отправить повторно")
        }
        if (maintenanceRequiresPhotos(editor) && !maintenanceHasPhotos(editor)) {
            throw IllegalArgumentException("Добавьте хотя бы одну фотографию")
        }
        if (maintenanceHasPhotos(editor) && !maintenanceHasCoverPhoto(editor)) {
            throw IllegalArgumentException("Выберите титульную фотографию")
        }
        editor.logisticsTaskPriorityValidationError()?.let { error ->
            throw IllegalArgumentException(error)
        }
        editor.logisticsPlanningValidationError()?.let { error ->
            throw IllegalArgumentException(error)
        }
        val content = maintenanceDraftContent(editor)
        if (editor.lines.isNotEmpty() &&
            content.stages.none { it.kind == "REPAIR_WORK" }
        ) {
            throw IllegalArgumentException("Для отправки требуется маршрут работ")
        }
    }

    private fun maintenanceLineInput(line: MaintenanceLineEditorState): EstimateLineInputDto {
        val description = line.description.trim()
        if (description.isEmpty()) {
            throw IllegalArgumentException("Укажите описание каждой строки")
        }
        if (description.length > 1000) {
            throw IllegalArgumentException("Описание строки не может быть длиннее 1000 символов")
        }
        if (!isMaintenanceLineType(line.lineType)) {
            throw IllegalArgumentException("Тип строки должен быть работой или материалом")
        }
        val requestedComment = line.comment.trim().takeIf(String::isNotEmpty)
        if (requestedComment != null && requestedComment.length > 2000) {
            throw IllegalArgumentException("Комментарий строки не может быть длиннее 2000 символов")
        }
        val node = line.catalogNodeId?.let(maintenanceCatalogNodesById::get)
        val lineType = if (node == null) {
            line.lineType
        } else {
            if (!node.isOperationalEstimateNode()) {
                throw IllegalArgumentException("Позиция каталога для строки больше недоступна")
            }
            catalogMaintenanceLineType(node.nodeType).also { canonicalType ->
                if (line.lineType != canonicalType) {
                    throw IllegalArgumentException("Тип строки не совпадает с позицией каталога")
                }
            }
        }
        val comment = requestedComment.takeIf { lineType == "WORK" }
        val normativeMinutes = when (lineType) {
            "MATERIAL" -> 0
            "WORK" -> line.normativeMinutes.also { minutes ->
                val validRange = if (node == null) 1..525_600 else 0..525_600
                if (minutes !in validRange) {
                    throw IllegalArgumentException("Укажите норматив от ${validRange.first} до 525600 минут")
                }
            }
            else -> error("Validated above")
        }
        val unit = if (node == null) {
            line.unit.trim().takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("Укажите единицу измерения пользовательской строки")
        } else {
            node.unit?.trim()?.takeIf(String::isNotEmpty)
        }
        if (unit != null && unit.length > 32) {
            throw IllegalArgumentException("Единица измерения не может быть длиннее 32 символов")
        }
        return EstimateLineInputDto(
            id = line.id,
            catalogSnapshot = node?.toCatalogSnapshot(),
            lineType = lineType,
            description = description,
            unit = unit,
            quantity = canonicalMaintenanceQuantity(line.quantity),
            unitPrice = canonicalMaintenanceMoney(line.unitPrice),
            normativeMinutes = normativeMinutes,
            comment = comment,
            mediaReferences = if (lineType == "WORK") {
                line.mediaReferences.distinctBy(MediaReferenceDto::mediaId)
            } else {
                emptyList()
            },
        )
    }

    private fun inventoryPlanSelection(
        editor: InventoryEditorState,
        coverMediaId: String?,
    ): InventoryPlanSelectionDto? {
        if (editor.planLines.isEmpty()) {
            require(!editor.planMovementToRepair) {
                "Передача в ремонт доступна, когда в проверке есть работы или материалы"
            }
            return null
        }
        val maintenanceEditor = editor.toMaintenancePlanEditor()
            .copy(
                logisticsPlanningMode = if (editor.planMovementToRepair) {
                    LOGISTICS_PLANNING_MODE_AUTO
                } else {
                    null
                },
                logisticsScheduledDate = null,
            )
            .normalizedLogisticsPlanning()
        maintenanceEditor.logisticsTaskPriorityValidationError()?.let { error ->
            throw IllegalArgumentException(error)
        }
        maintenanceEditor.logisticsPlanningValidationError()?.let { error ->
            throw IllegalArgumentException(error)
        }
        val normalizedLines = maintenanceEditor.lines.associateBy(
            MaintenanceLineEditorState::id,
        )
        val normalizedLineInputs = maintenanceEditor.lines.associate { line ->
            line.id to maintenanceLineInput(line)
        }
        val routingCatalogNodeIdByManualLineId = inventoryManualLineRoutingCatalogNodeIds(
            catalogNodes = maintenanceCatalogNodesById.values,
            selectedRoutingByLineId = maintenanceEditor.lines
                .asSequence()
                .filter { line ->
                    normalizedLineInputs.getValue(line.id).catalogSnapshot == null
                }
                .associate { line -> line.id to line.maintenanceRouting() },
        )
        val stageCommentByLine = maintenanceEditor.stages
            .filter { it.kind == "REPAIR_WORK" }
            .flatMap { stage ->
                stage.includedLineIds.map { lineId -> lineId to stage.groupComment }
            }
            .toMap()
        val lines = maintenanceEditor.lines.map { line ->
            val normalized = normalizedLineInputs.getValue(line.id)
            if (normalized.catalogSnapshot != null) {
                InventoryPlanLineInputDto(
                    aggregationKind = "CATALOG",
                    catalogNodeId = normalized.catalogSnapshot.nodeId,
                    routingCatalogNodeId = null,
                    description = null,
                    type = null,
                    unit = null,
                    quantity = normalized.quantity,
                    unitPriceMinor = null,
                    normativeMinutes = null,
                    groupComment = if (normalized.lineType == "WORK") {
                        stageCommentByLine[line.id]
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: normalized.comment
                    } else {
                        null
                    },
                    mediaReferences = normalized.mediaReferences,
                )
            } else {
                InventoryPlanLineInputDto(
                    aggregationKind = "MANUAL",
                    catalogNodeId = null,
                    routingCatalogNodeId = routingCatalogNodeIdByManualLineId.getValue(line.id),
                    description = normalized.description,
                    type = normalized.lineType,
                    unit = normalized.unit,
                    quantity = normalized.quantity,
                    unitPriceMinor = BigDecimal(normalized.unitPrice)
                        .movePointRight(2)
                        .setScale(0, RoundingMode.UNNECESSARY)
                        .longValueExact(),
                    normativeMinutes = (normalized.normativeMinutes ?: 0).toString(),
                    groupComment = if (normalized.lineType == "WORK") {
                        stageCommentByLine[line.id]
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: normalized.comment
                    } else {
                        null
                    },
                    mediaReferences = normalized.mediaReferences,
                )
            }
        }
        val plannedStages = buildMaintenanceStages(maintenanceEditor)
        val inventoryStages = plannedStages.map { stage ->
            val catalogNodeId = when (stage.kind) {
                "REPAIR_WORK" -> stage.primaryLineId
                    ?.let(normalizedLines::get)
                    ?.catalogNodeId
                    ?: stage.includedLineIds
                        .asSequence()
                        .mapNotNull(normalizedLines::get)
                        .mapNotNull(MaintenanceLineEditorState::catalogNodeId)
                        .firstOrNull()
                    ?: inventoryStageCatalogNodeId(
                        catalogNodes = maintenanceCatalogNodesById.values,
                        selectedRouting = stage.routing,
                    )
                    ?: throw IllegalArgumentException(
                        "Для пользовательской строки не найден маршрут каталога",
                    )

                else -> throw IllegalArgumentException("Неподдерживаемый этап инвентаризации")
            }
            InventoryPlanStageSelectionDto(
                catalogNodeId = catalogNodeId,
                kind = stage.kind,
                order = stage.order,
            )
        }
        return InventoryPlanSelectionDto(
            mode = "MANUAL",
            priority = maintenanceEditor.priority,
            coverMediaId = coverMediaId,
            movementToRepair = maintenanceEditor.movementToRepair,
            logisticsPlanningMode = maintenanceEditor.logisticsPlanningMode,
            logisticsScheduledDate = maintenanceEditor.logisticsScheduledDate,
            lines = lines,
            stages = inventoryStages,
        )
    }

    private fun buildMaintenanceStages(editor: MaintenanceEditorState): List<PlanStageInputDto> =
        planMaintenanceStages(editor) { line -> line.maintenanceRouting() }

    private fun MaintenanceLineEditorState.maintenanceRouting(): RoutingSnapshotDto? =
        if (catalogNodeId == null) customRouting else effectiveMaintenanceRouting(catalogNodeId)

    private fun effectiveMaintenanceRouting(nodeId: String?): RoutingSnapshotDto? {
        val nodesById = maintenanceCatalogNodesById.filterValues(CatalogNodeDto::active)
        var current = nodeId?.let(nodesById::get)
        val incomingParents = maintenanceCatalogIncomingParents(nodesById)
        val visited = mutableSetOf<String>()
        while (current != null) {
            if (!visited.add(current.id)) return null
            current.routing?.takeIf(RoutingSnapshotDto::isValidMaintenanceRouting)?.let { return it }
            if (current.parentNodeId != null) {
                current = nodesById[current.parentNodeId]
            } else {
                current = incomingParents[current.id]?.firstOrNull { it.id !in visited }
            }
        }
        return null
    }

    private fun maintenanceCatalogIncomingParents(
        activeNodesById: Map<String, CatalogNodeDto>,
    ): Map<String, List<CatalogNodeDto>> {
        val incomingLinks = mutableState.value.maintenanceCatalogLinks
            .filter { link ->
                link.fromNodeId in activeNodesById && link.toNodeId in activeNodesById
            }
            .groupBy(CatalogLinkDto::toNodeId)
        return incomingLinks.mapValues { (_, links) ->
            links.sortedWith(
                compareBy<CatalogLinkDto> { it.sortOrder }
                    .thenBy { activeNodesById[it.fromNodeId]?.name.orEmpty() }
                    .thenBy(CatalogLinkDto::id),
            ).map { link -> requireNotNull(activeNodesById[link.fromNodeId]) }
        }
    }

    private suspend fun createMaintenanceEntity(
        editor: MaintenanceEditorState,
        content: MaintenanceDraftContent,
    ): PersistedMaintenanceEntity = when (editor.mode) {
        MaintenanceEditorMode.ESTIMATE -> backend.api.createEstimate(
            idempotencyKey = editor.createIdempotencyKey,
            request = CreateEstimateRequest(
                warehouseId = requireWarehouseId(),
                rentalItemId = content.asset.id,
                dispatchDate = content.dispatchDate,
                sourceParty = content.sourceParty,
                lines = content.lines,
                plan = content.stages,
                mediaReferences = content.mediaReferences,
                coverMediaId = content.coverMediaId,
            ),
        ).toPersistedMaintenanceEntity()

        MaintenanceEditorMode.REPAIR -> {
            if (editor.repairKind == "REWORK") {
                val sourceRepairId = requireNotNull(editor.sourceRepairId) {
                    "Не указан исходный ремонт"
                }
                val sourceVersion = requireNotNull(editor.sourceRepairExpectedVersion) {
                    "Не указана версия исходного ремонта"
                }
                backend.api.createRework(
                    repairId = sourceRepairId,
                    warehouseId = requireWarehouseId(),
                    idempotencyKey = editor.createIdempotencyKey,
                    request = CreateReworkRequest(
                        expectedVersion = sourceVersion,
                        reason = editor.reworkReason.trim(),
                        lines = reworkLineInputs(editor, content.lines),
                        plan = content.stages,
                        mediaReferences = emptyList(),
                        coverMediaId = null,
                    ),
                ).toPersistedMaintenanceEntity()
            } else {
                backend.api.createDirectRepair(
                    idempotencyKey = editor.createIdempotencyKey,
                    request = CreateDirectRepairRequest(
                        warehouseId = requireWarehouseId(),
                        rentalItemId = content.asset.id,
                        dispatchDate = content.dispatchDate,
                        sourceParty = content.sourceParty,
                        lines = content.lines,
                        plan = content.stages,
                        mediaReferences = content.mediaReferences,
                        coverMediaId = content.coverMediaId,
                    ),
                ).toPersistedMaintenanceEntity()
            }
        }
    }

    private fun reworkLineInputs(
        editor: MaintenanceEditorState,
        canonicalLines: List<EstimateLineInputDto>,
    ): List<ReworkLineInputDto> {
        val canonicalById = canonicalLines.associateBy(EstimateLineInputDto::id)
        return editor.lines.map { line ->
            when (line.reworkDisposition) {
                "REPEAT" -> ReworkLineInputDto(
                    id = line.id,
                    disposition = "REPEAT",
                    sourceRepairId = requireNotNull(line.sourceRepairId) {
                        "Для повторной строки не указан исходный ремонт"
                    },
                    sourceLineId = requireNotNull(line.sourceLineId) {
                        "Для повторной строки не указана исходная позиция"
                    },
                    quantity = canonicalMaintenanceQuantity(line.quantity),
                    comment = line.comment.trim().takeIf {
                        line.lineType == "WORK" && it.isNotEmpty()
                    },
                )

                "ADDED" -> ReworkLineInputDto(
                    id = line.id,
                    disposition = "ADDED",
                    line = requireNotNull(canonicalById[line.id]) {
                        "Новая строка доработки не прошла проверку"
                    },
                )

                else -> throw IllegalArgumentException(
                    "Выберите исходные позиции для переделки или добавьте новые",
                )
            }
        }
    }

    private suspend fun replaceMaintenanceEntity(
        editor: MaintenanceEditorState,
        content: MaintenanceDraftContent,
    ): PersistedMaintenanceEntity {
        val entityId = requireNotNull(editor.entityId) { "Сначала сохраните черновик" }
        val expectedVersion = requireNotNull(editor.expectedVersion) {
            "Сервис не вернул версию черновика"
        }
        return when (editor.mode) {
            MaintenanceEditorMode.ESTIMATE -> {
                if (maintenanceDocumentAlreadySubmitted(editor)) {
                    val expectedLinkedRepairVersion =
                        requireNotNull(editor.linkedRepairExpectedVersion) {
                            "Сервис не вернул версию связанного ремонта"
                        }
                    backend.api.amendEstimate(
                        estimateId = entityId,
                        warehouseId = requireWarehouseId(),
                        idempotencyKey = maintenanceAmendmentIdempotencyKey(editor),
                        request = AmendEstimateRequest(
                            expectedVersion = expectedVersion,
                            expectedLinkedRepairVersion = expectedLinkedRepairVersion,
                            dispatchDate = content.dispatchDate,
                            reason = MAINTENANCE_AMENDMENT_REASON,
                            sourceParty = content.sourceParty,
                            lines = content.lines,
                            plan = content.stages,
                            mediaReferences = content.mediaReferences,
                            coverMediaId = content.coverMediaId,
                        ),
                    ).toPersistedMaintenanceEntity()
                } else {
                    backend.api.replaceEstimate(
                        estimateId = entityId,
                        warehouseId = requireWarehouseId(),
                        request = ReplaceEstimateRequest(
                            expectedVersion = expectedVersion,
                            dispatchDate = content.dispatchDate,
                            sourceParty = content.sourceParty,
                            lines = content.lines,
                            plan = content.stages,
                            mediaReferences = content.mediaReferences,
                            coverMediaId = content.coverMediaId,
                        ),
                    ).toPersistedMaintenanceEntity()
                }
            }

            MaintenanceEditorMode.REPAIR -> backend.api.replaceRepairPlan(
                repairId = entityId,
                warehouseId = requireWarehouseId(),
                request = ReplaceRepairPlanRequest(
                    expectedVersion = expectedVersion,
                    lines = content.lines,
                    stages = content.stages,
                    mediaReferences = content.mediaReferences,
                    coverMediaId = content.coverMediaId,
                ),
            ).toPersistedMaintenanceEntity()
        }
    }

    private fun maintenanceAmendmentIdempotencyKey(editor: MaintenanceEditorState): String {
        editor.amendmentIdempotencyKey?.let { return it }
        val generated = UUID.randomUUID().toString()
        mutableState.update { current ->
            val currentEditor = current.maintenanceEditor
            if (currentEditor != null && currentEditor.entityId == editor.entityId) {
                current.copy(
                    maintenanceEditor = currentEditor.copy(
                        amendmentIdempotencyKey = generated,
                    ),
                )
            } else {
                current
            }
        }
        return generated
    }

    private fun updateMaintenanceEditorAfterSave(
        persisted: PersistedMaintenanceEntity,
        uploadedLocalUris: List<String> = emptyList(),
        uploadedReferences: List<MediaReferenceDto> = emptyList(),
    ) {
        val uploadedSet = uploadedLocalUris.toSet()
        mutableState.update { current ->
            val editor = current.maintenanceEditor
            if (editor == null || (editor.entityId != null && editor.entityId != persisted.id)) {
                current
            } else {
                val uploadedPreviewUris = uploadedReferences
                    .zip(uploadedLocalUris)
                    .associate { (reference, uri) -> reference.mediaId to uri }
                current.copy(
                    maintenanceEditor = editor.copy(
                        entityId = persisted.id,
                        expectedVersion = persisted.version,
                        readyMedia = persisted.mediaReferences,
                        readyPhotoUris = editor.readyPhotoUris + uploadedPreviewUris,
                        photoUris = editor.photoUris.filterNot(uploadedSet::contains),
                        coverPhotoKey =
                            persisted.coverMediaId
                                ?.let(::maintenanceReadyPhotoKey)
                                ?: maintenanceInitialCoverPhotoKey(persisted.mediaReferences)
                                ?: editor.coverPhotoKey,
                        linkedRepairExpectedVersion =
                            persisted.linkedRepairVersion ?: editor.linkedRepairExpectedVersion,
                        amendmentIdempotencyKey = null,
                    ),
                )
            }
        }
    }

    private fun EstimateDto.toPersistedMaintenanceEntity(): PersistedMaintenanceEntity =
        PersistedMaintenanceEntity(id, version, mediaReferences, coverMediaId)

    private fun EstimateCommandResultDto.toPersistedMaintenanceEntity(): PersistedMaintenanceEntity =
        PersistedMaintenanceEntity(
            id = estimate.id,
            version = estimate.version,
            mediaReferences = estimate.mediaReferences,
            coverMediaId = estimate.coverMediaId,
            linkedRepairVersion = repair?.version,
        )

    private fun RepairDto.toPersistedMaintenanceEntity(): PersistedMaintenanceEntity =
        PersistedMaintenanceEntity(id, version, mediaReferences, coverMediaId)

    private fun requireMaintenanceEditor(): MaintenanceEditorState =
        requireNotNull(mutableState.value.maintenanceEditor) {
            "Откройте смету или ремонт"
        }

    private fun normalizeMaintenanceEditor(editor: MaintenanceEditorState): MaintenanceEditorState {
        val normalized = editor.normalizedLogisticsPlanning().copy(
            lines = editor.lines.map(MaintenanceLineEditorState::normalizedMaintenanceAnnotations),
        )
        val rebuiltStages = runCatching { buildMaintenanceStages(normalized) }.getOrNull()
            ?: return normalized
        return normalized.copy(
            stages = rebuiltStages.map { stage ->
                MaintenanceStageEditorState(
                    id = stage.id,
                    kind = stage.kind,
                    routing = stage.routing,
                    includedLineIds = stage.includedLineIds,
                    primaryLineId = stage.primaryLineId,
                    groupComment = stage.groupComment,
                    taskDeadline = stage.taskDeadline,
                    originalOrder = stage.order,
                )
            },
        )
    }

    private suspend fun loadWorkspace() {
        setBusy(true)
        try {
            backend.warmUpTransport()
            val user = backend.api.currentUser()
            if (user.principalType != "USER" || user.globalRole !in MANAGER_ROLES) {
                backend.auth.invalidate(
                    "Эта роль не может входить в приложение руководителя",
                )
                return
            }
            val allWarehouses = backend.api.warehouses().filter(WarehouseDto::active)
            val visible = WarehouseAccessPolicy.visibleWarehouses(user, allWarehouses)
            if (visible.isEmpty()) {
                throw IllegalStateException("Пользователю не назначен доступ ни к одному складу")
            }
            val saved = preference.read()
            val selected = saved?.takeIf { id -> visible.any { it.id == id } }
                ?: visible.first().id
            preference.save(selected)
            mutableState.update {
                it.copy(
                    currentUser = user,
                    warehouses = visible,
                    selectedWarehouseId = selected,
                    warehouseSelectionLocked =
                        !user.warehouseAccessAll && visible.size == 1,
                    serverReachable = true,
                    message = null,
                )
            }
            restoreMaintenanceCatalogFromDisk(selected)
            startMaintenanceCatalogScheduler()
            requestMaintenanceCatalogSyncIfDue()
        } catch (failure: Throwable) {
            mutableState.update { it.copy(serverReachable = false) }
            handleFailure(failure)
        } finally {
            setBusy(false)
        }
    }

    private fun startConnectivityMonitor() {
        if (mutableState.value.authState != ManagerAuthState.SignedIn ||
            connectivityMonitorJob?.isActive == true
        ) {
            return
        }
        connectivityMonitorJob = viewModelScope.launch {
            while (isActive) {
                probeServerConnection()
                delay(SERVER_CONNECTIVITY_CHECK_MILLIS)
            }
        }
    }

    private fun stopConnectivityMonitor() {
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null
        connectivityProbeJob?.cancel()
        connectivityProbeJob = null
    }

    private suspend fun probeServerConnection() {
        try {
            backend.api.currentUser()
            mutableState.update { it.copy(serverReachable = true) }
        } catch (failure: Throwable) {
            mutableState.update { it.copy(serverReachable = false) }
            if (failure is HttpException && failure.code() == 401) {
                backend.auth.invalidate("Сессия завершена. Войдите снова")
            }
        }
    }

    private suspend fun refreshShipments() {
        val warehouseId = requireWarehouseId()
        val documents = backend.api.shipments(warehouseId)
            .filter { it.documentType == "SHIPMENT" }
        val labels = resolveLogisticsAssetLabels(documents)
        if (mutableState.value.selectedWarehouseId != warehouseId) return
        mutableState.update {
            it.copy(
                shipments = documents,
                logisticsAssetLabels = it.logisticsAssetLabels + labels,
            )
        }
    }

    private suspend fun refreshTransfers() {
        val warehouseId = requireWarehouseId()
        val documents = backend.api.transfers(warehouseId)
            .filter { it.documentType == "TRANSFER" }
        val labels = resolveLogisticsAssetLabels(documents)
        if (mutableState.value.selectedWarehouseId != warehouseId) return
        mutableState.update {
            it.copy(
                transfers = documents,
                logisticsAssetLabels = it.logisticsAssetLabels + labels,
            )
        }
    }

    private suspend fun resolveLogisticsAssetLabels(
        documents: List<LogisticsDocumentDto>,
    ): Map<String, String> = documents
        .flatMap(LogisticsDocumentDto::lines)
        .map { it.assetId }
        .distinct()
        .associateWith { assetId ->
            backend.api.rentalItem(assetId).number
        }

    private fun applyShipmentProjection(document: LogisticsDocumentDto) {
        require(document.documentType == "SHIPMENT") {
            "Сервис вернул не документ отгрузки"
        }
        mutableState.update { current ->
            current.copy(
                shipments = current.shipments.replaceLogisticsDocument(document),
                selectedShipment = document,
            )
        }
    }

    private suspend fun refreshShipmentReadiness(document: LogisticsDocumentDto) {
        if (mutableState.value.selectedShipment?.id == document.id) {
            mutableState.update { it.copy(shipmentFurnitureReadiness = null) }
        }
        val readiness = if (document.needsShipmentFurnitureReadiness()) {
            backend.api.shipmentFurnitureReadiness(document.id)
        } else {
            null
        }
        if (mutableState.value.selectedShipment?.id == document.id) {
            mutableState.update { it.copy(shipmentFurnitureReadiness = readiness) }
        }
    }

    private fun requireShipmentFurnitureReady(document: LogisticsDocumentDto) {
        if (!document.needsShipmentFurnitureReadiness()) return
        val state = mutableState.value.shipmentFurnitureReadiness?.state
        require(state == "NOT_REQUIRED" || state == "READY") {
            "Сначала завершите задания по мебели"
        }
    }

    private fun applyTransferProjection(document: LogisticsDocumentDto) {
        require(document.documentType == "TRANSFER") {
            "Сервис вернул не документ перемещения"
        }
        mutableState.update { current ->
            current.copy(
                transfers = current.transfers.replaceLogisticsDocument(document),
                selectedTransfer =
                    if (current.selectedTransfer?.id == document.id) document
                    else current.selectedTransfer,
            )
        }
    }

    private suspend fun refreshTransferReadiness(document: LogisticsDocumentDto) {
        if (mutableState.value.selectedTransfer?.id == document.id) {
            mutableState.update { it.copy(transferFurnitureReadiness = null) }
        }
        val readiness = if (document.needsTransferFurnitureReadiness()) {
            backend.api.transferFurnitureReadiness(document.id)
        } else {
            null
        }
        if (mutableState.value.selectedTransfer?.id == document.id) {
            mutableState.update { it.copy(transferFurnitureReadiness = readiness) }
        }
    }

    private fun requireTransferFurnitureReady(document: LogisticsDocumentDto) {
        if (!document.needsTransferFurnitureReadiness()) return
        val state = mutableState.value.transferFurnitureReadiness?.state
        require(state == "NOT_REQUIRED" || state == "READY") {
            "Сначала завершите задания по мебели"
        }
    }

    private fun requireTransferManageAccess(document: LogisticsDocumentDto) {
        requireWarehouseAccess(document.warehouseId, "MANAGE")
        requireWarehouseAccess(
            requireNotNull(document.destinationWarehouseId) {
                "Сервис не вернул склад назначения"
            },
            "MANAGE",
        )
    }

    private fun requireWarehouseAccess(warehouseId: String, level: String) {
        val user = requireNotNull(mutableState.value.currentUser) {
            "Не удалось проверить права пользователя"
        }
        require(WarehouseAccessPolicy.hasAccess(user, warehouseId, level)) {
            "Недостаточно прав для операции на складе"
        }
    }

    private fun logisticsCommandKey(signature: String): String =
        commandKeys.key(signature)

    private suspend fun refreshInventory(force: Boolean = false) {
        val warehouseId = requireWarehouseId()
        val scope = readCacheScope(warehouseId)
        inventoryReadMutex.withLock {
            val cached = managerReadCache.readInventory(scope)
            val snapshot = try {
                val active = backend.api.activeInventory(
                    warehouseId = warehouseId,
                    ifNoneMatch = if (force) null else cached?.activeEtag,
                )
                when (active.code()) {
                    304 -> requireNotNull(cached) {
                        "Сервер подтвердил старую инвентаризацию, которой нет на телефоне"
                    }.copy(activeEtag = active.headers()["ETag"] ?: cached.activeEtag)

                    204 -> CachedInventoryRead(
                        activeEtag = active.headers()["ETag"],
                        rentalItems = cached?.rentalItems.orEmpty(),
                    )

                    else -> {
                        if (!active.isSuccessful) throw HttpException(active)
                        val session = requireNotNull(active.body()) {
                            "RWMS не вернул активную инвентаризацию"
                        }
                        CachedInventoryRead(
                            activeEtag = active.headers()["ETag"],
                            rentalItems = loadInventoryRentalItems(warehouseId),
                            session = session,
                            findings = loadInventoryFindings(session.id),
                        )
                    }
                }
            } catch (failure: Throwable) {
                if (!force && cached != null && canUseCachedReadAfter(failure)) {
                    applyInventoryRead(warehouseId, cached)
                    return@withLock
                }
                throw failure
            }
            if (mutableState.value.selectedWarehouseId != warehouseId) return@withLock
            managerReadCache.writeInventory(scope, snapshot)
            applyInventoryRead(warehouseId, snapshot)
        }
    }

    private suspend fun loadInventoryRentalItems(warehouseId: String): List<RentalItemDto> {
        val rentalItems = mutableListOf<RentalItemDto>()
        var rentalPage = 0
        var rentalTotalPages: Int
        do {
            val result = backend.api.rentalItems(
                warehouseId = warehouseId,
                page = rentalPage,
                size = 200,
            )
            rentalItems += result.content
            rentalTotalPages = result.totalPages
            rentalPage += 1
        } while (rentalPage < rentalTotalPages)
        return rentalItems
            .distinctBy(RentalItemDto::id)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { item -> item.number })
    }

    private suspend fun loadInventoryFindings(inventoryId: String): List<InventoryFindingDto> {
        val findings = mutableListOf<InventoryFindingDto>()
        var page = 0
        var totalPages: Int
        do {
            val result = backend.api.inventoryFindings(inventoryId, page)
            findings += result.content
            totalPages = result.page.totalPages
            page += 1
        } while (page < totalPages)
        return findings
    }

    private fun applyInventoryRead(
        warehouseId: String,
        snapshot: CachedInventoryRead,
    ) {
        mutableState.update { current ->
            if (current.selectedWarehouseId == warehouseId) {
                current.copy(
                    inventoryRentalItems = snapshot.rentalItems,
                    inventorySession = snapshot.session,
                    inventoryFindings = snapshot.findings,
                )
            } else {
                current
            }
        }
    }

    private suspend fun enqueueReturnUpload(
        document: LogisticsDocumentDto,
        action: ReturnUploadAction,
        shortages: Map<String, EquipmentShortageRequest> = emptyMap(),
    ) {
        val photos = document.lines.flatMap { line ->
            val existing = mutableState.value.returnReadyMedia[line.id].orEmpty()
            val local = mutableState.value.returnPhotoUris[line.id].orEmpty()
            if (existing.isEmpty() && local.isEmpty()) {
                throw IllegalArgumentException(
                    "Добавьте фотографию для строки ${line.lineNumber}",
                )
            }
            val owner = MediaOwner(
                ownerType = "LOGISTICS_RETURN",
                documentId = document.id,
                lineId = line.id,
                warehouseId = document.warehouseId,
                context = "RETURN_INSPECTION",
            )
            local.mapIndexed { index, uri ->
                PendingBackgroundPhoto(
                    uri = uri,
                    owner = owner,
                    sortOrder = existing.size + index,
                )
            }
        }
        backgroundUploads.await().enqueue(
            BackgroundUploadDraft(
                area = BackgroundUploadArea.LOGISTICS,
                title = if (action == ReturnUploadAction.ACCEPT) {
                    "Приёмка возврата"
                } else {
                    "Возврат со сметой"
                },
                subtitle = "${document.lines.size} бытовок",
                photos = photos,
                returnAction = ReturnUploadCommand(
                    documentId = document.id,
                    warehouseId = document.warehouseId,
                    expectedVersion = document.version,
                    action = action,
                    lines = document.lines.map { line ->
                        ReturnUploadLineCommand(
                            lineId = line.id,
                            equipmentConfirmed = true,
                            shortages = shortages[line.id]?.let(::listOf).orEmpty(),
                            existingMedia = mutableState.value.returnReadyMedia[line.id].orEmpty(),
                        )
                    },
                    idempotencyKey = UUID.randomUUID().toString(),
                ),
            ),
        )
    }

    private suspend fun currentReturnInspectionDocument(
        displayedDocument: LogisticsDocumentDto,
    ): LogisticsDocumentDto {
        val currentDocument = backend.api.returnDocument(displayedDocument.id)
        require(currentDocument.documentType == "RETURN") {
            "RWMS вернул не документ возврата"
        }
        require(currentDocument.warehouseId == requireWarehouseId()) {
            "Возврат относится к другому складу"
        }
        applyCurrentReturnDocument(currentDocument)
        currentDocument.returnInspectionActionError()?.let { error ->
            throw IllegalStateException(error)
        }
        val displayedLineIds = displayedDocument.lines.mapTo(linkedSetOf()) { it.id }
        val currentLineIds = currentDocument.lines.mapTo(linkedSetOf()) { it.id }
        require(displayedLineIds == currentLineIds) {
            "Состав возврата изменился. Экран обновлён, проверьте данные и повторите действие."
        }
        return currentDocument
    }

    private fun applyCurrentReturnDocument(document: LogisticsDocumentDto) {
        mutableState.update { current ->
            val selected = current.selectedReturn
            current.copy(
                selectedReturn = if (selected?.id == document.id) document else selected,
                returns = current.returns.map { listed ->
                    if (listed.id == document.id) document else listed
                },
            )
        }
    }

    private fun command(block: suspend () -> Unit) {
        viewModelScope.launch {
            setBusy(true)
            try {
                block()
            } catch (failure: Throwable) {
                handleFailure(failure)
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun handleFailure(failure: Throwable) {
        val text = when (failure) {
            is HttpException -> backend.problemMessage(failure)
            is SocketTimeoutException ->
                "Не удалось передать фотографию вовремя. Проверьте сеть и повторите сохранение"
            is IOException -> "Нет связи с RWMS. Проверьте подключение"
            is JsonDataException ->
                "RWMS вернул данные старого формата. Обновите страницу и повторите операцию"
            is IllegalArgumentException, is IllegalStateException ->
                failure.message ?: "Операция не выполнена"
            else -> "Операция не выполнена"
        }
        if (failure is HttpException && failure.code() == 401) {
            backend.auth.invalidate("Сессия завершена. Войдите снова")
        }
        message(text)
    }

    private fun setBusy(value: Boolean) {
        mutableState.update { it.copy(busy = value) }
    }

    private fun message(value: String) {
        mutableState.update { it.copy(message = value) }
    }

    private data class ConditionalRead<T>(
        val value: T,
        val etag: String?,
    )

    private fun <T> conditionalRead(
        response: retrofit2.Response<T>,
        cachedValue: T?,
        cachedEtag: String?,
        missingCacheMessage: String,
    ): ConditionalRead<T> = when (response.code()) {
        304 -> ConditionalRead(
            value = requireNotNull(cachedValue) { missingCacheMessage },
            etag = response.headers()["ETag"] ?: cachedEtag,
        )

        else -> {
            if (!response.isSuccessful) throw HttpException(response)
            ConditionalRead(
                value = requireNotNull(response.body()) { "RWMS вернул пустой ответ" },
                etag = response.headers()["ETag"],
            )
        }
    }

    private fun canUseCachedReadAfter(failure: Throwable): Boolean =
        failure is IOException || (failure is HttpException && failure.code() in 500..599)

    private fun readCacheScope(warehouseId: String): ManagerReadCacheScope =
        ManagerReadCacheScope(
            accountId = requireNotNull(mutableState.value.currentUser?.id) {
                "Не удалось определить пользователя для локального кэша"
            },
            warehouseId = warehouseId,
        )

    private fun requireWarehouseId(): String =
        requireNotNull(mutableState.value.selectedWarehouseId) {
            "Склад не выбран"
        }

    private fun clearMaintenanceCatalogMemory() {
        maintenanceCatalogSyncJob?.cancel()
        maintenanceCatalogSyncJob = null
        maintenanceCatalogSchedulerJob?.cancel()
        maintenanceCatalogSchedulerJob = null
        maintenanceCatalogWarehouseId = null
        maintenanceCatalogNodesById = emptyMap()
        maintenanceCatalogRevision = null
        maintenanceCatalogActiveVersionEtag = null
        assetSearchGeneration += 1
    }

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

object WarehouseAccessPolicy {
    fun visibleWarehouses(
        user: CurrentUserDto,
        warehouses: List<WarehouseDto>,
    ): List<WarehouseDto> {
        if (user.warehouseAccessAll) return warehouses
        val allowed = user.warehouseAccesses.map { it.warehouseId }.toSet()
        return warehouses.filter { it.id in allowed }
    }

    fun hasAccess(
        user: CurrentUserDto,
        warehouseId: String,
        requiredLevel: String,
    ): Boolean {
        if (user.warehouseAccessAll) return true
        val required = WAREHOUSE_ACCESS_ORDER[requiredLevel] ?: return false
        val actual = user.warehouseAccesses
            .firstOrNull { it.warehouseId == warehouseId }
            ?.level
            ?.let(WAREHOUSE_ACCESS_ORDER::get)
            ?: return false
        return actual >= required
    }
}

private val WAREHOUSE_ACCESS_ORDER = mapOf(
    "VIEW" to 0,
    "EDIT" to 1,
    "MANAGE" to 2,
)

private val SHIPMENT_CANCELLABLE_STATES = setOf(
    "DRAFT",
    "PREPARING",
    "AWAITING_CONFIRMATION",
)

private fun List<LogisticsDocumentDto>.replaceLogisticsDocument(
    document: LogisticsDocumentDto,
): List<LogisticsDocumentDto> =
    if (any { it.id == document.id }) {
        map { current -> if (current.id == document.id) document else current }
    } else {
        listOf(document) + this
    }

private class WarehousePreference(application: Application) {
    private val preferences = application.getSharedPreferences(
        "rwms_manager_ui",
        Application.MODE_PRIVATE,
    )

    fun read(): String? = preferences.getString(KEY, null)

    fun save(warehouseId: String) {
        preferences.edit().putString(KEY, warehouseId).apply()
    }

    private companion object {
        const val KEY = "last_service_warehouse_id"
    }
}

private fun InventoryEditorState.passport(): Map<String, Any?> =
    buildMap {
        rentalType.trim().takeIf(String::isNotEmpty)?.let { put("rentalType", it) }
        dimensions.trim().takeIf(String::isNotEmpty)?.let { put("dimensions", it) }
        finishing.trim().takeIf(String::isNotEmpty)?.let { put("finishing", it) }
        category.trim().takeIf(String::isNotEmpty)?.let { put("category", it) }
        inventoryCharacteristics().takeIf(List<String>::isNotEmpty)?.let {
            put("characteristics", it.joinToString(", "))
        }
        linoleum?.let { put("linoleum", it) }
        put("passport", emptyMap<String, Any?>())
        put("tags", emptyList<String>())
    }

private fun InventoryEditorState.observationPassport(): Map<String, Any?> =
    buildMap {
        rentalType.trim().takeIf(String::isNotEmpty)?.let { put("rentalType", it) }
        dimensions.trim().takeIf(String::isNotEmpty)?.let { put("dimensions", it) }
        finishing.trim().takeIf(String::isNotEmpty)?.let { put("finishing", it) }
        category.trim().takeIf(String::isNotEmpty)?.let { put("category", it) }
        inventoryCharacteristics().takeIf(List<String>::isNotEmpty)?.let {
            put("characteristics", it.joinToString(", "))
        }
        linoleum?.let { put("linoleum", it) }
    }

internal fun InventoryFindingDto.inspectionPassport(): Map<String, Any?> =
    when (passportObservation.presence) {
        "PRESENT" -> passportObservation.value.asStringMap()
        "EXPLICIT_EMPTY" -> emptyMap()
        else -> inspectionBaseline?.passportSnapshot
            ?: expectedSnapshot?.passportSnapshot
            ?: currentSnapshot?.passportSnapshot
            ?: emptyMap()
    }

internal data class InventoryPassportFacts(
    val rentalType: String?,
    val dimensions: String?,
    val finishing: String?,
    val category: String?,
    val linoleum: Boolean?,
)

internal fun InventoryFindingDto.inventoryPassportFacts(): InventoryPassportFacts {
    val passport = inspectionPassport()
    return InventoryPassportFacts(
        rentalType = passport.text("rentalType").takeIf(String::isNotBlank),
        dimensions = passport.text("dimensions").takeIf(String::isNotBlank),
        finishing = passport.text("finishing").takeIf(String::isNotBlank),
        category = passport.text("category").takeIf(String::isNotBlank),
        linoleum = passport["linoleum"] as? Boolean,
    )
}

private fun Any?.asStringMap(): Map<String, Any?> {
    val raw = this as? Map<*, *> ?: return emptyMap()
    return buildMap {
        raw.forEach { (key, value) ->
            if (key is String) put(key, value)
        }
    }
}

private fun Map<String, Any?>.text(key: String): String =
    this[key]?.toString().orEmpty()

internal data class ParsedInventoryCharacteristics(
    val selected: List<String>,
    val toilets: Int,
    val sinks: Int,
    val showers: Int,
)

private val sanitaryCharacteristicPattern =
    Regex("""^(Туалеты|Раковины|Душевые):\s*(\d+)$""", RegexOption.IGNORE_CASE)

/**
 * The asset projection may contain characteristics as a JSON array, a comma-separated string,
 * or no value at all. In particular, an empty JSON array must remain an empty selection rather
 * than becoming the literal characteristic `[]` in the editor.
 */
internal fun parseInventoryCharacteristics(value: Any?): ParsedInventoryCharacteristics {
    var toilets = 0
    var sinks = 0
    var showers = 0
    val rawValues = when (value) {
        null -> emptyList()
        is Iterable<*> -> value.mapNotNull { item -> item?.toString() }
        is Array<*> -> value.mapNotNull { item -> item?.toString() }
        else -> listOf(value.toString())
    }
    val selected = buildList {
        rawValues.forEach { rawValue ->
            rawValue.split(',').forEach tokenLoop@ { token ->
                val characteristic = token.trim()
                if (characteristic.isEmpty() || characteristic == "[]") return@tokenLoop
                val match = sanitaryCharacteristicPattern.matchEntire(characteristic)
                if (match == null) {
                    add(characteristic)
                } else {
                    val count = match.groupValues[2].toIntOrNull() ?: 0
                    when (match.groupValues[1].lowercase()) {
                        "туалеты" -> toilets = count
                        "раковины" -> sinks = count
                        "душевые" -> showers = count
                    }
                }
            }
        }
    }
    return ParsedInventoryCharacteristics(
        selected = selected.distinct(),
        toilets = toilets,
        sinks = sinks,
        showers = showers,
    )
}

internal fun InventoryEditorState.inventoryCharacteristics(): List<String> =
    buildList {
        addAll(characteristics.distinct())
        if (isSanitary) {
            add("Туалеты: ${sanitaryToilets.coerceAtLeast(0)}")
            add("Раковины: ${sanitarySinks.coerceAtLeast(0)}")
            add("Душевые: ${sanitaryShowers.coerceAtLeast(0)}")
        }
    }

/** Text shown in the selector. An empty selection deliberately renders as an empty field. */
internal fun inventoryCharacteristicsDisplayValue(characteristics: List<String>): String =
    characteristics.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot { it == "[]" }
        .distinct()
        .joinToString()

internal fun InventoryEditorState.withInventoryCreationOrigin(
    origin: String,
): InventoryEditorState {
    require(origin == "ADDED_NEW" || origin == "ADDED_USED")
    val options = creationOptions
    val selectedCategory = when (origin) {
        "ADDED_NEW" -> options?.newCategory.orEmpty()
        else -> category.takeIf { it in options?.usedCategories.orEmpty() }.orEmpty()
    }
    return copy(
        creationOrigin = origin,
        category = selectedCategory,
    )
}

internal fun InventoryEditorState.withInventoryRentalType(
    value: String,
): InventoryEditorState {
    val firstDimension = creationOptions
        ?.dimensionOptionsForRentalType(value)
        ?.firstOrNull()
        ?.name
        .orEmpty()
    return copy(
        rentalType = value,
        dimensions = firstDimension,
    )
}

internal fun InventoryEditorState.inventoryCategoryOptions(): List<String> {
    val serviceValues = when {
        !isCreation -> listOfNotNull(creationOptions?.newCategory) +
            creationOptions?.usedCategories.orEmpty()
        creationOrigin == "ADDED_NEW" -> listOfNotNull(creationOptions?.newCategory)
        creationOrigin == "ADDED_USED" -> creationOptions?.usedCategories.orEmpty()
        else -> emptyList()
    }
    return (serviceValues + category.takeIf(String::isNotBlank)).filterNotNull().distinct()
}

internal fun InventoryEditorState.inventoryRentalTypeOptions(): List<String> =
    (creationOptions?.rentalTypes.orEmpty().map(CabinCatalogValueDto::name) +
        rentalType.takeIf(String::isNotBlank))
        .filterNotNull()
        .distinct()

internal fun InventoryEditorState.inventoryDimensionOptions(): List<String> =
    (creationOptions?.dimensionOptionsForRentalType(rentalType).orEmpty().map(CabinCatalogValueDto::name) +
        dimensions.takeIf(String::isNotBlank))
        .filterNotNull()
        .distinct()

internal fun InventoryEditorState.inventoryFinishingOptions(): List<String> =
    (creationOptions?.finishings.orEmpty().map(CabinCatalogValueDto::name) +
        finishing.takeIf(String::isNotBlank))
        .filterNotNull()
        .distinct()

internal fun InventoryEditorState.inventoryCharacteristicOptions(): List<String> =
    (creationOptions?.characteristics.orEmpty().map(CabinCatalogValueDto::name) + characteristics)
        .distinct()

internal fun InventoryEditorState.inventoryCreationValidationError(
    hasCreationPhoto: Boolean,
    hasCoverPhoto: Boolean = false,
): String? {
    if (!isCreation) return null
    val options = creationOptions ?: return "Варианты паспорта бытовки не загружены"
    val origin = creationOrigin ?: return "Выберите: новая или б/у"
    if (category.isBlank()) return "Выберите категорию бытовки"
    if (origin == "ADDED_NEW" && category != options.newCategory) {
        return "Для новой бытовки доступна только категория ${options.newCategory}"
    }
    if (origin == "ADDED_USED" && category !in options.usedCategories) {
        return "Выберите категорию б/у бытовки"
    }
    val type = options.rentalTypes.firstOrNull { it.name == rentalType }
        ?: return "Выберите тип бытовки"
    if (dimensions !in options.dimensionOptionsForTypeId(type.id).map(CabinCatalogValueDto::name)) {
        return "Выберите габариты бытовки"
    }
    if (finishing !in options.finishings.map(CabinCatalogValueDto::name)) {
        return "Выберите отделку бытовки"
    }
    if (linoleum == null) return "Укажите, есть ли линолеум"
    if (characteristics.any { it !in options.characteristics.map(CabinCatalogValueDto::name) }) {
        return "Выберите характеристики из списка"
    }
    if (sanitaryToilets < 0 || sanitarySinks < 0 || sanitaryShowers < 0) {
        return "Количество оборудования санблока не может быть отрицательным"
    }
    if (!hasCreationPhoto) return "Для добавляемой бытовки обязательна фотография"
    if (!hasCoverPhoto) return "Выберите титульную фотографию"
    return null
}

private fun RentalItemCreationOptionsDto.dimensionOptionsForRentalType(
    rentalTypeName: String,
): List<CabinCatalogValueDto> =
    rentalTypes
        .firstOrNull { it.name == rentalTypeName }
        ?.let { type -> dimensionOptionsForTypeId(type.id) }
        .orEmpty()

private fun RentalItemCreationOptionsDto.dimensionOptionsForTypeId(
    typeId: String,
): List<CabinCatalogValueDto> {
    val dimensionsById = dimensions.associateBy(CabinCatalogValueDto::id)
    return typeDimensions
        .asSequence()
        .filter { it.typeId == typeId }
        .sortedBy { it.sortOrder }
        .mapNotNull { dimensionsById[it.dimensionId] }
        .toList()
}

internal fun InventoryEditorState.inventoryPhotoUrisForUpload(): List<String> {
    if (photoUris.isEmpty()) {
        if (persistedInventoryMediaReferences().isNotEmpty()) return emptyList()
        throw IllegalArgumentException("Добавьте хотя бы одну фотографию")
    }
    val cover = coverPhotoUri?.takeIf { it in photoUris }
    if (cover != null) return listOf(cover) + photoUris.filterNot { it == cover }
    if (persistedInventoryMediaReferences().isNotEmpty()) return photoUris
    throw IllegalArgumentException("Выберите титульную фотографию")
}

internal fun InventoryEditorState.pendingInventoryPhotoUris(): List<String> =
    inventoryPhotoUrisForUpload().filterNot { uri ->
        uri in uploadedPhotoMedia || uri in persistedPhotoMedia
    }

/**
 * Removes a visible cached server photo from the next inventory save without deleting the media
 * asset itself. Unavailable old references are deliberately retained, because a download failure
 * must never be interpreted as an operator decision to remove evidence.
 */
internal fun InventoryEditorState.removeInventoryPhoto(uri: String): InventoryEditorState {
    if (uri !in photoUris) return this
    val removedReference = persistedPhotoMedia[uri]
    return copy(
        photoUris = photoUris - uri,
        coverPhotoUri = coverPhotoUri.takeUnless { selected -> selected == uri },
        persistedPhotoMedia = persistedPhotoMedia - uri,
        persistedPhotoRotationDegrees = persistedPhotoRotationDegrees - uri,
        removedPersistedMediaIds = removedReference?.mediaId?.let { mediaId ->
            removedPersistedMediaIds + mediaId
        } ?: removedPersistedMediaIds,
        uploadedPhotoMedia = uploadedPhotoMedia - uri,
        photoRotationDegrees = photoRotationDegrees - uri,
    )
}

/** Retains one server-confirmed original without changing the user-selected URI order. */
internal fun InventoryEditorState.retainUploadedInventoryPhoto(
    uri: String,
    reference: MediaReferenceDto,
): InventoryEditorState = if (uri in photoUris) {
    copy(uploadedPhotoMedia = uploadedPhotoMedia + (uri to reference))
} else {
    this
}

/**
 * The URI continues to identify the original device file.  This only records the absolute
 * canonical orientation that media-service must apply to its one media asset on save.
 */
internal fun InventoryEditorState.rotateInventoryPhoto(uri: String): InventoryEditorState {
    if (uri !in photoUris) return this
    val persistedRotation = persistedPhotoRotationDegrees[uri] ?: 0
    val nextRotation = ((photoRotationDegrees[uri] ?: persistedRotation) + 90) % 360
    return copy(
        photoRotationDegrees = if (nextRotation == persistedRotation) {
            photoRotationDegrees - uri
        } else {
            photoRotationDegrees + (uri to nextRotation)
        },
    )
}

/** The local preview only needs the delta from the canonical server image already downloaded. */
internal fun InventoryEditorState.inventoryPhotoPreviewRotation(uri: String): Int {
    val requestedRotation = photoRotationDegrees[uri] ?: return 0
    val persistedRotation = persistedPhotoRotationDegrees[uri] ?: 0
    return (requestedRotation - persistedRotation + 360) % 360
}

internal fun inventoryMediaReferencesForEditor(
    editor: InventoryEditorState,
    uploadedByUri: Map<String, MediaReferenceDto>,
    persisted: List<MediaReferenceDto>,
): List<MediaReferenceDto> =
    buildList {
        editor.inventoryPhotoUrisForUpload()
            .map { uri ->
                editor.persistedPhotoMedia[uri] ?: requireNotNull(uploadedByUri[uri]) {
                    "Выбранная фотография ещё не готова к сохранению"
                }
            }
            .forEach(::add)
        addAll(persisted)
    }
        .distinctBy(MediaReferenceDto::mediaId)

internal fun inventoryReadyPersistedMediaReferences(
    persisted: List<MediaReferenceDto>,
    readyOwnerReferences: Set<MediaReferenceDto>,
): List<MediaReferenceDto> =
    persisted.filter(readyOwnerReferences::contains)

internal fun InventoryEditorState.persistedInventoryMediaReferences(): List<MediaReferenceDto> =
    finding?.media.orEmpty()
        .filterNot { reference -> reference.mediaId in removedPersistedMediaIds }
        .distinctBy(MediaReferenceDto::mediaId)

internal fun InventoryEditorState.persistedInventoryPhotoRotations(): List<InventoryExistingMediaRotation> =
    photoUris.mapNotNull { uri ->
        val reference = persistedPhotoMedia[uri] ?: return@mapNotNull null
        val rotationDegrees = photoRotationDegrees[uri] ?: return@mapNotNull null
        InventoryExistingMediaRotation(reference = reference, rotationDegrees = rotationDegrees)
    }

internal fun inventoryExistingMediaReferences(
    persisted: List<MediaReferenceDto>,
    uploadedByUri: Map<String, MediaReferenceDto>,
): List<MediaReferenceDto> =
    (persisted + uploadedByUri.values).distinctBy(MediaReferenceDto::mediaId)

internal fun InventoryEditorState.inventoryPhotoValidationError(): String? =
    when {
        photoUris.isEmpty() && persistedInventoryMediaReferences().isEmpty() ->
            "Добавьте хотя бы одну фотографию"
        photoUris.isNotEmpty() &&
            coverPhotoUri !in photoUris &&
            persistedInventoryMediaReferences().isEmpty() ->
            "Выберите титульную фотографию"
        else -> null
    }

internal fun inventoryRentalItemSuggestions(
    items: List<RentalItemDto>,
    query: String,
    limit: Int = 8,
): List<RentalItemDto> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return items.take(limit)
    return items
        .asSequence()
        .filter { it.number.contains(normalized, ignoreCase = true) }
        .take(limit)
        .toList()
}

internal fun hasExactInventoryRentalItemNumber(
    items: List<RentalItemDto>,
    query: String,
): Boolean {
    val normalized = query.trim()
    return normalized.isNotEmpty() &&
        items.any { it.number.equals(normalized, ignoreCase = true) }
}

internal data class InventorySemanticChange(
    val label: String,
    val before: String,
    val after: String,
)

internal fun InventoryFindingDto.inventorySemanticChanges(): List<InventorySemanticChange> {
    val before = inspectionBaseline ?: return emptyList()
    val after = currentSnapshot ?: return emptyList()
    return buildList {
        addInventorySemanticChange(
            label = "Статус",
            before = inventoryBusinessStatusLabel(before.status),
            after = inventoryBusinessStatusLabel(after.status),
        )
        addInventorySemanticChange(
            label = "Номер",
            before = before.displayCanonicalNumber,
            after = after.displayCanonicalNumber,
        )
        addInventorySemanticChange(
            label = "Склад",
            before = before.warehouseId,
            after = after.warehouseId,
        )
        addInventorySemanticChange(
            label = "Контрагент",
            before = before.tenantSnapshot.orEmpty().ifBlank { "Не указан" },
            after = after.tenantSnapshot.orEmpty().ifBlank { "Не указан" },
        )
        addInventorySemanticChange(
            label = "Паспорт",
            before = formatInventorySnapshotValue(before.passportSnapshot),
            after = formatInventorySnapshotValue(after.passportSnapshot),
        )
        addInventorySemanticChange(
            label = "Комплектация",
            before = formatInventorySnapshotValue(before.contentsSnapshot),
            after = formatInventorySnapshotValue(after.contentsSnapshot),
        )
        addInventorySemanticChange(
            label = "Ремонты",
            before = formatInventoryRepairs(before.repairsSnapshot),
            after = formatInventoryRepairs(after.repairsSnapshot),
        )
    }
}

private fun MutableList<InventorySemanticChange>.addInventorySemanticChange(
    label: String,
    before: String,
    after: String,
) {
    if (before != after) add(InventorySemanticChange(label, before, after))
}

private fun formatInventorySnapshotValue(value: Any?): String =
    when (value) {
        null -> "Нет"
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString("; ") { (key, item) ->
                "$key: ${formatInventorySnapshotValue(item)}"
            }
            .ifBlank { "Нет" }
        is Iterable<*> -> value
            .joinToString("; ") { formatInventorySnapshotValue(it) }
            .ifBlank { "Нет" }
        else -> value.toString().ifBlank { "Нет" }
    }

private fun formatInventoryRepairs(
    repairs: List<dev.buhanzaz.rwms.manager.network.InventoryRepairRegistryFactDto>,
): String = repairs.joinToString("; ") {
    listOf(
        "Ремонт",
        it.kind,
        it.executionState,
        it.acceptanceState,
        "план зафиксирован",
    ).joinToString(" · ")
}.ifBlank { "Нет" }

internal fun InventoryFindingDto.inventoryBusinessStatus(): String? =
    currentSnapshot?.status
        ?: inspectionBaseline?.status
        ?: expectedSnapshot?.status

internal fun inventoryBusinessStatusLabel(value: String): String = when (value) {
    "BOOKED" -> "Забронирована"
    "RENTED" -> "В аренде"
    "REPAIR" -> "Ремонт"
    "WAITING_REPAIR_CHECK" -> "Ожидает проверки ремонта"
    "CAPITAL_REPAIR" -> "Капитальный ремонт"
    "AFTER_RENT" -> "После аренды"
    "WAITING_ESTIMATE_CONFIRMATION" -> "Ожидает подтверждения сметы"
    "SALE" -> "Продажа"
    "USED_SALE" -> "Продажа б/у"
    "RESERVED" -> "Резерв"
    "FREE" -> "Свободная"
    "WAREHOUSE" -> "На складе"
    "OWN_NEEDS" -> "Собственные нужды"
    "IN_TRANSFER" -> "Перемещение"
    "WRITTEN_OFF" -> "Списана"
    else -> value
}

internal fun inventoryInspectionLabel(value: String): String = when (value) {
    "NOT_INSPECTED" -> "Непроверена"
    "READY", "WORK_STAGED" -> "Проверена"
    else -> value
}

internal fun List<EquipmentCatalogItemDto>.maintenanceFurnitureCatalog(): List<EquipmentCatalogItemDto> =
    filter { equipment -> equipment.active && equipment.category == "FURNITURE" }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, EquipmentCatalogItemDto::name)
                .thenBy(EquipmentCatalogItemDto::id),
        )

internal fun RentalItemDto.maintenanceFurnitureInitialQuantities(
    furnitureCatalog: List<EquipmentCatalogItemDto>,
): Map<String, String> {
    val currentByEquipmentId = linkedMapOf<String, Long>()
    contents.forEach { content ->
        if (content.quantity > 0) {
            currentByEquipmentId[content.equipmentId] =
                (currentByEquipmentId[content.equipmentId] ?: 0L) + content.quantity
        }
    }
    return furnitureCatalog.associate { equipment ->
        equipment.id to (currentByEquipmentId[equipment.id] ?: 0L).toString()
    }
}

internal fun MaintenanceFurnitureEditorState.maintenanceFurnitureCompositionValidationError(): String? {
    var nonZeroCount = 0
    for (equipment in furnitureCatalog) {
        val text = quantities[equipment.id]?.trim().orEmpty()
        if (text.isEmpty() || text.any { character -> !character.isDigit() }) {
            return "Количество для «${equipment.name}» должно быть целым числом"
        }
        val quantity = text.toLongOrNull()
            ?: return "Количество для «${equipment.name}» слишком велико"
        if (quantity > 0) nonZeroCount += 1
    }
    return if (nonZeroCount > 100) {
        "В составе бытовки может быть не больше 100 позиций"
    } else {
        null
    }
}

internal fun MaintenanceFurnitureEditorState.maintenanceFurnitureDesiredContents():
    List<CabinFurnitureRequirementDto> {
    check(maintenanceFurnitureCompositionValidationError() == null) {
        "Состав мебели заполнен некорректно"
    }
    return furnitureCatalog.mapNotNull { equipment ->
        val quantity = requireNotNull(quantities[equipment.id])
            .trim()
            .toLong()
        if (quantity > 0) CabinFurnitureRequirementDto(equipment.id, quantity) else null
    }
}

/** The disposition question only makes sense when the operator actually recorded furniture. */
internal fun MaintenanceFurnitureEditorState.hasObservedFurniture(): Boolean =
    furnitureCatalog.any { equipment ->
        quantities[equipment.id]?.trim()?.toLongOrNull()?.let { quantity -> quantity > 0L } == true
    }

internal fun maintenanceFurnitureScheduledDateValidationError(scheduledDate: String): String? =
    if (runCatching { LocalDate.parse(scheduledDate) }.isSuccess) {
        null
    } else {
        "Укажите корректную дату задания"
    }

internal fun ManagerUiState.withStartedMaintenanceEditor(
    editor: MaintenanceEditorState,
): ManagerUiState = copy(
    maintenanceEditor = editor,
    maintenanceFurnitureEditor = null,
    assetSearch = "",
    assetSearchResults = emptyList(),
    assetSearchBusy = false,
    assetSearchCompletedQuery = null,
    assetSearchFailedQuery = null,
)

internal fun ManagerUiState.withClosedMaintenanceEditor(): ManagerUiState = copy(
    maintenanceEditor = null,
    maintenanceFurnitureEditor = null,
    assetSearch = "",
    assetSearchResults = emptyList(),
    assetSearchBusy = false,
    assetSearchCompletedQuery = null,
    assetSearchFailedQuery = null,
)

internal fun readOnlyMaintenanceEditorStepChange(
    original: MaintenanceEditorState,
    candidate: MaintenanceEditorState,
): MaintenanceEditorState = original.copy(step = candidate.step)

internal fun preserveMaintenanceImmutableFields(
    original: MaintenanceEditorState,
    candidate: MaintenanceEditorState,
): MaintenanceEditorState {
    var preserved = candidate.copy(
        entityId = original.entityId,
        expectedVersion = original.expectedVersion,
        readOnly = original.readOnly,
        createIdempotencyKey = original.createIdempotencyKey,
        documentState = original.documentState,
        linkedRepairExpectedVersion = original.linkedRepairExpectedVersion,
        repairKind = original.repairKind,
        sourceRepairId = original.sourceRepairId,
        sourceRepairExpectedVersion = original.sourceRepairExpectedVersion,
    )
    if (original.entityId != null) {
        preserved = preserved.copy(selectedAsset = original.selectedAsset)
    }
    if (original.mode == MaintenanceEditorMode.REPAIR && original.entityId != null) {
        preserved = preserved.copy(
            dispatchDate = original.dispatchDate,
            sourceParty = original.sourceParty,
        )
    }
    return preserved
}

internal fun maintenanceDocumentAlreadySubmitted(editor: MaintenanceEditorState): Boolean =
    when (editor.mode) {
        MaintenanceEditorMode.ESTIMATE -> editor.documentState == "COMPLETED"
        MaintenanceEditorMode.REPAIR -> editor.documentState == "QUEUED"
    }

private fun CatalogNodeDto.isOperationalEstimateNode(): Boolean =
    active &&
        includeInEstimate &&
        nodeType in setOf("WORK", "MATERIAL", "OPTION")

/**
 * The user selects a route for a manual inventory line, while an inventory stage still needs
 * one catalog node as its technical reference. The routing can be inherited from a category,
 * while catalog administration may legitimately contain several operational descendants on that
 * route. Choose the lowest stable node id rather than making a valid manual line impossible to
 * save.
 */
internal fun inventoryStageCatalogNodeId(
    catalogNodes: Collection<CatalogNodeDto>,
    selectedRouting: RoutingSnapshotDto,
): String? {
    val activeNodesById = catalogNodes
        .asSequence()
        .filter(CatalogNodeDto::active)
        .associateBy(CatalogNodeDto::id)
    return activeNodesById.values
        .asSequence()
        .filter { node ->
            node.isOperationalEstimateNode() && node.nodeType in setOf("WORK", "MATERIAL")
        }
        .filter { node ->
            effectiveCatalogNodeRouting(node.id, activeNodesById)?.routingKey() ==
                selectedRouting.routingKey()
        }
        .map(CatalogNodeDto::id)
        .minOrNull()
}

/**
 * Every manual inventory line keeps its user-selected routing, but the inventory command also
 * needs an active catalog node as the technical route reference. Resolve each line independently
 * so materials and works with different inherited routes cannot accidentally share a stage node.
 */
internal fun inventoryManualLineRoutingCatalogNodeIds(
    catalogNodes: Collection<CatalogNodeDto>,
    selectedRoutingByLineId: Map<String, RoutingSnapshotDto?>,
): Map<String, String> = selectedRoutingByLineId.mapValues { (_, selectedRouting) ->
    val routing = selectedRouting
        ?: throw IllegalArgumentException("Для пользовательской строки назначьте маршрут")
    if (!routing.isValidMaintenanceRouting()) {
        throw IllegalArgumentException("Для пользовательской строки назначьте корректный маршрут")
    }
    inventoryStageCatalogNodeId(catalogNodes, routing)
        ?: throw IllegalArgumentException("Для пользовательской строки не найден маршрут каталога")
}

private fun effectiveCatalogNodeRouting(
    nodeId: String,
    activeNodesById: Map<String, CatalogNodeDto>,
): RoutingSnapshotDto? {
    var current = activeNodesById[nodeId]
    val visited = mutableSetOf<String>()
    while (current != null) {
        if (!visited.add(current.id)) return null
        current.routing?.takeIf(RoutingSnapshotDto::isValidMaintenanceRouting)?.let { return it }
        current = current.parentNodeId?.let(activeNodesById::get)
    }
    return null
}

internal fun CatalogNodeDto.isAvailableForMaintenanceMode(
    mode: MaintenanceEditorMode,
    nodesById: Map<String, CatalogNodeDto>,
): Boolean {
    var current: CatalogNodeDto? = this
    val visited = mutableSetOf<String>()
    var belongsToFurnitureTree = false
    while (current != null && visited.add(current.id)) {
        if (current.furnitureCategory) {
            belongsToFurnitureTree = true
            break
        }
        current = current.parentNodeId?.let(nodesById::get)
    }
    if (!belongsToFurnitureTree) return true
    if (mode == MaintenanceEditorMode.REPAIR) return false
    return nodeType != "MATERIAL" || furnitureEquipment != null
}

private fun CatalogNodeDto.toCatalogSnapshot(): CatalogNodeSnapshotDto =
    CatalogNodeSnapshotDto(
        catalogVersionId = catalogVersionId,
        nodeId = id,
        nodeType = nodeType,
        name = name,
        unit = unit,
        unitPrice = unitPrice,
        durationMinutes = durationMinutes,
        routing = routing,
        furnitureEquipment = furnitureEquipment,
    )

private fun CatalogNodeDto.toNewMaintenanceLine(): MaintenanceLineEditorState =
    MaintenanceLineEditorState(
        id = UUID.randomUUID().toString(),
        catalogNodeId = id,
        description = name,
        lineType = catalogMaintenanceLineType(nodeType),
        unit = unit.orEmpty(),
        quantity = "1",
        unitPrice = unitPrice ?: "0.00",
        normativeMinutes = if (nodeType == "WORK") durationMinutes else 0,
        comment = "",
    )

private fun MaintenanceEditorState.hasSelectedCatalogWork(
    nodes: List<CatalogNodeDto>,
    lineId: String?,
): Boolean {
    if (lineId == null) return true
    val selectedWorkNodeIds = nodes
        .asSequence()
        .filter { node -> node.nodeType == "WORK" }
        .map(CatalogNodeDto::id)
        .toSet()
    return lines.any { line ->
        line.id == lineId &&
            line.lineType == "WORK" &&
            line.catalogNodeId in selectedWorkNodeIds
    }
}

internal fun applyMaintenanceCatalogNodes(
    editor: MaintenanceEditorState,
    nodes: List<CatalogNodeDto>,
    quantity: String,
    comment: String,
    existingWorkLineId: String? = null,
    photoUris: List<String> = emptyList(),
    mediaReferences: List<MediaReferenceDto> = emptyList(),
): MaintenanceEditorState {
    val nextLines = editor.lines.toMutableList()
    val selectedNodes = nodes.distinctBy(CatalogNodeDto::id)
    val selectedWorkNodes = selectedNodes
        .asSequence()
        .filter { node -> node.nodeType == "WORK" }
        .toList()
    val selectedWorkNodeIds = selectedWorkNodes.mapTo(mutableSetOf(), CatalogNodeDto::id)
    if ((photoUris.isNotEmpty() || mediaReferences.isNotEmpty()) && selectedWorkNodes.size != 1) {
        throw IllegalArgumentException(
            "Фото можно прикрепить только при выборе одной работы",
        )
    }
    val photoWorkNodeId = selectedWorkNodes.singleOrNull()?.id
    val existingWorkIndex = existingWorkLineId?.let { lineId ->
        nextLines.indexOfFirst { line -> line.id == lineId }
    } ?: -1
    if (existingWorkLineId != null) {
        require(existingWorkIndex >= 0) {
            "Выбранная работа больше не существует в документе"
        }
        val existing = nextLines[existingWorkIndex]
        require(existing.lineType == "WORK" && existing.catalogNodeId in selectedWorkNodeIds) {
            "Выбранная работа не соответствует позиции каталога"
        }
    }

    selectedNodes.forEach { node ->
        if (node.nodeType == "WORK") {
            val matchingExistingWorkIndex = existingWorkIndex.takeIf { index ->
                index >= 0 && nextLines[index].catalogNodeId == node.id
            }
            if (matchingExistingWorkIndex != null) {
                val existing = nextLines[matchingExistingWorkIndex]
                nextLines[matchingExistingWorkIndex] = existing.copy(
                    quantity = accumulatedMaintenanceQuantity(existing.quantity, quantity),
                    comment = mergeMaintenanceLineComments(existing.comment, comment),
                    photoUris = if (node.id == photoWorkNodeId) {
                        (existing.photoUris + photoUris).distinct()
                    } else {
                        existing.photoUris
                    },
                    mediaReferences = if (node.id == photoWorkNodeId) {
                        (existing.mediaReferences + mediaReferences)
                            .distinctBy(MediaReferenceDto::mediaId)
                    } else {
                        existing.mediaReferences
                    },
                ).normalizedMaintenanceAnnotations()
            } else {
                nextLines += node.toNewMaintenanceLine().copy(
                    quantity = quantity,
                    comment = comment,
                    photoUris = photoUris.takeIf { node.id == photoWorkNodeId }.orEmpty(),
                    mediaReferences = mediaReferences
                        .takeIf { node.id == photoWorkNodeId }
                        .orEmpty()
                        .distinctBy(MediaReferenceDto::mediaId),
                ).normalizedMaintenanceAnnotations()
            }
        } else {
            val existingMaterialIndex = nextLines.indexOfFirst { line ->
                line.catalogNodeId == node.id && line.lineType == "MATERIAL"
            }
            if (existingMaterialIndex >= 0) {
                val existing = nextLines[existingMaterialIndex]
                nextLines[existingMaterialIndex] = existing.copy(
                    quantity = accumulatedMaintenanceQuantity(existing.quantity, quantity),
                    comment = "",
                    mediaReferences = emptyList(),
                    photoUris = emptyList(),
                ).normalizedMaintenanceAnnotations()
            } else {
                nextLines += node.toNewMaintenanceLine().copy(
                    quantity = quantity,
                    comment = "",
                    mediaReferences = emptyList(),
                    photoUris = emptyList(),
                ).normalizedMaintenanceAnnotations()
            }
        }
    }
    return editor.copy(lines = nextLines)
}

internal fun EstimateLineDto.toMaintenanceLineEditor(
    customRouting: RoutingSnapshotDto? = null,
): MaintenanceLineEditorState {
    val canonicalLineType = lineType.also { type ->
        require(isMaintenanceLineType(type)) { "Сервис вернул неподдерживаемый тип строки" }
    }
    return MaintenanceLineEditorState(
        id = id,
        catalogNodeId = catalogSnapshot?.nodeId,
        description = description,
        lineType = canonicalLineType,
        unit = unit ?: catalogSnapshot?.unit.orEmpty(),
        quantity = quantity,
        unitPrice = unitPrice,
        normativeMinutes = if (canonicalLineType == "MATERIAL") 0 else normativeMinutes,
        comment = comment.orEmpty().takeIf { canonicalLineType == "WORK" }.orEmpty(),
        catalogSnapshot = catalogSnapshot,
        customRouting = if (catalogSnapshot == null) customRouting else null,
        mediaReferences = mediaReferences.takeIf { canonicalLineType == "WORK" }.orEmpty(),
        reworkDisposition = disposition,
        sourceRepairId = sourceRepairId,
        sourceLineId = sourceLineId,
        lineageRootLineId = lineageRootLineId,
        repeatSourceDescription = description.takeIf { disposition == "REPEAT" },
    ).normalizedMaintenanceAnnotations()
}

internal fun MaintenanceEditorState.toggleReworkCandidate(
    candidate: ReworkCandidateDto,
    newLineId: () -> String = { UUID.randomUUID().toString() },
): MaintenanceEditorState {
    require(repairKind == "REWORK") {
        "Переделка доступна только для доработки"
    }
    val existing = lines.firstOrNull { line ->
        line.reworkDisposition == "REPEAT" &&
            line.lineageRootLineId == candidate.lineageRootLineId
    }
    if (existing != null) {
        return copy(lines = lines.filterNot { it.id == existing.id })
    }
    val canonical = candidate.line.toMaintenanceLineEditor(
        customRouting = candidate.line.catalogSnapshot?.routing,
    )
    return copy(
        lines = lines + canonical.copy(
            id = newLineId(),
            reworkDisposition = "REPEAT",
            sourceRepairId = candidate.sourceRepairId,
            sourceLineId = candidate.sourceLineId,
            lineageRootLineId = candidate.lineageRootLineId,
            repeatSourceDescription = candidate.line.description,
        ),
    )
}

internal fun InventoryEditorState.toMaintenancePlanEditor(): MaintenanceEditorState =
    MaintenanceEditorState(
        mode = MaintenanceEditorMode.REPAIR,
        entityId = findingId,
        expectedVersion = finding?.findingRevision,
        readOnly = false,
        selectedAsset = null,
        dispatchDate = LocalDate.now().toString(),
        sourceParty = "Инвентаризация",
        lines = planLines,
        photoUris = emptyList(),
        readyMedia = emptyList(),
        priority = planPriority,
        movementToRepair = planMovementToRepair,
        logisticsPlanningMode = planLogisticsPlanningMode,
        logisticsScheduledDate = planLogisticsScheduledDate,
        step = 3,
        stages = planStages,
        repairKind = "PRIMARY",
    )

internal fun InventoryEditorState.withMaintenancePlanEditor(
    editor: MaintenanceEditorState,
): InventoryEditorState = copy(
    planLines = editor.lines,
    planStages = editor.stages,
    planPriority = editor.priority,
    planMovementToRepair = editor.movementToRepair,
    planLogisticsPlanningMode = editor.logisticsPlanningMode,
    planLogisticsScheduledDate = editor.logisticsScheduledDate,
)

private fun normalizeReworkEditorLines(
    editor: MaintenanceEditorState,
): MaintenanceEditorState {
    if (editor.repairKind != "REWORK") return editor
    return editor.copy(
        lines = editor.lines.map { line ->
            if (line.reworkDisposition == null) {
                line.copy(reworkDisposition = "ADDED")
            } else {
                line
            }
        },
    )
}

private fun PlanStageInputDto.toMaintenanceStageEditor(): MaintenanceStageEditorState =
    MaintenanceStageEditorState(
        id = id,
        kind = kind,
        routing = routing,
        includedLineIds = includedLineIds,
        primaryLineId = primaryLineId,
        // A null primary is the contract's material-only stage marker, so an old/stale stage
        // comment must not reappear in the Android editor.
        groupComment = groupComment.takeIf { primaryLineId != null }.orEmpty(),
        taskDeadline = taskDeadline,
        originalOrder = order,
    )

private fun RepairStageDto.toMaintenanceStageEditor(): MaintenanceStageEditorState =
    MaintenanceStageEditorState(
        id = id,
        kind = kind,
        routing = routing,
        includedLineIds = (workLines + materialLines).map(EstimateLineDto::id).distinct(),
        primaryLineId = primaryLineId,
        groupComment = groupComment.takeIf { workLines.isNotEmpty() }.orEmpty(),
        taskDeadline = taskDeadline,
        originalOrder = order,
    )

internal fun planMaintenanceStages(
    editor: MaintenanceEditorState,
    routingForLine: (MaintenanceLineEditorState) -> RoutingSnapshotDto?,
): List<PlanStageInputDto> {
    data class RoutedLine(
        val line: MaintenanceLineEditorState,
        val routing: RoutingSnapshotDto,
        val index: Int,
    )

    data class LineGroup(
        val key: String,
        val routing: RoutingSnapshotDto,
        val lines: MutableList<RoutedLine> = mutableListOf(),
    )

    data class PlannedStage(
        val originalOrder: Int,
        val generatedOrder: Int,
        val stage: PlanStageInputDto,
    )

    val routedLines = editor.lines.mapIndexed { index, line ->
        line.effectiveLineType()
        val routing = routingForLine(line)
            ?: throw IllegalArgumentException("Для каждой строки назначьте маршрут")
        if (!routing.isValidMaintenanceRouting()) {
            throw IllegalArgumentException("Для каждой строки назначьте корректный маршрут")
        }
        RoutedLine(line = line, routing = routing, index = index)
    }
    val groupsByKey = linkedMapOf<String, LineGroup>()
    val groupKeyByWorkLineId = mutableMapOf<String, String>()

    fun groupFor(key: String, routing: RoutingSnapshotDto): LineGroup =
        groupsByKey.getOrPut(key) {
            LineGroup(key = key, routing = routing)
    }

    routedLines.filter { routed -> routed.line.lineType == "WORK" }.forEach { routed ->
        val key = "work:${routed.line.id}"
        groupFor(key, routed.routing).lines += routed
        groupKeyByWorkLineId[routed.line.id] = key
    }

    val previousWorkStages = editor.stages
        .filter { it.kind == "REPAIR_WORK" }
        .toMutableList()

    routedLines.filter { routed -> routed.line.lineType == "MATERIAL" }.forEach { material ->
        val routeKey = material.routing.routingKey()
        val groupFromExistingStage = editor.stages
            .firstOrNull { stage ->
                stage.kind == "REPAIR_WORK" && material.line.id in stage.includedLineIds
            }
            ?.primaryLineId
            ?.let(groupKeyByWorkLineId::get)
            ?.let(groupsByKey::get)
            ?.takeIf { group -> group.routing.routingKey() == routeKey }
        val groupsForRoute = groupsByKey.values.filter { group ->
            group.routing.routingKey() == routeKey
        }
        val routeGroup = groupsByKey["route:$routeKey"]
        val precedingWorkGroup = groupsForRoute
            .asSequence()
            .filter { group -> group.lines.any { it.line.lineType == "WORK" } }
            .mapNotNull { group ->
                group.lines
                    .asSequence()
                    .filter { line -> line.line.lineType == "WORK" && line.index < material.index }
                    .maxByOrNull(RoutedLine::index)
                    ?.let { precedingLine -> group to precedingLine.index }
            }
            .maxByOrNull { (_, lineIndex) -> lineIndex }
            ?.first
        val target = groupFromExistingStage
            ?: routeGroup
            ?: precedingWorkGroup
            ?: groupsForRoute.singleOrNull()
            ?: groupFor("route:$routeKey", material.routing)
        target.lines += material
    }

    val planned = groupsByKey.values.mapIndexed { generatedOrder, group ->
        val groupedLines = group.lines.sortedBy(RoutedLine::index)
        val includedLineIds = groupedLines.map { routed -> routed.line.id }
        val primaryLineId = groupedLines
            .firstOrNull { routed -> routed.line.lineType == "WORK" }
            ?.line
            ?.id
        val previousIndex = previousWorkStages.indexOfFirst { stage ->
            stage.routing.routingKey() == group.routing.routingKey() &&
                if (primaryLineId != null) {
                    stage.primaryLineId == primaryLineId
                } else {
                    stage.primaryLineId == null && stage.includedLineIds == includedLineIds
                }
        }
        val previous = if (previousIndex >= 0) {
            previousWorkStages.removeAt(previousIndex)
        } else {
            null
        }
        PlannedStage(
            originalOrder = previous?.originalOrder ?: Int.MAX_VALUE,
            generatedOrder = generatedOrder,
            stage = PlanStageInputDto(
                id = previous?.id ?: UUID.randomUUID().toString(),
                kind = "REPAIR_WORK",
                order = 0,
                routing = group.routing,
                includedLineIds = includedLineIds,
                primaryLineId = primaryLineId,
                groupComment = groupedLines
                    .map(RoutedLine::line)
                    .commentsForMaintenanceStage()
                    .ifBlank {
                        previous?.groupComment
                            ?.takeIf { primaryLineId != null }
                            .orEmpty()
                    },
                taskDeadline = previous?.taskDeadline,
            ),
        )
    }

    val orderedWorkStages = planned
        .sortedWith(
            compareBy<PlannedStage>(PlannedStage::originalOrder)
                .thenBy(PlannedStage::generatedOrder),
        )
        .map(PlannedStage::stage)
    return orderedWorkStages.mapIndexed { index, stage -> stage.copy(order = index) }
}

private fun MaintenanceLineEditorState.effectiveLineType(): String =
    lineType.also { type ->
        require(isMaintenanceLineType(type)) { "Тип строки должен быть работой или материалом" }
    }

private fun RoutingSnapshotDto?.routingKey(): String = this?.let { routing ->
    "${routing.queueId.trim()}:${routing.queueName.trim()}:${routing.queueType.trim()}"
} ?: "UNBOUND"

private fun RoutingSnapshotDto.isValidMaintenanceRouting(): Boolean =
    queueId.isNotBlank() && queueName.isNotBlank() && queueType.isNotBlank()

private fun List<MaintenanceLineEditorState>.commentsForMaintenanceStage(): String =
    asSequence()
        .filter { line -> line.lineType == "WORK" }
        .map { it.comment.trim() }
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString("; ")

private fun mergeMaintenanceLineComments(existing: String, added: String): String =
    sequenceOf(existing, added)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString("; ")

private fun accumulatedMaintenanceQuantity(existing: String, added: String): String =
    BigDecimal(existing)
        .add(BigDecimal(added))
        .stripTrailingZeros()
        .toPlainString()

private fun canonicalMaintenanceQuantity(value: String): String {
    val normalized = value.trim().replace(',', '.')
    if (!MAINTENANCE_QUANTITY_PATTERN.matches(normalized)) {
        throw IllegalArgumentException("Количество должно быть положительным числом до трёх знаков")
    }
    val quantity = BigDecimal(normalized)
    if (quantity <= BigDecimal.ZERO) {
        throw IllegalArgumentException("Количество должно быть больше нуля")
    }
    return quantity.stripTrailingZeros().toPlainString()
}

private fun canonicalMaintenanceMoney(value: String): String {
    val normalized = value.trim().replace(',', '.')
    if (!MAINTENANCE_MONEY_PATTERN.matches(normalized)) {
        throw IllegalArgumentException("Цена должна быть неотрицательным числом с двумя знаками")
    }
    val money = BigDecimal(normalized)
    if (money < BigDecimal.ZERO) {
        throw IllegalArgumentException("Цена не может быть отрицательной")
    }
    return money.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
}

private const val SERVER_CONNECTIVITY_CHECK_MILLIS = 30_000L
private const val MAINTENANCE_AMENDMENT_REASON = "Изменение работ до начала ремонта"
private val PRE_START_REPAIR_STATES = setOf("DRAFT", "QUEUED")
private val MAINTENANCE_QUANTITY_PATTERN = Regex("^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?$")
private val MAINTENANCE_MONEY_PATTERN = Regex("^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?$")

private val MANAGER_ROLES =
    setOf("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER")
