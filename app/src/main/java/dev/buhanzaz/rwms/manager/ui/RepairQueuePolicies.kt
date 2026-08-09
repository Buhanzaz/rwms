package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.network.TaskBoardColumnDto
import dev.buhanzaz.rwms.manager.network.TaskBoardEntryDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import java.time.LocalDate

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class RepairQueueDateColumnRange(
    val date: String,
    val left: Float,
    val right: Float,
)

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
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
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */
data class RepairQueueItem(
    val repair: RepairDto,
    val stage: RepairStageDto,
    val entry: TaskBoardEntryDto,
    val queue: TaskBoardColumnDto,
    val date: String,
    val boardOrder: Int,
) {
    val id: String
        get() = "repair:${repair.id}"
}

internal fun operationalRepairStage(repair: RepairDto): RepairStageDto? {
    return repair.plan.stages
        .filter { stage ->
            stage.state != "DONE" && stage.state != "CANCELLED"
        }
        .sortedWith(
            compareBy<RepairStageDto> { stage ->
                if (stage.state == "IN_PROGRESS") 0 else 1
            }.thenBy(RepairStageDto::order),
        )
        .firstOrNull()
}

/**
 * Mirrors the panel repair queue: one repair is represented by its single
 * operational stage and matched to the exact task-board route entry.
 */
internal fun createRepairQueueItems(
    boards: List<TaskBoardSnapshotDto>,
    repairs: List<RepairDto>,
): List<RepairQueueItem> {
    data class LocatedEntry(
        val entry: TaskBoardEntryDto,
        val queue: TaskBoardColumnDto,
        val boardOrder: Int,
    )

    val entriesByRoute = linkedMapOf<String, LocatedEntry>()
    boards.forEach { board ->
        var boardOrder = 0
        board.columns.sortedBy(TaskBoardColumnDto::sortOrder).forEach { queue ->
            queue.entries.forEach { entry ->
                val externalTaskId = entry.externalTaskId
                if (externalTaskId != null) {
                    entriesByRoute["$externalTaskId:${entry.routeIndex}"] =
                        LocatedEntry(entry, queue, boardOrder)
                }
                boardOrder += 1
            }
        }
    }

    return repairs.mapNotNull { repair ->
        val stage = operationalRepairStage(repair) ?: return@mapNotNull null
        val externalTaskId = stage.taskSync?.externalTaskId ?: return@mapNotNull null
        val located = entriesByRoute["$externalTaskId:${stage.order}"]
            ?: return@mapNotNull null
        val source = located.entry.source
        if (source != null &&
            (source.type != "MAINTENANCE_REPAIR" || source.sourceId != repair.id)
        ) {
            return@mapNotNull null
        }
        RepairQueueItem(
            repair = repair,
            stage = stage,
            entry = located.entry,
            queue = located.queue,
            date = located.entry.scheduledDate,
            boardOrder = located.boardOrder,
        )
    }
}

internal fun repairQueueEntryCanMove(entry: TaskBoardEntryDto): Boolean =
    entry.entryType == "REAL" &&
        entry.taskStatus == "ACTIVE" &&
        entry.status == "WAITING"

internal fun minimumRepairQueueInsertionIndex(items: List<RepairQueueItem>): Int {
    val firstMovable = items.indexOfFirst { item ->
        repairQueueEntryCanMove(item.entry)
    }
    return if (firstMovable < 0) items.size else firstMovable
}

internal fun normalizedRepairQueueTargetIndex(
    targetItems: List<RepairQueueItem>,
    activeItem: RepairQueueItem,
    proposedIndex: Int,
    targetDate: String,
): Int {
    var targetIndex = proposedIndex.coerceIn(0, targetItems.size)
    val minimumIndex = minimumRepairQueueInsertionIndex(targetItems)
    targetIndex = targetIndex.coerceAtLeast(minimumIndex)

    if (activeItem.date == targetDate) {
        val sourceIndex = targetItems.indexOfFirst { it.id == activeItem.id }
        if (sourceIndex >= 0 && sourceIndex < targetIndex) {
            targetIndex -= 1
        }
    }
    return targetIndex.coerceAtLeast(minimumIndex)
}

/**
 * Resolves only an actual day-column hit. A drop in the horizontal whitespace
 * is deliberately left unresolved so the UI can ask the operator for a date
 * instead of silently choosing a nearby day.
 */
internal fun repairQueueDateAtHorizontalPosition(
    columns: List<RepairQueueDateColumnRange>,
    horizontalPosition: Float,
): String? = columns.firstOrNull { column ->
    horizontalPosition in column.left..column.right
}?.date

/**
 * The empty-area date picker appends to the selected day. When the source and
 * destination are the same day, remove the active item before deriving its
 * persisted target index.
 */
internal fun repairQueueEndTargetIndex(
    targetItems: List<RepairQueueItem>,
    activeItem: RepairQueueItem,
): Int {
    // The move endpoint interprets targetIndex after it removes the source
    // entry. An empty-area drop therefore appends after every remaining card.
    return targetItems.count { item -> item.id != activeItem.id }
}

internal fun isRepairQueueCalendarDate(value: String): Boolean {
    if (!REPAIR_QUEUE_CALENDAR_DATE.matches(value)) return false
    return runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
}

/**
 * Keeps a locally rearranged set of visible date columns while reconciling
 * newly loaded or removed server dates. Column order is a presentation
 * preference only; the task-board API remains the authority for task dates.
 */
internal fun reconcileRepairQueueDateColumnOrder(
    currentOrder: List<String>,
    availableDates: List<String>,
): List<String> {
    val canonicalDates = availableDates.distinct()
    val available = canonicalDates.toSet()
    val retained = currentOrder.filter { date -> date in available }.distinct()
    return retained + canonicalDates.filterNot(retained::contains)
}

/** Moves one date into an insertion position measured after it is removed. */
internal fun reorderRepairQueueDateColumn(
    dates: List<String>,
    activeDate: String,
    targetIndex: Int,
): List<String> {
    val normalizedDates = dates.distinct()
    if (activeDate !in normalizedDates) return normalizedDates

    val remaining = normalizedDates.filterNot { date -> date == activeDate }.toMutableList()
    remaining.add(targetIndex.coerceIn(0, remaining.size), activeDate)
    return remaining
}

/**
 * Swapping headers keeps each visual column in place.  After the server exchanges BoardTask
 * dates, this reversed date lookup makes the same cards remain in their physical columns while
 * their headers show the new dates.
 */
internal fun swapRepairQueueDateColumns(
    dates: List<String>,
    firstDate: String,
    secondDate: String,
): List<String> {
    if (firstDate == secondDate || firstDate !in dates || secondDate !in dates) return dates.distinct()
    return dates.distinct().map { date ->
        when (date) {
            firstDate -> secondDate
            secondDate -> firstDate
            else -> date
        }
    }
}

internal fun repairQueueDates(
    boards: List<TaskBoardSnapshotDto>,
    items: List<RepairQueueItem>,
): List<String> = buildSet {
    boards.forEach { board ->
        addAll(board.availableDates)
        board.selectedDate?.let(::add)
    }
    items.forEach { item -> add(item.date) }
}.sorted()

private val REPAIR_QUEUE_CALENDAR_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
