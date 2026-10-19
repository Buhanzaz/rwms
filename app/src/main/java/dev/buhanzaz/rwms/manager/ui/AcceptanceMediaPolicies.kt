package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto

/** One immutable media reference paired with the owner scope that authorizes its preview read. */
data class AcceptanceScopedMedia(
    val reference: MediaReferenceDto,
    val ownerType: String,
    val ownerId: String,
    val context: String,
)

/** Names one ordered owner-scoped group before previews are downloaded. */
data class AcceptanceMediaCollection(
    val title: String,
    val items: List<AcceptanceScopedMedia>,
)

/**
 * Groups the exact owner-scoped references rendered during one acceptance review.
 * Work photos remain nested by stage so a reference can never leak into another work card.
 */
data class AcceptanceReviewMediaSources(
    val cabin: AcceptanceMediaCollection,
    val workByStageId: Map<String, Map<String, AcceptanceMediaCollection>>,
    val resultByStageId: Map<String, AcceptanceMediaCollection>,
)

/** Holds one complete locally cached preview set for an inline acceptance slider. */
data class AcceptanceInlineMediaState(
    val title: String,
    val photoUris: List<String>,
)

/** Contains all inline media resolved for one immutable acceptance editor snapshot. */
data class AcceptanceReviewMediaState(
    val cabin: AcceptanceInlineMediaState,
    val workByStageId: Map<String, Map<String, AcceptanceInlineMediaState>>,
    val resultByStageId: Map<String, AcceptanceInlineMediaState>,
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

/** Resolves only the planned media references attached to one exact work line. */
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
        title = "Фото к работе: ${work.description}",
        items = work.mediaReferences
            .distinctBy(MediaReferenceDto::mediaId)
            .map { reference ->
                AcceptanceScopedMedia(reference, owner.first, owner.second, owner.third)
            },
    )
}

/**
 * Resolves general task photos without duplicating media attached to a particular work line.
 *
 * Completed task-board entries are tried first because they carry the same source-media grants
 * used by WorkerApp. The original maintenance owner remains a fallback for capital work that has
 * not produced a task-board entry.
 */
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
    } else if (repair.origin == "INVENTORY" && repair.inventorySource != null) {
        ownerType = "INVENTORY_FINDING"
        ownerId = repair.inventorySource.findingId
        context = "INSPECTION"
        references = repair.mediaReferences
    } else {
        ownerType = "MAINTENANCE_REPAIR"
        ownerId = repair.id
        context = "REPAIR"
        references = repair.mediaReferences
    }
    val workMediaIds = repair.plan.stages
        .asSequence()
        .flatMap { stage -> stage.workLines.asSequence() }
        .flatMap { work -> work.mediaReferences.asSequence() }
        .map(MediaReferenceDto::mediaId)
        .toSet()
    val taskBoardScopes = repair.plan.stages
        .sortedBy(RepairStageDto::order)
        .mapNotNull { stage -> stage.taskSync?.taskBoardEntryId }
        .distinct()
        .map { entryId -> Triple("TASK_BOARD_ENTRY", entryId, "WORK_RESULT") }
    val scopes = (taskBoardScopes + Triple(ownerType, ownerId, context)).distinct()
    return AcceptanceMediaCollection(
        title = "Фото бытовки",
        items = references
            .filterNot { reference -> reference.mediaId in workMediaIds }
            .distinctBy(MediaReferenceDto::mediaId)
            .flatMap { reference ->
                scopes.map { scope ->
                    AcceptanceScopedMedia(reference, scope.first, scope.second, scope.third)
                }
            },
    )
}

/** Resolves only worker result evidence projected onto the exact completed repair stage. */
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
    )

/** Builds review groups without mixing general, per-work, or worker result evidence. */
internal fun acceptanceReviewMediaSources(
    repair: RepairDto,
    linkedEstimate: EstimateDto?,
): AcceptanceReviewMediaSources = AcceptanceReviewMediaSources(
    cabin = acceptanceCabinMedia(repair, linkedEstimate),
    workByStageId = repair.plan.stages.associate { stage ->
        stage.id to stage.workLines.associate { work ->
            work.id to acceptanceWorkSourceMedia(repair, stage, work)
        }
    },
    resultByStageId = repair.plan.stages.associate { stage ->
        stage.id to acceptanceStageMedia(stage)
    },
)
