package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.TaskBoardColumnDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.pow

internal const val DIRECT_REPAIR_SOURCE_PARTY = "app-приложение"
/**
 * Default repair priority. The same value is propagated to an optional inbound or outbound
 * logistics task, while the server assigns repair-board priority 1 after inbound delivery.
 */
internal const val DEFAULT_MAINTENANCE_PRIORITY = 3
internal val MAINTENANCE_CATALOG_SYNC_TIME: LocalTime = LocalTime.of(9, 0)
internal const val LOGISTICS_PLANNING_MODE_AUTO = "AUTO"
internal const val LOGISTICS_PLANNING_MODE_FIXED_DATE = "FIXED_DATE"

/**
 * Kept in lockstep with the public asset contract. Estimate selection is deliberately
 * restrictive: AFTER_RENT is the only eligible state.
 */
internal val maintenanceKnownRentalItemStatuses = listOf(
    "RENTED",
    "BOOKED",
    "REPAIR",
    "WAITING_REPAIR_CHECK",
    "WRITTEN_OFF",
    "LOST",
    "CAPITAL_REPAIR",
    "AFTER_RENT",
    "WAITING_ESTIMATE_CONFIRMATION",
    "SALE",
    "USED_SALE",
    "RESERVED",
    "FREE",
    "WAREHOUSE",
    "OWN_NEEDS",
    "IN_TRANSFER",
)

internal fun maintenanceExcludedRentalItemStatuses(
    mode: MaintenanceEditorMode,
): List<String> = when (mode) {
    MaintenanceEditorMode.REPAIR -> listOf("RENTED", "AFTER_RENT")
    MaintenanceEditorMode.ESTIMATE -> maintenanceKnownRentalItemStatuses
        .filterNot { status -> status == "AFTER_RENT" }
}

internal fun isMaintenanceLineType(value: String): Boolean =
    value == "WORK" || value == "MATERIAL"

internal fun repairSourceLabel(origin: String, sourceParty: String?): String =
    sourceParty?.trim()?.takeIf(String::isNotEmpty)
        ?: if (origin == "INVENTORY") "Инвентаризация" else "—"

/** An empty estimate or direct repair is a valid outcome that releases the cabin to FREE. */
internal fun isEmptyMaintenanceOutcome(editor: MaintenanceEditorState): Boolean =
    editor.lines.isEmpty() && editor.repairKind != "REWORK"

internal fun isEmptyMaintenanceEstimate(editor: MaintenanceEditorState): Boolean =
    editor.mode == MaintenanceEditorMode.ESTIMATE && editor.lines.isEmpty()

/** A rework may be created from an acceptance without new child-owned photos. */
internal fun maintenanceRequiresPhotos(editor: MaintenanceEditorState): Boolean =
    editor.repairKind != "REWORK"

internal fun maintenanceHasPhotos(editor: MaintenanceEditorState): Boolean =
    editor.photoUris.isNotEmpty() || editor.readyMedia.isNotEmpty()

/**
 * Aggregate photos and work-line photos are uploaded together, but only aggregate photos take
 * part in the document cover policy. A line photo must still send the document through the
 * durable upload outbox.
 */
internal fun MaintenanceEditorState.hasPendingMaintenancePhotos(): Boolean =
    photoUris.isNotEmpty() || lines.any { line ->
        line.lineType == "WORK" && line.photoUris.isNotEmpty()
    }

/**
 * Materials are quantities, not annotated work instructions. Normalize at the UI boundary so
 * old responses, edits and local draft state cannot put a comment or a source image back onto a
 * MATERIAL line.
 */
internal fun MaintenanceLineEditorState.normalizedMaintenanceAnnotations(): MaintenanceLineEditorState =
    if (lineType == "MATERIAL") {
        copy(comment = "", mediaReferences = emptyList(), photoUris = emptyList())
    } else {
        copy(
            mediaReferences = mediaReferences.distinctBy(MediaReferenceDto::mediaId),
            photoUris = photoUris.distinct(),
        )
    }

