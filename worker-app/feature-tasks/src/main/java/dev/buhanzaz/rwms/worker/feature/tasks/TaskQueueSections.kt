package dev.buhanzaz.rwms.worker.feature.tasks

import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity

/** Presentation-only timer copied from the latest authoritative server snapshot. */
internal data class QueueTaskTimerPresentation(
    val elapsed: String,
    val percent: String?,
)

/**
 * Formats server-counted execution time and its optional budget without extrapolating time on the
 * client.
 */
internal fun queueTaskTimerPresentation(task: WorkerTaskEntity): QueueTaskTimerPresentation? {
    if (task.timerState == null) return null
    val countedActiveSeconds = task.timerCountedActiveSeconds ?: return null
    if (task.timerServerTime == null) return null
    return QueueTaskTimerPresentation(
        elapsed = unsignedQueueDurationLabel(countedActiveSeconds),
        percent = task.timerRemainingPercent?.let { "%.1f%%".format(java.util.Locale.ROOT, it) },
    )
}

/** Formats the last authoritative accumulated work duration when no budget timer is available. */
internal fun queueTaskElapsedLabel(task: WorkerTaskEntity): String =
    unsignedQueueDurationLabel(task.activeWorkSeconds)

/**
 * Chooses the only card shown on the worker home screen. A joined slinger task temporarily wins;
 * otherwise the worker's/group's live ordinary assignment wins, followed by one waiting entry in
 * the server-issued queue order.
 */
fun selectCurrentWorkerTask(
    userId: String,
    currentGroupId: String?,
    categories: List<WorkerCategoryEntity>,
    tasks: List<WorkerTaskEntity>,
    assignments: List<WorkerAssignmentEntity>,
): WorkerTaskEntity? {
    val categoriesById = categories.associateBy(WorkerCategoryEntity::queueId)
    val liveAssignments = assignments.filter(WorkerAssignmentEntity::isLive)
    val visibleTasks = tasks.asSequence()
        .filter { it.entryType == REAL_ENTRY_TYPE }
        // A completion is optimistic before its evidence reaches READY. Keep that
        // task in front until its durable outbox command is authoritatively
        // retired, otherwise the board can take the next waiting task too soon.
        .filter { it.locallyPending || it.status in VISIBLE_TASK_STATUSES }
        .filter { categoriesById.isEmpty() || it.categoryId in categoriesById }
        .filter { task ->
            val logistics = categoriesById[task.categoryId]?.queuePurpose == LOGISTICS_QUEUE_PURPOSE
            val belongsToWorkerOrCurrentGroup = liveAssignments.any { assignment ->
                assignment.entryId == task.entryId &&
                    (assignment.workerId == userId ||
                        currentGroupId != null && assignment.workerGroupId == currentGroupId)
            }
            when {
                task.locallyPending -> true
                logistics -> liveAssignments.any { assignment ->
                    assignment.entryId == task.entryId && assignment.workerId == userId
                }
                task.status == "WAITING" -> true
                else -> belongsToWorkerOrCurrentGroup
            }
        }
        .toList()

    fun foregroundRank(task: WorkerTaskEntity): Int {
        if (task.locallyPending) return -1
        val logistics = categoriesById[task.categoryId]?.queuePurpose == LOGISTICS_QUEUE_PURPOSE
        if (logistics) return 0
        val taskAssignments = liveAssignments.filter { it.entryId == task.entryId }
        val belongsToWorkerOrCurrentGroup = taskAssignments.any { assignment ->
            assignment.workerId == userId ||
                currentGroupId != null && assignment.workerGroupId == currentGroupId
        }
        return when {
            belongsToWorkerOrCurrentGroup && task.status == "IN_PROGRESS" -> 1
            belongsToWorkerOrCurrentGroup && task.status == "PAUSED" -> 2
            task.status == "IN_PROGRESS" -> 3
            task.status == "PAUSED" -> 4
            else -> 5
        }
    }

    return visibleTasks.minWithOrNull(
        compareBy<WorkerTaskEntity>(::foregroundRank)
            .thenBy { task -> categoriesById[task.categoryId]?.sortOrder ?: task.categorySortOrder }
            .thenBy { task -> if (task.pinned) 0 else 1 }
            .thenBy(WorkerTaskEntity::queuePosition)
            .thenBy(WorkerTaskEntity::localId),
    )
}

/**
 * Returns one unaccepted active logistics task that may interrupt the current group. The service
 * remains authoritative: the dialog's action sends JOIN, which performs the atomic group pause.
 */
fun selectIncomingSlingerTask(
    userId: String,
    currentGroupId: String?,
    operationalAvailability: String,
    categories: List<WorkerCategoryEntity>,
    tasks: List<WorkerTaskEntity>,
    assignments: List<WorkerAssignmentEntity>,
): WorkerTaskEntity? {
    if (currentGroupId == null || operationalAvailability != "AVAILABLE") return null
    val categoriesById = categories.associateBy(WorkerCategoryEntity::queueId)
    val liveAssignments = assignments.filter(WorkerAssignmentEntity::isLive)
    return tasks.asSequence()
        .filter { task ->
            categoriesById[task.categoryId]?.queuePurpose == LOGISTICS_QUEUE_PURPOSE
        }
        .filter { it.entryType == REAL_ENTRY_TYPE }
        .filter { it.status == "IN_PROGRESS" }
        .filter { it.availabilityMode in SLINGER_JOIN_MODES }
        .filterNot(WorkerTaskEntity::locallyPending)
        .filterNot { task ->
            liveAssignments.any { assignment ->
                assignment.entryId == task.entryId && assignment.workerId == userId
            }
        }
        .minWithOrNull(
            compareBy<WorkerTaskEntity> { task ->
                if (task.availabilityMode == "REQUIRED_JOIN") 0 else 1
            }
                .thenBy(WorkerTaskEntity::categorySortOrder)
                .thenBy(WorkerTaskEntity::queuePosition)
                .thenBy(WorkerTaskEntity::localId),
        )
}

/** Returns the only task-card heading that may represent a cabin-owned task. */
internal fun taskCardTitle(unitNumber: String?): String =
    dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay(unitNumber) ?: "Задание"

/** Replaces the backend's technical maintenance queue label in the worker UI. */
internal fun taskQueueLabel(name: String): String =
    dev.buhanzaz.rwms.worker.core.ui.workerTaskStageLabel(name) ?: name

/** Formats the server-issued budget without treating it as elapsed work. */
internal fun allocatedQueueDurationLabel(minutes: Int?): String = minutes
    ?.coerceAtLeast(0)
    ?.let { value -> "%d:%02d:00".format(value / 60, value % 60) }
    ?: "—"

private fun WorkerAssignmentEntity.isLive(): Boolean = status == "ACTIVE" || status == "PAUSED"

private fun signedQueueDurationLabel(seconds: Long): String {
    val sign = if (seconds < 0) "−" else ""
    val value = kotlin.math.abs(seconds)
    val hours = value / 3_600
    val minutes = (value % 3_600) / 60
    val remainder = value % 60
    return "$sign%d:%02d:%02d".format(hours, minutes, remainder)
}

private fun unsignedQueueDurationLabel(seconds: Long): String =
    signedQueueDurationLabel(seconds.coerceAtLeast(0))

internal const val LOGISTICS_QUEUE_PURPOSE = "LOGISTICS_DRIVER"
private const val REAL_ENTRY_TYPE = "REAL"
private val VISIBLE_TASK_STATUSES = setOf("WAITING", "IN_PROGRESS", "PAUSED")
private val SLINGER_JOIN_MODES = setOf("REQUIRED_JOIN", "OPTIONAL_JOIN")
