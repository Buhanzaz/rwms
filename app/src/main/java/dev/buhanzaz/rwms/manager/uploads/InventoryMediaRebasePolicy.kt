package dev.buhanzaz.rwms.manager.uploads

import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto

/**
 * The inventory finding and its frozen repair plan are historical snapshots.  They deliberately
 * retain the media generation that was used by that inspection revision.  A new supplement,
 * however, must use the current READY generation exposed by the media owner projection: media
 * processing can advance a generation without changing the logical media id.
 *
 * This policy is used only while preparing a new command.  It never mutates the historical DTO
 * returned by inventory-service.
 */
internal const val INVENTORY_RETAINED_MEDIA_UNAVAILABLE_MESSAGE =
    "Одна из сохранённых фотографий больше недоступна или ещё обрабатывается. " +
        "Обновите осмотр и проверьте фотографии."

/** Converts the current owner projection into the one current usable asset per media id. */
internal fun readyOwnerMediaAssetsById(
    assets: Collection<MediaAssetDto>,
): Map<String, MediaAssetDto> {
    val ready = assets.asSequence()
        .filter { asset -> asset.status == "READY" && asset.generation > 0L }
        .toList()
    require(ready.groupBy(MediaAssetDto::id).all { (_, currentAssets) -> currentAssets.size == 1 }) {
        "Медиасервис вернул несколько актуальных поколений одной фотографии"
    }
    return ready.associateBy(MediaAssetDto::id)
}

/** Converts the current owner projection into the one current usable reference per media id. */
internal fun readyOwnerMediaReferencesById(
    assets: Collection<MediaAssetDto>,
): Map<String, MediaReferenceDto> = readyOwnerMediaAssetsById(assets)
    .mapValues { (_, asset) -> MediaReferenceDto(asset.id, asset.generation) }

/**
 * Retains the submitted logical media ids and changes only their generation.  Missing, deleted,
 * failed or not-yet-ready media is a hard error: dropping it would silently change an inspection.
 */
internal fun rebaseRetainedInventoryMediaReferences(
    references: List<MediaReferenceDto>,
    currentReadyByMediaId: Map<String, MediaReferenceDto>,
): List<MediaReferenceDto> = references
    .distinctBy(MediaReferenceDto::mediaId)
    .map { historical ->
        currentReadyByMediaId[historical.mediaId]
            ?: throw IllegalArgumentException(INVENTORY_RETAINED_MEDIA_UNAVAILABLE_MESSAGE)
    }

/**
 * Keeps every work-line assignment intact while replacing only stale generations.  The command
 * remembers its editor line ids, so material-line media is still removed at this boundary.  Old
 * outbox rows can lack that mapping; for those rows we preserve and validate references rather
 * than silently deleting evidence.
 */
internal fun InventoryPlanSelectionDto.rebaseRetainedWorkLineMedia(
    currentReadyByMediaId: Map<String, MediaReferenceDto>,
    lineIds: List<String>,
    workLineIds: Set<String>,
): InventoryPlanSelectionDto = copy(
    lines = lines.mapIndexed { index, line ->
        val lineId = lineIds.getOrNull(index)
        val hasKnownLineMapping = lineIds.isNotEmpty()
        when {
            lineId != null && hasKnownLineMapping && lineId !in workLineIds ->
                line.copy(mediaReferences = emptyList())

            else -> line.copy(
                mediaReferences = rebaseRetainedInventoryMediaReferences(
                    line.mediaReferences,
                    currentReadyByMediaId,
                ),
            )
        }
    },
)

/**
 * Rebase one durable inventory outbox row immediately before its final save.  This covers an
 * operation created by an older app version: all aggregate photos, line photos and frozen work
 * plan references are refreshed by media id and persisted by the caller before the retry.
 */
internal fun BackgroundUploadOperation.rebaseRetainedInventoryMedia(
    command: InventoryUploadCommand,
    currentReadyByMediaId: Map<String, MediaReferenceDto>,
): BackgroundUploadOperation {
    val rebasedCommand = command.copy(
        existingMedia = rebaseRetainedInventoryMediaReferences(
            command.existingMedia,
            currentReadyByMediaId,
        ),
        planSelection = command.planSelection?.rebaseRetainedWorkLineMedia(
            currentReadyByMediaId = currentReadyByMediaId,
            lineIds = command.planLineIds,
            workLineIds = command.planWorkLineIds.toSet(),
        ),
    )
    return copy(
        photos = photos.map { photo ->
            photo.reference?.let { reference ->
                photo.copy(
                    reference = rebaseRetainedInventoryMediaReferences(
                        references = listOf(reference),
                        currentReadyByMediaId = currentReadyByMediaId,
                    ).single(),
                )
            } ?: photo
        },
        inventory = rebasedCommand,
    )
}
