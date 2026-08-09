package dev.buhanzaz.rwms.worker.feature.tasks

import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerGroupEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class TaskQueueSection(
    val queueId: String,
    val name: String,
    val queuePurpose: String,
    val sortOrder: Int,
    val tasks: List<WorkerTaskEntity>,
)

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class WorkBoardColumn(
    val id: String,
    val name: String,
    val personal: Boolean,
    val sections: List<TaskQueueSection>,
)

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
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

/**
 * Projects the service-issued queue/group bindings into worker-facing columns.
 * A live assignment is more specific than a category audience: once present,
 * the card is visible only in the assigned group (or in personal work when the
 * assignment deliberately has no group).
 */
internal fun buildWorkBoardColumns(
    groups: List<WorkerGroupEntity>,
    categories: List<WorkerCategoryEntity>,
    tasks: List<WorkerTaskEntity>,
    assignments: List<WorkerAssignmentEntity>,
): List<WorkBoardColumn> {
    val liveAssignmentsByEntry = assignments
        .filter { it.status == "ACTIVE" || it.status == "PAUSED" }
        .groupBy { it.entryId }
    val categoriesByQueue = categories.associateBy { it.queueId }

    fun visibleInGroup(task: WorkerTaskEntity, groupId: String): Boolean {
        val liveAssignments = liveAssignmentsByEntry[task.entryId].orEmpty()
        if (liveAssignments.isNotEmpty()) {
            return liveAssignments.any { it.workerGroupId == groupId }
        }
        return groupId in categoriesByQueue[task.categoryId]?.groupIds().orEmpty()
    }

    fun visibleAsPersonal(task: WorkerTaskEntity): Boolean {
        val liveAssignments = liveAssignmentsByEntry[task.entryId].orEmpty()
        if (liveAssignments.isNotEmpty()) {
            return liveAssignments.any { it.workerGroupId == null }
        }
        return categoriesByQueue[task.categoryId]?.groupIds().orEmpty().isEmpty()
    }

    val personalCategories = categories.filter { it.groupIds().isEmpty() }
    val personalTasks = tasks.filter(::visibleAsPersonal)
    val personalSection = personalTasks.takeIf(List<WorkerTaskEntity>::isNotEmpty)?.let {
        TaskQueueSection(
            queueId = PERSONAL_COLUMN_ID,
            name = "Личные задания",
            queuePurpose = "PERSONAL",
            sortOrder = Int.MAX_VALUE,
            tasks = it.sortedWith(
                compareBy<WorkerTaskEntity> { task -> task.categorySortOrder }
                    .thenBy { task -> task.queuePosition }
                    .thenByDescending { task -> task.priority }
                    .thenBy { task -> task.localId },
            ),
        )
    }
    val groupColumns = groups
        .distinctBy { it.groupId }
        .sortedWith(compareBy(WorkerGroupEntity::name, WorkerGroupEntity::groupId))
        .mapIndexed { index, group ->
            val authorizedCategories = categories.filter { category ->
                group.groupId in category.groupIds() ||
                    tasks.any { task ->
                        task.categoryId == category.queueId && visibleInGroup(task, group.groupId)
                    }
            }
            WorkBoardColumn(
                id = group.groupId,
                name = group.name,
                personal = false,
                sections = buildTaskQueueSections(
                    categories = authorizedCategories,
                    tasks = tasks.filter { visibleInGroup(it, group.groupId) },
                ) + if (index == 0) listOfNotNull(personalSection) else emptyList(),
            )
        }

    val personalColumn = if (
        groupColumns.isEmpty() && (personalCategories.isNotEmpty() || personalTasks.isNotEmpty())
    ) {
        WorkBoardColumn(
            id = PERSONAL_COLUMN_ID,
            name = "Личные задания",
            personal = true,
            sections = buildTaskQueueSections(personalCategories, personalTasks),
        )
    } else {
        null
    }
    return groupColumns + listOfNotNull(personalColumn)
}

internal fun WorkerCategoryEntity.groupIds(): Set<String> =
    groupIdsKey.splitToSequence(GROUP_IDS_SEPARATOR)
        .filter(String::isNotBlank)
        .toSet()

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

private const val GROUP_IDS_SEPARATOR = '\u001F'
private const val PERSONAL_COLUMN_ID = "personal"
