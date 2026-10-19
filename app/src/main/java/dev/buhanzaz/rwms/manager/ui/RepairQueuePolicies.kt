package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.network.TaskBoardColumnDto
import dev.buhanzaz.rwms.manager.network.TaskBoardEntryDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto

/** Work and material descriptions shown separately in the compact repair detail dialog. */
internal data class RepairQueueLineGroups(
    val works: List<String>,
    val materials: List<String>,
)

/** Keeps work and material information separate for the compact queue info card. */
internal fun RepairStageDto.repairQueueLineGroups(): RepairQueueLineGroups = RepairQueueLineGroups(
    works = workLines.map { line -> line.description.trim() }.filter(String::isNotEmpty),
    materials = materialLines.map { line -> line.description.trim() }.filter(String::isNotEmpty),
)

/**
 * Binds one ordinary task-board entry to the maintenance repair and stage that own its source.
 * The board remains authoritative for queue order and task state.
 */
internal data class RepairQueueItem(
    val repair: RepairDto,
    val stage: RepairStageDto,
    val entry: TaskBoardEntryDto,
    val queue: TaskBoardColumnDto,
    val boardOrder: Int,
) {
    val id: String
        get() = "repair:${repair.id}"
}

/** One server-ordered queue and the maintenance repair cards resolved inside it. */
internal data class RepairQueueSection(
    val queue: TaskBoardColumnDto,
    val items: List<RepairQueueItem>,
)

/** Chooses the single unfinished maintenance stage represented on the ordinary board. */
internal fun operationalRepairStage(repair: RepairDto): RepairStageDto? = repair.plan.stages
    .filter { stage -> stage.state != "DONE" && stage.state != "CANCELLED" }
    .sortedWith(
        compareBy<RepairStageDto> { stage ->
            if (stage.state == "IN_PROGRESS") 0 else 1
        }.thenBy(RepairStageDto::order),
    )
    .firstOrNull()

/**
 * Resolves ordinary board entries only through their exact maintenance source and route identity.
 * Missing, shadow-only, or cross-source entries are never fabricated as repair cards.
 */
internal fun createRepairQueueItems(
    board: TaskBoardSnapshotDto?,
    repairs: List<RepairDto>,
): List<RepairQueueItem> {
    /** Board location retained while source repairs are joined to their active route. */
    data class LocatedEntry(
        val entry: TaskBoardEntryDto,
        val queue: TaskBoardColumnDto,
        val boardOrder: Int,
    )

    if (board == null) return emptyList()
    val entriesByRoute = linkedMapOf<String, LocatedEntry>()
    var boardOrder = 0
    board.columns.sortedBy(TaskBoardColumnDto::sortOrder).forEach { queue ->
        queue.entries.forEach { entry ->
            val externalTaskId = entry.externalTaskId
            if (entry.entryType == "REAL" && externalTaskId != null) {
                entriesByRoute["$externalTaskId:${entry.routeIndex}"] =
                    LocatedEntry(entry, queue, boardOrder)
            }
            boardOrder += 1
        }
    }

    return repairs.mapNotNull { repair ->
        val stage = operationalRepairStage(repair) ?: return@mapNotNull null
        val externalTaskId = stage.taskSync?.externalTaskId ?: return@mapNotNull null
        val located = entriesByRoute["$externalTaskId:${stage.order}"] ?: return@mapNotNull null
        val source = located.entry.source
        if (source?.type != "MAINTENANCE_REPAIR" || source.sourceId != repair.id) {
            return@mapNotNull null
        }
        RepairQueueItem(
            repair = repair,
            stage = stage,
            entry = located.entry,
            queue = located.queue,
            boardOrder = located.boardOrder,
        )
    }
}

/**
 * Groups resolved repair cards by the aggregate board's canonical queue order and card position.
 * Empty queues are omitted from the manager repair view.
 */
internal fun repairQueueSections(
    board: TaskBoardSnapshotDto?,
    items: List<RepairQueueItem>,
): List<RepairQueueSection> {
    if (board == null) return emptyList()
    val itemsByQueue = items.groupBy { item -> item.queue.queueId }
    return board.columns
        .sortedWith(compareBy(TaskBoardColumnDto::sortOrder, TaskBoardColumnDto::queueId))
        .mapNotNull { queue ->
            val queueItems = itemsByQueue[queue.queueId]
                .orEmpty()
                .sortedWith(
                    compareBy<RepairQueueItem> { item -> item.entry.queuePosition }
                        .thenBy(RepairQueueItem::boardOrder)
                        .thenBy(RepairQueueItem::id),
                )
            queueItems.takeIf { it.isNotEmpty() }?.let { resolved ->
                RepairQueueSection(queue, resolved)
            }
        }
}
