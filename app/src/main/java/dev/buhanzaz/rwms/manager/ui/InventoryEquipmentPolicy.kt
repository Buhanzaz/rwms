package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.ObservationInput

/**
 * The inventory service owns an observation, rather than a mutable cabin-equipment command.
 * ABSENT means the inspector did not assert a new snapshot; EXPLICIT_EMPTY is an intentional
 * empty snapshot; PRESENT carries only the positive quantities observed in this inspection.
 *
 * The follow-up reconciliation is deliberately limited to furniture: it uses the existing
 * logistics furniture-task contract, which owns the physical balance movement.
 */
internal fun List<EquipmentCatalogItemDto>.inventoryFurnitureCatalog(): List<EquipmentCatalogItemDto> =
    asSequence()
        .filter { it.active && it.category == "FURNITURE" }
        .sortedWith(
            Comparator { left, right ->
                String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name)
                    .takeIf { it != 0 }
                    ?: left.id.compareTo(right.id)
            },
        )
        .toList()

internal fun InventoryEditorState.inventoryEquipmentQuantityText(equipmentId: String): String =
    equipmentQuantities[equipmentId] ?: "0"

internal fun InventoryEditorState.inventoryEquipmentObservationValidationError(): String? {
    val requested = equipmentObservationRequested
        ?: return "Укажите, есть ли в бытовке мебель"
    if (!requested) return null

    val catalog = equipmentCatalog.inventoryFurnitureCatalog()
    if (catalog.isEmpty()) {
        return "В панели нет активных позиций мебели. Обновите каталог и откройте проверку заново"
    }
    val catalogIds = catalog.mapTo(mutableSetOf(), EquipmentCatalogItemDto::id)
    if (equipmentQuantities.keys.any { it !in catalogIds }) {
        return "Состав оборудования изменился. Откройте проверку заново"
    }
    for (equipment in catalog) {
        val value = inventoryEquipmentQuantityText(equipment.id)
        if (value.isBlank()) {
            return "Укажите количество для «${equipment.name}»"
        }
        val quantity = value.toLongOrNull()
            ?: return "Количество для «${equipment.name}» должно быть целым числом"
        if (quantity < 0) {
            return "Количество для «${equipment.name}» не может быть отрицательным"
        }
    }
    return null
}

internal fun InventoryEditorState.inventoryEquipmentObservation(): ObservationInput {
    inventoryEquipmentObservationValidationError()?.let { error ->
        throw IllegalArgumentException(error)
    }
    if (equipmentObservationRequested == false) {
        return ObservationInput("ABSENT", null)
    }

    val observed = equipmentCatalog.inventoryFurnitureCatalog().mapNotNull { equipment ->
        val quantity = requireNotNull(inventoryEquipmentQuantityText(equipment.id).toLongOrNull())
        if (quantity == 0L) {
            null
        } else {
            linkedMapOf<String, Any?>(
                "equipmentId" to equipment.id,
                "equipmentName" to equipment.name,
                "equipmentCategory" to equipment.category,
                "catalogVersion" to equipment.version,
                "quantity" to quantity,
            )
        }
    }
    return if (observed.isEmpty()) {
        ObservationInput("EXPLICIT_EMPTY", emptyList<Map<String, Any?>>())
    } else {
        ObservationInput("PRESENT", observed)
    }
}

/**
 * The desired total is sent only to logistics, after inventory has durably recorded the
 * observation.  Asset balances are therefore changed by the resulting worker task rather than
 * being overwritten by the inspection itself.
 */
internal fun InventoryEditorState.inventoryFurnitureDesiredContents():
    List<CabinFurnitureRequirementDto>? {
    inventoryEquipmentObservationValidationError()?.let { error ->
        throw IllegalArgumentException(error)
    }
    if (equipmentObservationRequested != true) return null
    return equipmentCatalog.inventoryFurnitureCatalog().mapNotNull { equipment ->
        val quantity = requireNotNull(inventoryEquipmentQuantityText(equipment.id).toLongOrNull())
        if (quantity > 0L) CabinFurnitureRequirementDto(equipment.id, quantity) else null
    }
}

/**
 * Hydrates the quantity controls from the current asset snapshot so the user changes the actual
 * furniture composition instead of starting every known position at zero.
 */
internal fun InventoryFindingDto.inventoryFurnitureInitialQuantities(
    furnitureCatalog: List<EquipmentCatalogItemDto>,
): Map<String, String> {
    val catalogIds = furnitureCatalog.mapTo(mutableSetOf(), EquipmentCatalogItemDto::id)
    val quantities = linkedMapOf<String, Long>()
    currentSnapshot?.contentsSnapshot.orEmpty().forEach { raw ->
        val content = raw as? Map<*, *> ?: return@forEach
        val equipmentId = content["equipmentId"] as? String ?: return@forEach
        if (equipmentId !in catalogIds) return@forEach
        val quantity = when (val rawQuantity = content["quantity"]) {
            is Number -> rawQuantity.toLong()
            is String -> rawQuantity.toLongOrNull()
            else -> null
        } ?: throw IllegalStateException("RWMS вернул некорректное количество мебели")
        if (quantity < 0L) {
            throw IllegalStateException("RWMS вернул отрицательное количество мебели")
        }
        quantities[equipmentId] = Math.addExact(quantities[equipmentId] ?: 0L, quantity)
    }
    return furnitureCatalog.associate { equipment ->
        equipment.id to (quantities[equipment.id] ?: 0L).toString()
    }
}

/** A zero-only observation is an explicit empty composition, not a furniture disposition. */
internal fun InventoryEditorState.hasObservedFurniture(): Boolean =
    equipmentCatalog.inventoryFurnitureCatalog().any { equipment ->
        inventoryEquipmentQuantityText(equipment.id).trim().toLongOrNull()?.let { quantity ->
            quantity > 0L
        } == true
    }
