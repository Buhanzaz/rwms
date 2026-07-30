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
import java.util.UUID
import kotlin.math.pow

internal const val DIRECT_REPAIR_SOURCE_PARTY = "app-приложение"
internal val MAINTENANCE_CATALOG_SYNC_TIME: LocalTime = LocalTime.of(9, 0)

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

internal fun orderedMaintenanceMediaReferences(
    editor: MaintenanceEditorState,
    uploadedReferences: List<MediaReferenceDto>,
): List<MediaReferenceDto> {
    val localUris = orderedMaintenanceLocalPhotoUris(editor)
    require(localUris.size == uploadedReferences.size) {
        "Сервис вернул неполный набор загруженных фотографий"
    }
    val ready = orderedMaintenanceReadyMedia(editor)
    val uploaded = localUris.zip(uploadedReferences)
        .associate { (uri, reference) -> uri to reference }
    val orderedUploaded = localUris.map(uploaded::getValue)
    val combined = if (editor.coverPhotoKey?.startsWith("local:") == true) {
        orderedUploaded + ready
    } else {
        ready + orderedUploaded
    }
    return combined.distinctBy(MediaReferenceDto::mediaId)
}

/**
 * Promotes completed local uploads into the editor before the maintenance command runs.
 * This keeps READY media reusable when the following replace/amend command fails.
 */
internal fun retainCompletedMaintenanceUploads(
    editor: MaintenanceEditorState,
    uploadedLocalUris: List<String>,
    uploadedReferences: List<MediaReferenceDto>,
): MaintenanceEditorState {
    require(uploadedLocalUris.size == uploadedReferences.size) {
        "Медиасервис вернул неполный результат загрузки"
    }
    val uploadedByUri = uploadedLocalUris.zip(uploadedReferences).toMap()
    val coverReference = editor.photoUris
        .firstOrNull { maintenanceLocalPhotoKey(it) == editor.coverPhotoKey }
        ?.let(uploadedByUri::get)
    return editor.copy(
        readyMedia = orderedMaintenanceMediaReferences(editor, uploadedReferences),
        readyPhotoUris = editor.readyPhotoUris +
            uploadedByUri.map { (uri, reference) -> reference.mediaId to uri },
        photoUris = editor.photoUris.filterNot(uploadedByUri::containsKey),
        coverPhotoKey = coverReference
            ?.let { maintenanceReadyPhotoKey(it.mediaId) }
            ?: editor.coverPhotoKey,
    )
}

internal fun catalogMaintenanceLineType(nodeType: String): String = when (nodeType) {
    "WORK" -> "WORK"
    "MATERIAL", "OPTION" -> "MATERIAL"
    else -> throw IllegalArgumentException("Неподдерживаемый тип позиции каталога")
}

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
 * The task-board snapshot is the live directory of queues. Catalog snapshots are intentionally
 * not used as the only source here: a catalog may reference a route that was subsequently
 * removed or disabled in the board settings.
 */
internal fun List<TaskBoardSnapshotDto>.maintenanceWorkRoutingOptions(): List<RoutingSnapshotDto> =
    asSequence()
        .flatMap { board -> board.columns.asSequence() }
        .filter { column -> column.queueType in setOf("REPAIR", "HOLDING") }
        .map(TaskBoardColumnDto::toMaintenanceRouting)
        .filter(RoutingSnapshotDto::isUsableMaintenanceRouting)
        .distinctBy(RoutingSnapshotDto::queueId)
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, RoutingSnapshotDto::queueName)
                .thenBy(RoutingSnapshotDto::queueId),
        )
        .toList()

