package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.InventoryFindingDto

/**
 * The existing save-inspection command replaces the active finding revision.  The mode only
 * decides which parts of that revision are offered as the starting point to the inspector.
 */
enum class InventoryReinspectionMode {
    SUPPLEMENT,
    REPLACE,
}

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class InventoryReinspectionSeed(
    val passport: Map<String, Any?>,
    val comment: String,
    val retainPreviousInspection: Boolean,
)

internal fun InventoryFindingDto.requiresInventoryReinspectionChoice(): Boolean =
    inspection != "NOT_INSPECTED"

internal fun InventoryFindingDto.inventoryReinspectionSeed(
    mode: InventoryReinspectionMode,
): InventoryReinspectionSeed = when (mode) {
    InventoryReinspectionMode.SUPPLEMENT -> InventoryReinspectionSeed(
        passport = inspectionPassport(),
        comment = comment,
        retainPreviousInspection = true,
    )

    InventoryReinspectionMode.REPLACE -> InventoryReinspectionSeed(
        // A full repeat must not silently carry forward facts entered in the prior inspection.
        // The current registry passport remains useful as the neutral starting reference.
        passport = currentSnapshot?.passportSnapshot
            ?: inspectionBaseline?.passportSnapshot
            ?: expectedSnapshot?.passportSnapshot
            ?: emptyMap(),
        comment = "",
        retainPreviousInspection = false,
    )
}

/** Old evidence stays in audit history, but is excluded from the next active inspection revision. */
internal fun InventoryFindingDto.inventoryReinspectionRemovedMediaIds(
    mode: InventoryReinspectionMode,
): Set<String> = if (mode == InventoryReinspectionMode.REPLACE) {
    media.mapTo(linkedSetOf()) { reference -> reference.mediaId }
} else {
    emptySet()
}
