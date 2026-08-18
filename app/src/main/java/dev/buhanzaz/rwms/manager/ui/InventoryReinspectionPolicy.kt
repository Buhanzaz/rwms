package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.InventoryFindingDto

/**
 * Selects a local inspection presentation or editing seed without changing server semantics.
 * Review is read-only, supplement retains prior input, and replacement starts a clean revision.
 */
enum class InventoryReinspectionMode {
    REVIEW,
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

/** Selects review only when the finding already contains a saved inspection. */
internal fun InventoryFindingDto.inventoryReinspectionOpenMode(): InventoryReinspectionMode =
    if (requiresInventoryReinspectionChoice()) {
        InventoryReinspectionMode.REVIEW
    } else {
        InventoryReinspectionMode.SUPPLEMENT
    }

internal fun InventoryFindingDto.inventoryReinspectionSeed(
    mode: InventoryReinspectionMode,
): InventoryReinspectionSeed = when (mode) {
    InventoryReinspectionMode.REVIEW,
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

/** Leaves the retained review draft on its current screen and enables local editing. */
internal fun InventoryEditorState.beginInventorySupplement(): InventoryEditorState =
    copy(readOnly = false)
