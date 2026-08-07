package dev.buhanzaz.rwms.manager.uploads

import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto
import dev.buhanzaz.rwms.manager.network.EstimateLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationInput
import dev.buhanzaz.rwms.manager.network.PlanStageInputDto
import dev.buhanzaz.rwms.manager.network.PriorityVersionRequest

enum class BackgroundUploadArea(val title: String) {
    INVENTORY("Инвентаризация"),
    MAINTENANCE("Ремонтный цикл"),
    LOGISTICS("Логистика"),
    ACCEPTANCE("Приёмка"),
}

enum class BackgroundUploadStatus {
    QUEUED,
    RUNNING,
    FAILED,
}

enum class BackgroundPhotoStatus {
    QUEUED,
    UPLOADING,
    READY,
    FAILED,
}

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

    val readyPhotoCount: Int
        get() = photos.count { it.reference != null && it.status == BackgroundPhotoStatus.READY }

    val failedPhotoCount: Int
        get() = photos.count { it.status == BackgroundPhotoStatus.FAILED }
}

data class PendingBackgroundPhoto(
    val uri: String,
    val owner: MediaOwner,
    val sortOrder: Int,
    val cover: Boolean = false,
    val lineId: String? = null,
)

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
}

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

data class InventoryFurnitureUploadCommand(
    val rentalItemId: String,
    val warehouseId: String,
    val scheduledDate: String,
    val idempotencyKey: String,
    val desiredContents: List<CabinFurnitureRequirementDto> = emptyList(),
)

enum class MaintenanceReplaceKind {
    NONE,
    ESTIMATE,
    ESTIMATE_AMENDMENT,
    REPAIR,
}

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

data class AcceptanceUploadCommand(
    val repairId: String,
    val warehouseId: String,
    val expectedVersion: Long,
    val comment: String? = null,
    val existingMedia: List<MediaReferenceDto> = emptyList(),
    val idempotencyKey: String,
)

data class TransferArrivalUploadCommand(
    val documentId: String,
    val lineId: String,
    val expectedDocumentVersion: Long,
    val expectedLineVersion: Long,
    val existingMedia: List<MediaReferenceDto> = emptyList(),
    val idempotencyKey: String,
)

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

data class ReturnUploadLineCommand(
    val lineId: String,
    val equipmentConfirmed: Boolean = true,
    val existingMedia: List<MediaReferenceDto> = emptyList(),
)

data class ReturnUploadCommand(
    val documentId: String,
    val warehouseId: String,
    val expectedVersion: Long,
    val action: ReturnUploadAction,
    val lines: List<ReturnUploadLineCommand>,
    val idempotencyKey: String,
)

internal data class BackgroundUploadStoreDocument(
    val schemaVersion: Int = 1,
    val operations: List<BackgroundUploadOperation> = emptyList(),
)

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