internal fun maintenanceLocalPhotoKey(uri: String): String = "local:$uri"

internal fun maintenanceReadyPhotoKey(mediaId: String): String = "media:$mediaId"

internal fun maintenanceInitialCoverPhotoKey(
    references: List<MediaReferenceDto>,
): String? = references.firstOrNull()?.let { reference ->
    maintenanceReadyPhotoKey(reference.mediaId)
}

internal fun removeMaintenanceLocalPhoto(
    editor: MaintenanceEditorState,
    uri: String,
): MaintenanceEditorState = editor.copy(
    photoUris = editor.photoUris - uri,
    coverPhotoKey = editor.coverPhotoKey.takeUnless {
        it == maintenanceLocalPhotoKey(uri)
    },
)

internal fun maintenanceHasCoverPhoto(editor: MaintenanceEditorState): Boolean {
    val key = editor.coverPhotoKey ?: return false
    return editor.photoUris.any { uri -> maintenanceLocalPhotoKey(uri) == key } ||
        editor.readyMedia.any { reference -> maintenanceReadyPhotoKey(reference.mediaId) == key }
}

internal fun orderedMaintenanceLocalPhotoUris(editor: MaintenanceEditorState): List<String> {
    val coverKey = editor.coverPhotoKey
    return editor.photoUris.sortedBy { uri ->
        if (maintenanceLocalPhotoKey(uri) == coverKey) 0 else 1
    }
}

internal fun orderedMaintenanceReadyMedia(
    editor: MaintenanceEditorState,
): List<MediaReferenceDto> {
    val coverKey = editor.coverPhotoKey
    return editor.readyMedia.sortedBy { reference ->
        if (maintenanceReadyPhotoKey(reference.mediaId) == coverKey) 0 else 1
    }
}

internal fun catalogMaintenanceLineType(nodeType: String): String = when (nodeType) {
    "WORK" -> "WORK"
    "MATERIAL", "OPTION" -> "MATERIAL"
    else -> throw IllegalArgumentException("Неподдерживаемый тип позиции каталога")
}

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class ActiveMaintenanceCatalogRevision(
    val id: String,
    val version: Long,
)

/**
 * An empty cache is never authoritative. In particular, the app may have opened before the
 * first catalog activation or a previous load may have stopped before nodes were applied.
 */
internal fun shouldReloadMaintenanceCatalog(
    requestedWarehouseId: String,
    cachedWarehouseId: String?,
    cachedRevision: ActiveMaintenanceCatalogRevision?,
    cachedNodeCount: Int,
): Boolean = cachedWarehouseId != requestedWarehouseId ||
    cachedRevision == null ||
    cachedNodeCount == 0

/**
 * Catalog refreshes belong to daily local-time slots that start at 09:00. Before 09:00 the
 * current slot is still yesterday's one, so opening the app early does not consume today's
 * scheduled refresh.
 */
internal fun maintenanceCatalogSyncSlot(now: ZonedDateTime): LocalDate =
    if (now.toLocalTime().isBefore(MAINTENANCE_CATALOG_SYNC_TIME)) {
        now.toLocalDate().minusDays(1)
    } else {
        now.toLocalDate()
    }

internal fun shouldAttemptMaintenanceCatalogSync(
    lastAttemptSlot: LocalDate?,
    now: ZonedDateTime,
): Boolean = lastAttemptSlot != maintenanceCatalogSyncSlot(now)

internal fun millisUntilNextMaintenanceCatalogSync(now: ZonedDateTime): Long {
    val todayAtSyncTime = now.toLocalDate()
        .atTime(MAINTENANCE_CATALOG_SYNC_TIME)
        .atZone(now.zone)
    val nextSync = if (now.isBefore(todayAtSyncTime)) {
        todayAtSyncTime
    } else {
        now.toLocalDate()
            .plusDays(1)
            .atTime(MAINTENANCE_CATALOG_SYNC_TIME)
            .atZone(now.zone)
    }
    return Duration.between(now, nextSync).toMillis().coerceAtLeast(1L)
}

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
data class MaintenanceReturnMetadata(
    val sourceParty: String?,
    val dispatchDate: String?,
)

