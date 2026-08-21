package dev.buhanzaz.rwms.worker.feature.taskdetail

import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal enum class WorkerTaskAction(val wireValue: String) {
    TAKE("TAKE"),
    JOIN("JOIN"),
    PAUSE("PAUSE"),
    COMPLETE("COMPLETE"),
    RESUME("RESUME"),
}

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class TaskActionPresentation(
    val actions: List<WorkerTaskAction>,
    val actionsEnabled: Boolean,
    val message: String?,
    val performers: List<String>,
    val takeLabel: String,
    val joinLabel: String,
)

/**
 * Carries the manager-selected group on TAKE and JOIN when available, allowing
 * task-board to pause the slinger group's previous assignment atomically.
 */
internal fun selectedGroupForAction(
    action: WorkerTaskAction,
    currentGroupId: String?,
): String? {
    if (action == WorkerTaskAction.TAKE || action == WorkerTaskAction.JOIN) {
        requireNotNull(currentGroupId) { "Руководитель ещё не выбрал текущую группу" }
    }
    return when (action) {
        WorkerTaskAction.TAKE, WorkerTaskAction.JOIN -> currentGroupId
        else -> currentGroupId
    }
}

/**
 * Fails closed for waiting logistics entries: WorkerApp can only JOIN an
 * already active shared task and can act further only after assignment.
 */
internal fun taskActionPresentation(
    currentWorkerId: String,
    taskStatus: String?,
    availabilityMode: String?,
    queuePurpose: String? = null,
    assignments: List<WorkerAssignmentEntity>,
    locallyPending: Boolean,
    hasCurrentGroup: Boolean = true,
    operationalAvailability: String = "AVAILABLE",
): TaskActionPresentation {
    val isSecondaryLogistics = queuePurpose == LOGISTICS_DRIVER_QUEUE_PURPOSE
    val liveAssignments = assignments.filter { it.status == "ACTIVE" || it.status == "PAUSED" }
    val currentWorkerHasActiveAssignment = liveAssignments.any {
        it.workerId == currentWorkerId &&
            it.status == "ACTIVE" &&
            (!isSecondaryLogistics || it.workerGroupId != null)
    }
    val currentWorkerHasPausedAssignment = liveAssignments.any {
        it.workerId == currentWorkerId &&
            it.status == "PAUSED" &&
            (!isSecondaryLogistics || it.workerGroupId != null)
    }
    val assignedOnlyToOthers = liveAssignments.isNotEmpty() &&
        liveAssignments.none { it.workerId == currentWorkerId }
    val canJoinMandatoryTask = taskStatus == "IN_PROGRESS" &&
        availabilityMode == "MANDATORY" &&
        assignedOnlyToOthers
    val canJoinOptionalLogisticsTask = taskStatus == "IN_PROGRESS" &&
        availabilityMode == "OPTIONAL_JOIN" &&
        assignedOnlyToOthers
    val canJoinRequiredTask = taskStatus == "IN_PROGRESS" &&
        availabilityMode == "REQUIRED_JOIN" &&
        assignedOnlyToOthers
    val candidateActions = when {
        taskStatus == "WAITING" &&
            !isSecondaryLogistics &&
            availabilityMode != "SECONDARY_PENDING" &&
            liveAssignments.isEmpty() ->
            listOf(WorkerTaskAction.TAKE)
        canJoinOptionalLogisticsTask ->
            listOf(WorkerTaskAction.JOIN)
        canJoinRequiredTask ->
            listOf(WorkerTaskAction.JOIN)
        canJoinMandatoryTask ->
            listOf(WorkerTaskAction.TAKE)
        taskStatus == "IN_PROGRESS" && currentWorkerHasActiveAssignment ->
            listOf(WorkerTaskAction.PAUSE, WorkerTaskAction.COMPLETE)
        taskStatus == "PAUSED" && currentWorkerHasPausedAssignment ->
            listOf(WorkerTaskAction.RESUME)
        else -> emptyList()
    }
    val actions = when {
        operationalAvailability != "AVAILABLE" -> emptyList()
        !hasCurrentGroup &&
            candidateActions.any { it == WorkerTaskAction.TAKE || it == WorkerTaskAction.JOIN } ->
            emptyList()
        else -> candidateActions
    }
    val performers = liveAssignments.mapNotNull { assignment ->
        assignment.workerName
            ?: assignment.workerGroupName
            ?: assignment.workerId
    }.distinct()
    val message = when {
        locallyPending -> "Действие ожидает синхронизации"
        operationalAvailability != "AVAILABLE" -> "Рабочий временно недоступен"
        !hasCurrentGroup &&
            candidateActions.any { it == WorkerTaskAction.TAKE || it == WorkerTaskAction.JOIN } ->
            "Руководитель ещё не выбрал текущую группу"
        taskStatus == "WAITING" && availabilityMode == "SECONDARY_PENDING" ->
            "Ожидает основного исполнителя"
        canJoinMandatoryTask -> "Срочное задание: присоединитесь к выполнению"
        canJoinRequiredTask -> "Для продолжения задания требуется присоединиться"
        canJoinOptionalLogisticsTask -> "Активное совместное задание — можно присоединиться"
        actions.isNotEmpty() -> null
        assignedOnlyToOthers -> "Задание выполняет другой рабочий"
        else -> "Действия недоступны в текущем состоянии"
    }
    return TaskActionPresentation(
        actions = actions,
        actionsEnabled = actions.isNotEmpty() && !locallyPending,
        message = message,
        performers = performers,
        takeLabel = "Взять задание",
        joinLabel = if (
            isSecondaryLogistics && (canJoinOptionalLogisticsTask || canJoinRequiredTask)
        ) {
            "Взять задание"
        } else {
            "Присоединиться"
        },
    )
}

/** Selects the required READY photo used to complete a shared logistics task. */
internal fun completionEvidenceId(
    queuePurpose: String?,
    readyEvidenceIds: Set<String>,
    selectedEvidenceId: String?,
): String? {
    if (queuePurpose != LOGISTICS_DRIVER_QUEUE_PURPOSE) return null
    return when (readyEvidenceIds.size) {
        1 -> readyEvidenceIds.single()
        else -> selectedEvidenceId?.takeIf(readyEvidenceIds::contains)
    }
}

/** Attaches the new photo only where the task-board contract requires a completion selection. */
internal fun queuedCompletionEvidenceId(queuePurpose: String?, capturedEvidenceId: String): String? =
    capturedEvidenceId.takeIf { queuePurpose == LOGISTICS_DRIVER_QUEUE_PURPOSE }

internal const val LOGISTICS_DRIVER_QUEUE_PURPOSE = "LOGISTICS_DRIVER"
