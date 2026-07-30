package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto

data class AcceptanceScopedMedia(
    val reference: MediaReferenceDto,
    val ownerType: String,
    val ownerId: String,
    val context: String,
)

data class AcceptanceMediaCollection(
    val title: String,
    val items: List<AcceptanceScopedMedia>,
    val emptyMessage: String,
)

/**
 * A newly selected local photo is acceptable input because the command uploads it before the
 * decision is sent. A decision with neither local nor already-ready media must not be exposed.
 */
internal fun MaintenanceAcceptanceEditorState.hasAcceptanceEvidence(): Boolean =
    photoUris.isNotEmpty() || readyMedia.isNotEmpty()

internal fun acceptanceCabinMedia(
    repair: RepairDto,
    linkedEstimate: EstimateDto?,
): AcceptanceMediaCollection {
    val useEstimate = repair.kind != "REWORK" && repair.estimateId != null
    val ownerType: String
    val ownerId: String
    val context: String
    val references: List<MediaReferenceDto>
    if (useEstimate) {
        val estimate = requireNotNull(linkedEstimate) {
            "Сервис не вернул связанную смету"
        }
        require(estimate.id == repair.estimateId) {
            "Сервис вернул другую связанную смету"
        }
        ownerType = "MAINTENANCE_ESTIMATE"
        ownerId = estimate.id
        context = "ESTIMATE"
        references = estimate.mediaReferences
    } else {
        ownerType = "MAINTENANCE_REPAIR"
        ownerId = repair.id
        context = "REPAIR"
        references = repair.mediaReferences
    }
    return AcceptanceMediaCollection(
        title = "Фото бытовки",
        items = references.map { reference ->
            AcceptanceScopedMedia(reference, ownerType, ownerId, context)
        },
        emptyMessage = "Фото бытовки при создании сметы или ремонта отсутствуют.",
    )
}

internal fun acceptanceStageMedia(stage: RepairStageDto): AcceptanceMediaCollection =
    AcceptanceMediaCollection(
        title = "Фото этапа ${stage.order + 1}",
        items = stage.evidence
            .map { evidence ->
                AcceptanceScopedMedia(
                    reference = MediaReferenceDto(
                        mediaId = evidence.mediaId,
                        generation = evidence.mediaGeneration,
                    ),
                    ownerType = "TASK_BOARD_ENTRY",
                    ownerId = evidence.entryId,
                    context = "WORK_RESULT",
                )
            }
            .distinctBy { media ->
                listOf(
                    media.ownerType,
                    media.ownerId,
                    media.context,
                    media.reference.mediaId,
                    media.reference.generation.toString(),
                ).joinToString(":")
            },
        emptyMessage = "Для этого этапа фотографии результата работы не добавлены.",
    )