internal fun latestMaintenanceReturnMetadata(
    returnDocuments: List<LogisticsDocumentDto>,
    rentalItemId: String,
): MaintenanceReturnMetadata? {
    val latest = returnDocuments
        .asSequence()
        .filter { document -> document.lines.any { line -> line.assetId == rentalItemId } }
        .maxByOrNull { document -> document.createdAt.toReturnInstant() ?: Instant.EPOCH }
        ?: return null
    val matchingLine = latest.lines.firstOrNull { line -> line.assetId == rentalItemId }
    return MaintenanceReturnMetadata(
        sourceParty = latest.partySnapshot.nonBlank() ?: matchingLine?.tenantSnapshot.nonBlank(),
        dispatchDate = latest.createdAt.toReturnLocalDate()?.toString(),
    )
}

internal fun RoutingSnapshotDto.routingLabel(): String =
    listOf(queueName.trim(), queueType.trim()).filter(String::isNotEmpty).joinToString(" · ")

/**
 * The aggregate task-board snapshot is the live directory of queues. Catalog snapshots are intentionally
 * not used as the only source here: a catalog may reference a route that was subsequently
 * removed or disabled in the board settings.
 */
internal fun TaskBoardSnapshotDto?.maintenanceWorkRoutingOptions(): List<RoutingSnapshotDto> =
    this?.columns
        .orEmpty()
        .asSequence()
        .filter { column -> column.queueType in setOf("REPAIR", "HOLDING") }
        .map(TaskBoardColumnDto::toMaintenanceRouting)
        .filter(RoutingSnapshotDto::isUsableMaintenanceRouting)
        .distinctBy(RoutingSnapshotDto::queueId)
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, RoutingSnapshotDto::queueName)
                .thenBy(RoutingSnapshotDto::queueId),
        )
        .toList()

private fun TaskBoardColumnDto.toMaintenanceRouting(): RoutingSnapshotDto = RoutingSnapshotDto(
    queueId = queueId,
    queueName = queueName,
    queueType = queueType,
)

private fun RoutingSnapshotDto.isUsableMaintenanceRouting(): Boolean =
    queueId.isNotBlank() && queueName.isNotBlank() && queueType.isNotBlank()

/**
 * A movement-to-repair is a logistics command, not a task-board stage.  Keeping its intent in
 * the repair command lets the server schedule it through the warehouse's logistics queue.
 */
internal fun MaintenanceEditorState.withMovementToRepair(
    required: Boolean,
): MaintenanceEditorState = if (required) {
    copy(
        movementToRepair = true,
        forceCapitalRepair = false,
        logisticsPlanningMode = logisticsPlanningMode
            ?.takeIf { mode -> mode in logisticsPlanningModes }
            ?: LOGISTICS_PLANNING_MODE_AUTO,
        logisticsScheduledDate = logisticsScheduledDate
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.takeIf { logisticsPlanningMode == LOGISTICS_PLANNING_MODE_FIXED_DATE },
    )
} else {
    copy(
        movementToRepair = false,
        logisticsPlanningMode = null,
        logisticsScheduledDate = null,
    )
}

/**
 * Selects the explicit capital-repair route and clears the alternative inbound repair movement.
 * The owning service still calculates catalog-derived capital complexity independently.
 */
internal fun MaintenanceEditorState.withForceCapitalRepair(
    required: Boolean,
): MaintenanceEditorState = if (required) {
    copy(
        forceCapitalRepair = true,
        movementToRepair = false,
        logisticsPlanningMode = null,
        logisticsScheduledDate = null,
    )
} else {
    copy(forceCapitalRepair = false)
}

