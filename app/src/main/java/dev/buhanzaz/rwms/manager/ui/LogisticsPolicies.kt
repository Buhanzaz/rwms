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
        lines.any { it.state == "PENDING" }

internal fun transferFurnitureIsReady(
    document: LogisticsDocumentDto,
    readiness: TransferFurnitureReadinessDto?,
): Boolean =
    !document.needsTransferFurnitureReadiness() ||
        readiness?.state == "NOT_REQUIRED" ||
        readiness?.state == "READY"

internal fun ManagerUiState.withLoadedReturnReadyMedia(
    documentId: String,
    readyMedia: Map<String, List<MediaReferenceDto>>,
): ManagerUiState =
    if (selectedReturn?.id == documentId) {
        copy(returnReadyMedia = readyMedia)
    } else {
        this
    }
