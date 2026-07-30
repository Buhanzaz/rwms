package dev.buhanzaz.rwms.worker.feature.taskdetail

import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity

internal enum class WorkerTaskAction(val wireValue: String) {
    TAKE("TAKE"),
    PAUSE("PAUSE"),
    COMPLETE("COMPLETE"),
    RESUME("RESUME"),
}

internal data class TaskActionPresentation(
    val actions: List<WorkerTaskAction>,
    val actionsEnabled: Boolean,
    val message: String?,
    val performers: List<String>,
    val takeLabel: String,
)

internal fun managerSelectedGroupForAction(
    action: WorkerTaskAction,
    currentGroupId: String?,
): String? {
    if (action == WorkerTaskAction.TAKE) {
        requireNotNull(currentGroupId) { "Руководитель ещё не выбрал текущую группу" }
    }
    return currentGroupId
}

internal fun taskActionPresentation(
    currentWorkerId: String,
    taskStatus: String?,
    availabilityMode: String?,
    assignments: List<WorkerAssignmentEntity>,
    locallyPending: Boolean,
    hasCurrentGroup: Boolean = true,
    operationalAvailability: String = "AVAILABLE",
): TaskActionPresentation {
    val liveAssignments = assignments.filter { it.status == "ACTIVE" || it.status == "PAUSED" }
    val currentWorkerHasActiveAssignment = liveAssignments.any {
        it.workerId == currentWorkerId && it.status == "ACTIVE"
    }
    val currentWorkerHasPausedAssignment = liveAssignments.any {
        it.workerId == currentWorkerId && it.status == "PAUSED"
    }
    val assignedOnlyToOthers = liveAssignments.isNotEmpty() &&
        liveAssignments.none { it.workerId == currentWorkerId }
    val canJoinMandatoryTask = taskStatus == "IN_PROGRESS" &&
        availabilityMode == "MANDATORY" &&
        assignedOnlyToOthers
    val candidateActions = when {
        taskStatus == "WAITING" &&
            availabilityMode != "SECONDARY_PENDING" &&
            liveAssignments.isEmpty() ->
            listOf(WorkerTaskAction.TAKE)
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
        !hasCurrentGroup && WorkerTaskAction.TAKE in candidateActions -> emptyList()
        else -> candidateActions
    }
    val performers = liveAssignments.mapNotNull { assignment ->
        assignment.workerName
            ?: assignment.workerGroupName
            ?: assignment.workerId
    }.distinct()
    val message = when {
        locallyPending -> "Действие ожидает синхронизации"
        operationalAvailability != "AVAILABLE" -> "Группа временно недоступна"
        !hasCurrentGroup && WorkerTaskAction.TAKE in candidateActions ->
            "Руководитель ещё не выбрал текущую группу"
        taskStatus == "WAITING" && availabilityMode == "SECONDARY_PENDING" ->
            "Ожидает основного исполнителя"
        canJoinMandatoryTask -> "Срочное задание: присоединитесь к выполнению"
        actions.isNotEmpty() -> null
        assignedOnlyToOthers -> "Задание выполняет другой рабочий"
        else -> "Действия недоступны в текущем состоянии"
    }
    return TaskActionPresentation(
        actions = actions,
        actionsEnabled = actions.isNotEmpty() && !locallyPending,
        message = message,
        performers = performers,
        takeLabel = if (canJoinMandatoryTask) "Взять срочное" else "Взять",
    )
}
