package dev.buhanzaz.rwms.manager.uploads

import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto
import dev.buhanzaz.rwms.manager.network.EstimateLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationInput
import dev.buhanzaz.rwms.manager.network.PlanStageInputDto
import dev.buhanzaz.rwms.manager.network.PriorityVersionRequest
import java.util.concurrent.atomic.AtomicReference

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
enum class BackgroundUploadArea(val title: String) {
    INVENTORY("Инвентаризация"),
    MAINTENANCE("Ремонтный цикл"),
    LOGISTICS("Логистика"),
    ACCEPTANCE("Приёмка"),
}

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
enum class BackgroundUploadStatus {
    QUEUED,
    RUNNING,
    FAILED,
}

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
enum class BackgroundPhotoStatus {
    QUEUED,
    UPLOADING,
    READY,
    FAILED,
}

/**
 * Identifies the verified manager principal and selected warehouse that exclusively own one
 * durable upload. The pair is copied into the queue row, file path and WorkManager request; it
 * is never inferred from a later OAuth session.
 */
data class BackgroundUploadScope(
    val ownerAccountId: String,
    val warehouseId: String,
) {
    init {
        require(ownerAccountId.isNotBlank()) { "Не указан владелец фоновой загрузки" }
        require(warehouseId.isNotBlank()) { "Не указан склад фоновой загрузки" }
    }
}

/**
 * Process-local capture boundary for transient drafts. The coordinator is its sole lifecycle
 * writer; taking the snapshot in the draft constructor prevents a command started by account A
 * from being rebound to account B if logout completes before enqueue is reached.
 */
internal object BackgroundUploadDraftScopeRegistry {
    private val activeScope = AtomicReference<BackgroundUploadScope?>(null)

    /** Captures one immutable identity pair for a newly constructed draft. */
    fun snapshot(): BackgroundUploadScope? = activeScope.get()

    /** Replaces or closes draft admission as part of the coordinator's serialized lifecycle. */
    fun replace(scope: BackgroundUploadScope?) {
        activeScope.set(scope)
    }
}

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class BackgroundUploadPhoto(
    val id: String,
    val sourceName: String,
    val durableUri: String,
    val owner: MediaOwner,
    val sortOrder: Int,
    val cover: Boolean = false,
    /** Maintenance/inventory editor line identity; null means aggregate photo. */
    val lineId: String? = null,
    val status: BackgroundPhotoStatus = BackgroundPhotoStatus.QUEUED,
    val reference: MediaReferenceDto? = null,
    val error: String? = null,
)

/**
 * Durable, immutable command input owned by exactly one verified account and warehouse.
 * Ownerless instances can only be decoded from legacy/test data and are rejected by the store.
 */
data class BackgroundUploadOperation(
    val id: String,
    val area: BackgroundUploadArea,
    val title: String,
    val subtitle: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: BackgroundUploadStatus = BackgroundUploadStatus.QUEUED,
    val stage: String = "Ожидание сети",
    val error: String? = null,
    val photos: List<BackgroundUploadPhoto> = emptyList(),
    val inventory: InventoryUploadCommand? = null,
    val maintenance: MaintenanceUploadCommand? = null,
    val acceptance: AcceptanceUploadCommand? = null,
    val transferArrival: TransferArrivalUploadCommand? = null,
    val returnAction: ReturnUploadCommand? = null,
    val ownerAccountId: String = "",
    val warehouseId: String = "",
) {
    init {
        require(
            listOfNotNull(
                inventory,
                maintenance,
                acceptance,
                transferArrival,
                returnAction,
            ).size == 1,
        ) { "Фоновая операция должна содержать ровно одну итоговую команду" }
        require(ownerAccountId.isBlank() == warehouseId.isBlank()) {
            "Владелец и склад фоновой загрузки должны задаваться вместе"
        }
    }

    val readyPhotoCount: Int
        get() = photos.count { it.reference != null && it.status == BackgroundPhotoStatus.READY }

    val failedPhotoCount: Int
        get() = photos.count { it.status == BackgroundPhotoStatus.FAILED }
}

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class PendingBackgroundPhoto(
    val uri: String,
    val owner: MediaOwner,
    val sortOrder: Int,
    val cover: Boolean = false,
    val lineId: String? = null,
)

/**
 * UI-produced upload input that captures the currently verified scope at construction time. The
 * coordinator rejects an absent or stale snapshot before any file or queue write, so a command
 * coroutine cannot cross an A-to-B logout/login transition while it is preparing the draft.
 */