internal fun List<TaskBoardSnapshotDto>.maintenanceMovementRoutingOptions(): List<RoutingSnapshotDto> =
    asSequence()
        .flatMap { board -> board.columns.asSequence() }
        .filter { column -> column.queueType == "MOVEMENT" }
        .map(TaskBoardColumnDto::toMaintenanceRouting)
        .filter(RoutingSnapshotDto::isUsableMaintenanceRouting)
        .distinctBy(RoutingSnapshotDto::queueId)
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, RoutingSnapshotDto::queueName)
                .thenBy(RoutingSnapshotDto::queueId),
        )
        .toList()

/** A move stage is unambiguous only when the live board contains one movement queue. */
internal fun List<TaskBoardSnapshotDto>.singleMaintenanceMovementRouting(): RoutingSnapshotDto? =
    maintenanceMovementRoutingOptions().singleOrNull()

internal fun List<TaskBoardSnapshotDto>.maintenanceMovementRoutingProblem(): String? = when (
    maintenanceMovementRoutingOptions().size
) {
    0 -> "В активной доске нет очереди перемещений."
    1 -> null
    else -> "В активной доске несколько очередей перемещений. Выберите одну в настройках панели."
}

private fun TaskBoardColumnDto.toMaintenanceRouting(): RoutingSnapshotDto = RoutingSnapshotDto(
    queueId = queueId,
    queueName = queueName,
    queueType = queueType,
)

private fun RoutingSnapshotDto.isUsableMaintenanceRouting(): Boolean =
    queueId.isNotBlank() && queueName.isNotBlank() && queueType.isNotBlank()

private const val MOVE_TO_REPAIR_STAGE_KIND = "MOVE_TO_REPAIR"
private const val MOVE_FROM_REPAIR_STAGE_KIND = "MOVE_FROM_REPAIR"

internal fun MaintenanceEditorState.hasRepairMovementStages(): Boolean =
    stages.any { stage -> stage.kind == MOVE_TO_REPAIR_STAGE_KIND } &&
        stages.any { stage -> stage.kind == MOVE_FROM_REPAIR_STAGE_KIND }

/**
 * Mirrors the panel's optional movement pair. Only the movement stages are added or removed;
 * existing work stages, their IDs, comments and deadlines are retained.
 */
internal fun MaintenanceEditorState.withRepairMovementStages(
    required: Boolean,
    movementRouting: RoutingSnapshotDto?,
): MaintenanceEditorState {
    val retained = stages.filterNot { stage ->
        stage.kind == MOVE_TO_REPAIR_STAGE_KIND || stage.kind == MOVE_FROM_REPAIR_STAGE_KIND
    }
    if (!required) return copy(stages = retained)

    val routing = requireNotNull(movementRouting) {
        "Для перемещения нужна единственная активная очередь перемещений"
    }
    require(routing.isUsableMaintenanceRouting()) {
        "Для перемещения нужна корректная очередь"
    }
    val existingTo = stages.firstOrNull { it.kind == MOVE_TO_REPAIR_STAGE_KIND }
    val existingFrom = stages.firstOrNull { it.kind == MOVE_FROM_REPAIR_STAGE_KIND }
    val moveTo = (existingTo ?: MaintenanceStageEditorState(
        id = UUID.randomUUID().toString(),
        kind = MOVE_TO_REPAIR_STAGE_KIND,
        routing = routing,
        includedLineIds = emptyList(),
        primaryLineId = null,
        groupComment = "",
        originalOrder = Int.MIN_VALUE,
    )).copy(
        routing = routing,
        includedLineIds = emptyList(),
        primaryLineId = null,
    )
    val moveFrom = (existingFrom ?: MaintenanceStageEditorState(
        id = UUID.randomUUID().toString(),
        kind = MOVE_FROM_REPAIR_STAGE_KIND,
        routing = routing,
        includedLineIds = emptyList(),
        primaryLineId = null,
        groupComment = "",
        originalOrder = Int.MAX_VALUE,
    )).copy(
        routing = routing,
        includedLineIds = emptyList(),
        primaryLineId = null,
    )
    return copy(stages = retained + moveTo + moveFrom)
}

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