internal fun MaintenanceEditorState.withLogisticsPlanningMode(
    mode: String,
): MaintenanceEditorState {
    require(mode in logisticsPlanningModes) { "Неподдерживаемый режим планирования логистики" }
    if (!movementToRepair) return this
    return copy(
        logisticsPlanningMode = mode,
        logisticsScheduledDate = if (mode == LOGISTICS_PLANNING_MODE_FIXED_DATE) {
            logisticsScheduledDate?.trim()?.takeIf(String::isNotEmpty)
        } else {
            null
        },
    )
}

internal fun MaintenanceEditorState.withLogisticsScheduledDate(
    date: String,
): MaintenanceEditorState = if (movementToRepair) {
    copy(logisticsScheduledDate = date.trim().takeIf(String::isNotEmpty))
} else {
    this
}

/** Normalizes the nullable transport fields before every command. */
internal fun MaintenanceEditorState.normalizedLogisticsPlanning(): MaintenanceEditorState {
    if (!movementToRepair) {
        return copy(logisticsPlanningMode = null, logisticsScheduledDate = null)
    }
    val mode = logisticsPlanningMode
        ?.takeIf { it in logisticsPlanningModes }
        ?: LOGISTICS_PLANNING_MODE_AUTO
    return copy(
        logisticsPlanningMode = mode,
        logisticsScheduledDate = if (mode == LOGISTICS_PLANNING_MODE_FIXED_DATE) {
            logisticsScheduledDate?.trim()?.takeIf(String::isNotEmpty)
        } else {
            null
        },
    )
}

internal fun MaintenanceEditorState.logisticsPlanningValidationError(): String? {
    if (!movementToRepair) return null
    val normalized = normalizedLogisticsPlanning()
    return when (normalized.logisticsPlanningMode) {
        LOGISTICS_PLANNING_MODE_AUTO -> null
        LOGISTICS_PLANNING_MODE_FIXED_DATE -> {
            val date = normalized.logisticsScheduledDate
            if (date == null || !isIsoLogisticsDate(date)) {
                "Выберите дату перемещения в формате ГГГГ-ММ-ДД"
            } else {
                null
            }
        }

        else -> "Выберите способ добавления в очередь перемещений"
    }
}

internal fun MaintenanceEditorState.logisticsTaskPriorityValidationError(): String? =
    if (priority in 1..5) {
        null
    } else {
        "Выберите приоритет ремонта от 1 до 5"
    }

private val logisticsPlanningModes = setOf(
    LOGISTICS_PLANNING_MODE_AUTO,
    LOGISTICS_PLANNING_MODE_FIXED_DATE,
)

private fun isIsoLogisticsDate(value: String): Boolean = runCatching {
    LocalDate.parse(value).toString() == value
}.getOrDefault(false)

/** Parses only the RGB values accepted by the shared catalog settings. */
internal fun catalogDisplayColorArgb(value: String?): Long? {
    val normalized = value?.trim().orEmpty()
    if (!Regex("^#[0-9A-Fa-f]{6}$").matches(normalized)) return null
    return 0xFF000000L or normalized.drop(1).toLong(16)
}

/** WCAG relative luminance threshold used for the label rendered over a catalog color. */
internal fun catalogDisplayColorNeedsLightContent(value: String?): Boolean {
    val argb = catalogDisplayColorArgb(value) ?: return false
    fun channel(offset: Int): Double {
        val value = ((argb shr offset) and 0xFF).toDouble() / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    val luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    return luminance < 0.36
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String.toReturnInstant(): Instant? = runCatching {
    OffsetDateTime.parse(this).toInstant()
}.recoverCatching {
    Instant.parse(this)
}.getOrNull()

private fun String.toReturnLocalDate(): LocalDate? = runCatching {
    OffsetDateTime.parse(this).toLocalDate()
}.recoverCatching {
    LocalDate.parse(substringBefore('T'))
}.getOrNull()