data class BackgroundUploadDraft(
    val area: BackgroundUploadArea,
    val title: String,
    val subtitle: String? = null,
    val photos: List<PendingBackgroundPhoto> = emptyList(),
    val inventory: InventoryUploadCommand? = null,
    val maintenance: MaintenanceUploadCommand? = null,
    val acceptance: AcceptanceUploadCommand? = null,
    val transferArrival: TransferArrivalUploadCommand? = null,
    val returnAction: ReturnUploadCommand? = null,
    val scope: BackgroundUploadScope = requireNotNull(
        BackgroundUploadDraftScopeRegistry.snapshot(),
    ) { "Черновик недоступен до проверки учётной записи и склада" },
) {
    init {
        require(
            listOfNotNull(
                inventory,
                maintenance,
                acceptance,
                transferArrival,
                returnAction,
            ).size == 1,
        ) { "Фоновая операция должна содержать ровно одну итоговую команду" }
    }

    val ownerAccountId: String
        get() = scope.ownerAccountId

    val warehouseId: String
        get() = scope.warehouseId
}

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class InventoryUploadCommand(
    val inventoryId: String,
    val findingId: String,
    val expectedFindingRevision: Long,
    val inspection: String,
    val comment: String,
    val passportObservation: ObservationInput,
    val equipmentObservation: ObservationInput,
    val existingMedia: List<MediaReferenceDto> = emptyList(),
    val existingCoverMediaId: String? = null,
    val planSelection: InventoryPlanSelectionDto? = null,
    val planLineIds: List<String> = emptyList(),
    /** Line ids whose contract permits source media. Kept with the outbox command as a final guard. */
    val planWorkLineIds: List<String> = emptyList(),
    val furnitureMove: InventoryFurnitureUploadCommand? = null,
    val inspectionSaved: Boolean = false,
)

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class InventoryFurnitureUploadCommand(
    val rentalItemId: String,
    val warehouseId: String,
    val scheduledDate: String,
    val idempotencyKey: String,
    val desiredContents: List<CabinFurnitureRequirementDto> = emptyList(),
)

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
enum class MaintenanceReplaceKind {
    NONE,
    ESTIMATE,
    ESTIMATE_AMENDMENT,
    REPAIR,
}

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class MaintenanceUploadCommand(
    val mode: String,
    val entityId: String,
    val warehouseId: String,
    val expectedVersion: Long,
    val dispatchDate: String,
    val sourceParty: String? = null,
    val lines: List<EstimateLineInputDto>,
    val stages: List<PlanStageInputDto>,
    val existingMedia: List<MediaReferenceDto> = emptyList(),
    val existingCoverMediaId: String? = null,
    val forceCapitalRepair: Boolean = false,
    val replaceKind: MaintenanceReplaceKind,
    val expectedLinkedRepairVersion: Long? = null,
    val amendmentIdempotencyKey: String? = null,
    val amendmentReason: String? = null,
    val submitRequest: PriorityVersionRequest? = null,
    val submitIdempotencyKey: String? = null,
    /** Server requests this only after an explicit user confirmation for an unrecorded cabin. */
    val allowUnaccountedFurniture: Boolean = false,
    /** A 422 preflight requires the operator to confirm the accounting exception before retry. */
    val requiresUnaccountedFurnitureConfirmation: Boolean = false,
)

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class AcceptanceUploadCommand(
    val repairId: String,
    val warehouseId: String,
    val expectedVersion: Long,
    val comment: String? = null,
    val existingMedia: List<MediaReferenceDto> = emptyList(),
    val idempotencyKey: String,
)

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class TransferArrivalUploadCommand(
    val documentId: String,
    val lineId: String,
    val expectedDocumentVersion: Long,
    val expectedLineVersion: Long,
    val priority: Int?,
    val existingMedia: List<MediaReferenceDto> = emptyList(),
    val idempotencyKey: String,
)

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
enum class ReturnUploadAction {
    ACCEPT,
    START_ESTIMATES,
    /**
     * Kept only to recover durable commands written by an older app. The worker sends them to
     * the replacement start-estimates endpoint and deliberately ignores their removed shortages.
     */
    @Deprecated("Use START_ESTIMATES")
    REQUEST_ESTIMATE,
}

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class ReturnUploadLineCommand(
    val lineId: String,
    val equipmentConfirmed: Boolean = true,
    val existingMedia: List<MediaReferenceDto> = emptyList(),
)

/**
 * Represents manager durable background-upload recovery state; it must be reconciled with the
 * authoritative server result.
 */
data class ReturnUploadCommand(
    val documentId: String,
    val warehouseId: String,
    val expectedVersion: Long,
    val action: ReturnUploadAction,
    val lines: List<ReturnUploadLineCommand>,
    val idempotencyKey: String,
)

/** Versioned queue envelope; schema 1 had no immutable account or warehouse owner. */
internal data class BackgroundUploadStoreDocument(
    val schemaVersion: Int = 1,
    val operations: List<BackgroundUploadOperation> = emptyList(),
)

internal const val CURRENT_BACKGROUND_UPLOAD_SCHEMA_VERSION = 2

internal fun InventoryPlanSelectionDto.withUploadedMedia(
    coverMediaId: String?,
    lineMedia: Map<String, List<MediaReferenceDto>>,
    lineIds: List<String>,
    workLineIds: Set<String>,
): InventoryPlanSelectionDto = copy(
    coverMediaId = coverMediaId,
    lines = lines.mapIndexed { index, line ->
        val lineId = lineIds.getOrNull(index)
        line.copy(
            mediaReferences = when {
                lineId != null && lineId !in workLineIds -> emptyList()
                lineId != null -> (
                    line.mediaReferences + lineMedia[lineId].orEmpty()
                    ).distinctBy(MediaReferenceDto::mediaId)
                else -> line.mediaReferences.distinctBy(MediaReferenceDto::mediaId)
            },
        )
    },
)
