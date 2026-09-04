package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ShipmentFurnitureReadinessDto
import dev.buhanzaz.rwms.manager.network.TransferFurnitureReadinessDto

internal fun LogisticsDocumentDto.needsShipmentFurnitureReadiness(): Boolean =
    (rentalOrderId != null || lines.any { it.rentalOrderId != null }) &&
        (state == "DRAFT" || state == "AWAITING_CONFIRMATION")

/**
 * Furniture blocks the initial departure plan. Once the shipment is awaiting confirmation,
 * changing only its driver/date must remain possible, matching the panel workflow.
 */
internal fun LogisticsDocumentDto.shipmentPlanRequiresFurnitureReadiness(): Boolean =
    state == "DRAFT" && needsShipmentFurnitureReadiness()

internal fun shipmentFurnitureIsReady(
    document: LogisticsDocumentDto,
    readiness: ShipmentFurnitureReadinessDto?,
): Boolean =
    !document.needsShipmentFurnitureReadiness() ||
        readiness?.state == "NOT_REQUIRED" ||
        readiness?.state == "READY"

internal fun LogisticsDocumentDto.needsTransferFurnitureReadiness(): Boolean =
    (state == "DRAFT" || state == "DEPARTING") &&
        (lines.isEmpty() || lines.any { it.state == "PENDING" })

internal fun transferFurnitureIsReady(
    document: LogisticsDocumentDto,
    readiness: TransferFurnitureReadinessDto?,
): Boolean =
    !document.needsTransferFurnitureReadiness() ||
        readiness?.state == "NOT_REQUIRED" ||
        readiness?.state == "READY"

/** Identifies the only whole-document command allowed for a zero-cabin transfer screen. */
internal enum class WholeTransferCommand {
    DEPART,
    ARRIVE,
}

/** Presents one server-authoritative zero-cabin transfer command without duplicating its lifecycle. */
internal data class WholeTransferAction(
    val command: WholeTransferCommand,
    val label: String,
    val enabled: Boolean,
    val blockingMessage: String?,
)

/**
 * Resolves the single valid whole-document action for a zero-cabin transfer projection. All
 * transitions and final validation remain owned by logistics-service.
 */
internal fun wholeTransferAction(
    document: LogisticsDocumentDto,
    canManage: Boolean,
    busy: Boolean,
    furnitureReady: Boolean,
): WholeTransferAction? {
    if (!canManage || document.documentType != "TRANSFER" || document.lines.isNotEmpty()) {
        return null
    }
    return when (document.state) {
        "DRAFT" -> WholeTransferAction(
            command = WholeTransferCommand.DEPART,
            label = "Начать перемещение",
            enabled = !busy && furnitureReady,
            blockingMessage = if (!busy && !furnitureReady) {
                "Сначала завершите задания по мебели."
            } else {
                null
            },
        )

        "IN_TRANSIT" -> WholeTransferAction(
            command = WholeTransferCommand.ARRIVE,
            label = "Подтвердить прибытие",
            enabled = !busy,
            blockingMessage = null,
        )

        else -> null
    }
}

internal fun ManagerUiState.withLoadedReturnReadyMedia(
    documentId: String,
    readyMedia: Map<String, List<MediaReferenceDto>>,
): ManagerUiState =
    if (selectedReturn?.id == documentId) {
        copy(returnReadyMedia = readyMedia)
    } else {
        this
    }
