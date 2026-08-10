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
    val description: String = if (personal) "Личная квалификация" else "Групповая роль",
)

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class QueueTaskTimerPresentation(
    val elapsed: String,
    val remaining: String?,
    val percent: String?,
    val state: String,
)

/**
 * Formats the server-counted execution time and optional budget timer without
 * extrapolating time on the client.
 */
internal fun queueTaskTimerPresentation(task: WorkerTaskEntity): QueueTaskTimerPresentation? {
    val state = task.timerState ?: return null
    val countedActiveSeconds = task.timerCountedActiveSeconds ?: return null
    if (task.timerServerTime == null) return null
    return QueueTaskTimerPresentation(
        elapsed = unsignedQueueDurationLabel(countedActiveSeconds),
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
 * Formats the last authoritative accumulated work duration when a queue has
 * no budget timer snapshot.
 */
internal fun queueTaskElapsedLabel(task: WorkerTaskEntity): String =
    unsignedQueueDurationLabel(task.activeWorkSeconds)

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
 * Projects service-issued queue/group bindings into independently collapsible
 * worker-role columns. Group-bound queues stay under their group role. A
 * driver queue becomes two explicit tables: assigned work is personal
 * logistics, while warehouse-shared work is movement. Every other
 * qualification-only category becomes its own personal column because the
 * local projection exposes the authorized category name, but deliberately
 * does not guess a missing qualification name or queue-to-class binding.
 *
 * A live assignment is more specific than a category audience: once present,
 * the card is visible only in the assigned group (or in qualification-only
 * work when the assignment deliberately has no group).
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

    val driverCategories = categories.filter {
        it.queuePurpose == LOGISTICS_DRIVER_QUEUE_PURPOSE
    }
    val driverQueueIds = driverCategories.mapTo(mutableSetOf()) { it.queueId }
    val driverTasks = tasks.filter { it.categoryId in driverQueueIds }
    val qualificationCategories = categories.filter {
        it.groupIds().isEmpty() && it.queuePurpose != LOGISTICS_DRIVER_QUEUE_PURPOSE
    }
    val qualificationQueueIds = qualificationCategories.mapTo(mutableSetOf()) { it.queueId }
    val qualificationTasks = tasks.filter(::visibleAsPersonal)
        .filter { it.categoryId in qualificationQueueIds }
    val groupColumns = groups
        .distinctBy { it.groupId }
        .sortedWith(compareBy(WorkerGroupEntity::name, WorkerGroupEntity::groupId))
        .map { group ->
            val authorizedCategories = categories.filter { category ->
                category.queuePurpose != LOGISTICS_DRIVER_QUEUE_PURPOSE &&
                    (group.groupId in category.groupIds() ||
                        tasks.any { task ->
                            task.categoryId == category.queueId &&
                                visibleInGroup(task, group.groupId)
                        })
            }
            WorkBoardColumn(
                id = group.groupId,
                name = group.name,
                personal = false,
                sections = buildTaskQueueSections(
                    categories = authorizedCategories,
                    tasks = tasks.filter { visibleInGroup(it, group.groupId) },
                ),
            )
        }

    val driverColumns = if (driverCategories.isEmpty()) {
        emptyList()
    } else {
        listOf(
            WorkBoardColumn(
                id = DRIVER_LOGISTICS_COLUMN_ID,
                name = "Логистика",
                personal = true,
                sections = buildTaskQueueSections(
                    categories = driverCategories,
                    tasks = driverTasks.filter {
                        it.driverAudienceMode == ASSIGNED_DRIVER_AUDIENCE
                    },
                ),
                description = "Только назначенные вам задания",
            ),
            WorkBoardColumn(
                id = DRIVER_MOVEMENTS_COLUMN_ID,
                name = "Перемещения",
                personal = true,
                sections = buildTaskQueueSections(
                    categories = driverCategories,
                    tasks = driverTasks.filter {
                        it.driverAudienceMode == WAREHOUSE_DRIVERS_AUDIENCE
                    },
                ),
                description = "Общие задания водителей склада",
            ),
        )
    }
    val qualificationColumns = buildTaskQueueSections(
        qualificationCategories,
        qualificationTasks,
    )
        .map { section ->
            WorkBoardColumn(
                id = "$QUALIFICATION_COLUMN_PREFIX${section.queueId}",
                name = section.name,
                personal = true,
                sections = listOf(section),
            )
        }
    return driverColumns + groupColumns + qualificationColumns
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

private fun unsignedQueueDurationLabel(seconds: Long): String =
    signedQueueDurationLabel(seconds.coerceAtLeast(0))

private const val GROUP_IDS_SEPARATOR = '\u001F'
private const val QUALIFICATION_COLUMN_PREFIX = "qualification-"
private const val LOGISTICS_DRIVER_QUEUE_PURPOSE = "LOGISTICS_DRIVER"
private const val ASSIGNED_DRIVER_AUDIENCE = "ASSIGNED_DRIVER"
private const val WAREHOUSE_DRIVERS_AUDIENCE = "WAREHOUSE_DRIVERS"
private const val DRIVER_LOGISTICS_COLUMN_ID = "driver-logistics"
private const val DRIVER_MOVEMENTS_COLUMN_ID = "driver-movements"
