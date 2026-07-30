package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
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

enum class ReturnEquipmentCatalogStatus {
    NOT_LOADED,
    LOADING,
    AVAILABLE,
    EMPTY,
    UNAVAILABLE,
}

internal fun List<EquipmentCatalogItemDto>.returnShortageEquipmentCatalog():
    List<EquipmentCatalogItemDto> =
    filter(EquipmentCatalogItemDto::active)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EquipmentCatalogItemDto::name))

internal fun List<EquipmentCatalogItemDto>.selectedReturnShortageEquipment(
    equipmentId: String?,
): EquipmentCatalogItemDto? = equipmentId?.let { selectedId ->
    firstOrNull { equipment -> equipment.id == selectedId }
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

internal fun ManagerUiState.withLoadedReturnEquipmentCatalog(
    documentId: String,
    equipment: List<EquipmentCatalogItemDto>,
): ManagerUiState =
    if (selectedReturn?.id == documentId) {
        val activeEquipment = equipment.returnShortageEquipmentCatalog()
        copy(
            returnEquipmentCatalog = activeEquipment,
            returnEquipmentCatalogStatus = if (activeEquipment.isEmpty()) {
                ReturnEquipmentCatalogStatus.EMPTY
            } else {
                ReturnEquipmentCatalogStatus.AVAILABLE
            },
        )
    } else {
        this
    }

internal fun ManagerUiState.withUnavailableReturnEquipmentCatalog(
    documentId: String,
): ManagerUiState =
    if (selectedReturn?.id == documentId) {
        copy(
            returnEquipmentCatalog = emptyList(),
            returnEquipmentCatalogStatus = ReturnEquipmentCatalogStatus.UNAVAILABLE,
            returnShortageEquipment = emptyMap(),
            returnShortageQuantity = emptyMap(),
        )
    } else {
        this
    }
