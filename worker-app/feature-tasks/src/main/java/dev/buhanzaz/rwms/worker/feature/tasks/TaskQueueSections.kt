package dev.buhanzaz.rwms.worker.feature.tasks

import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity

internal data class TaskQueueSection(
    val queueId: String,
    val name: String,
    val queuePurpose: String,
    val sortOrder: Int,
    val tasks: List<WorkerTaskEntity>,
)

internal data class QueueTaskTimerPresentation(
    val remaining: String?,
    val percent: String?,
    val state: String,
)

internal fun queueTaskTimerPresentation(task: WorkerTaskEntity): QueueTaskTimerPresentation? {
    val state = task.timerState ?: return null
    if (task.timerCountedActiveSeconds == null || task.timerServerTime == null) return null
    return QueueTaskTimerPresentation(
        remaining = task.timerRemainingSeconds?.let(::signedQueueDurationLabel),
        percent = task.timerRemainingPercent?.let { "%.1f%%".format(java.util.Locale.ROOT, it) },
        state = when (state) {
            "WORKING" -> "В работе"
            "BREAK" -> "Перерыв · таймер остановлен"
            "OFF_SHIFT" -> "Вне смены · таймер остановлен"
            "PAUSED" -> "Пауза · таймер остановлен"
            "DONE" -> "Завершено"
            else -> "По данным сервера"
        },
    )
}

/**
 * Builds one section per authorized queue. Tasks from an upgraded v1 cache
 * remain visible as fallback sections until the first full v2 sync stores the
 * independent queue projection.
 */
internal fun buildTaskQueueSections(
    categories: List<WorkerCategoryEntity>,
    tasks: List<WorkerTaskEntity>,
): List<TaskQueueSection> {
    val tasksByQueue = tasks.groupBy { it.categoryId }
    val orderedCategories = categories
        .distinctBy { it.queueId }
        .sortedWith(compareBy(WorkerCategoryEntity::sortOrder, WorkerCategoryEntity::queueId))
    val projected = orderedCategories.map { category ->
        TaskQueueSection(
            queueId = category.queueId,
            name = category.name,
            queuePurpose = category.queuePurpose,
            sortOrder = category.sortOrder,
            tasks = tasksByQueue[category.queueId].orEmpty().orderedWithinQueue(),
        )
    }
    val upgradedV1Fallback = if (categories.isEmpty()) {
        tasksByQueue.values
            .map { queueTasks ->
                val first = queueTasks.minWith(
                    compareBy(WorkerTaskEntity::categorySortOrder, WorkerTaskEntity::categoryId),
                )
                TaskQueueSection(
                    queueId = first.categoryId,
                    name = first.categoryName,
                    queuePurpose = "GENERAL",
                    sortOrder = first.categorySortOrder,
                    tasks = queueTasks.orderedWithinQueue(),
                )
            }
            .sortedWith(compareBy(TaskQueueSection::sortOrder, TaskQueueSection::queueId))
    } else {
        // Once an independent authorization projection exists, a task that is
        // not in it must fail closed even during concurrent Flow emissions.
        emptyList()
    }
    return projected + upgradedV1Fallback
}

private fun List<WorkerTaskEntity>.orderedWithinQueue(): List<WorkerTaskEntity> =
    sortedWith(
        compareBy<WorkerTaskEntity> { it.queuePosition }
            .thenByDescending { it.priority }
            .thenBy { it.localId },
    )

private fun signedQueueDurationLabel(seconds: Long): String {
    val sign = if (seconds < 0) "−" else ""
    val value = kotlin.math.abs(seconds)
    val hours = value / 3_600
    val minutes = (value % 3_600) / 60
    val remainder = value % 60
    return "$sign%d:%02d:%02d".format(hours, minutes, remainder)
}
