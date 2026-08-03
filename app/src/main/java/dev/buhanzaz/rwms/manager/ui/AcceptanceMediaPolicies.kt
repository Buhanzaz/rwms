package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
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

internal fun MaintenanceAcceptanceEditorState.hasAcceptedAllWorkLines(): Boolean {
    val workLineIds = repair.plan.stages
        .asSequence()
        .flatMap { stage -> stage.workLines.asSequence() }
        .map { line -> line.id }
        .toSet()
    return workLineIds.all(acceptedWorkLineIds::contains)
}

internal fun acceptanceWorkSourceMedia(
    repair: RepairDto,
    stage: RepairStageDto,
    work: EstimateLineDto,
): AcceptanceMediaCollection {
    require(work.lineType == "WORK") { "Исходные фотографии доступны только для работы" }
    val owner = when {
        stage.taskSync?.taskBoardEntryId != null -> Triple(
            "TASK_BOARD_ENTRY",
            stage.taskSync.taskBoardEntryId,
            "WORK_RESULT",
        )
        repair.kind != "REWORK" && repair.estimateId != null -> Triple(
            "MAINTENANCE_ESTIMATE",
            repair.estimateId,
            "ESTIMATE",
        )
        repair.origin == "INVENTORY" && repair.inventorySource != null -> Triple(
            "INVENTORY_FINDING",
            repair.inventorySource.findingId,
            "INSPECTION",
        )
        else -> Triple(
            "MAINTENANCE_REPAIR",
            work.sourceRepairId ?: repair.id,
            "REPAIR",
        )
    }
    return AcceptanceMediaCollection(
        title = "Фото до: ${work.description}",
        items = work.mediaReferences
            .distinctBy(MediaReferenceDto::mediaId)
            .map { reference ->
                AcceptanceScopedMedia(reference, owner.first, owner.second, owner.third)
            },
        emptyMessage = "Для этой работы исходные фотографии не добавлены.",
    )
}

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
        title = "Фото после: этап ${stage.order + 1}",
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
