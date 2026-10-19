package dev.buhanzaz.rwms.driver.feature.taskdetail

import dev.buhanzaz.rwms.driver.core.database.DriverAssignmentEntity

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal enum class DriverTaskAction(val wireValue: String) {
    TAKE("TAKE"),
    PAUSE("PAUSE"),
    COMPLETE("COMPLETE"),
    RESUME("RESUME"),
}

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal data class TaskActionPresentation(
    val actions: List<DriverTaskAction>,
    val actionsEnabled: Boolean,
    val message: String?,
    val performers: List<String>,
    val takeLabel: String,
)

internal fun selectedGroupForAction(
    action: DriverTaskAction,
    currentGroupId: String?,
    queuePurpose: String?,
): String? {
    if (action == DriverTaskAction.TAKE && queuePurpose != LOGISTICS_DRIVER_QUEUE_PURPOSE) {
        requireNotNull(currentGroupId) { "Руководитель ещё не выбрал текущую группу" }
    }
    return when (action) {
        DriverTaskAction.TAKE -> currentGroupId
            .takeUnless { queuePurpose == LOGISTICS_DRIVER_QUEUE_PURPOSE }
        else -> currentGroupId
    }
}

internal fun taskActionPresentation(
    currentDriverId: String,
    taskStatus: String?,
    availabilityMode: String?,
    queuePurpose: String? = null,
    assignments: List<DriverAssignmentEntity>,
    locallyPending: Boolean,
    hasCurrentGroup: Boolean = true,
    operationalAvailability: String = "AVAILABLE",
): TaskActionPresentation {
    val isIndividualLogistics = queuePurpose == LOGISTICS_DRIVER_QUEUE_PURPOSE
    val liveAssignments = assignments.filter { it.status == "ACTIVE" || it.status == "PAUSED" }
    val currentDriverHasActiveAssignment = liveAssignments.any {
        it.driverId == currentDriverId && it.status == "ACTIVE"
    }
    val currentDriverHasPausedAssignment = liveAssignments.any {
        it.driverId == currentDriverId && it.status == "PAUSED"
    }
    val assignedOnlyToOthers = liveAssignments.isNotEmpty() &&
        liveAssignments.none { it.driverId == currentDriverId }
    val candidateActions = when {
        taskStatus == "WAITING" &&
            availabilityMode != "SECONDARY_PENDING" &&
            liveAssignments.isEmpty() ->
            listOf(DriverTaskAction.TAKE)
        taskStatus == "IN_PROGRESS" && currentDriverHasActiveAssignment ->
            listOf(DriverTaskAction.PAUSE, DriverTaskAction.COMPLETE)
        taskStatus == "PAUSED" && currentDriverHasPausedAssignment ->
            listOf(DriverTaskAction.RESUME)
        else -> emptyList()
    }
    val actions = when {
        operationalAvailability != "AVAILABLE" -> emptyList()
        !isIndividualLogistics &&
            !hasCurrentGroup &&
            DriverTaskAction.TAKE in candidateActions ->
            emptyList()
        else -> candidateActions
    }
    val performers = liveAssignments.mapNotNull { assignment ->
        assignment.driverName
            ?: assignment.driverGroupName
            ?: assignment.driverId
    }.distinct()
    val message = when {
        locallyPending -> "Действие ожидает синхронизации"
        operationalAvailability != "AVAILABLE" -> "Рабочий временно недоступен"
        !isIndividualLogistics &&
            !hasCurrentGroup &&
            DriverTaskAction.TAKE in candidateActions ->
            "Руководитель ещё не выбрал текущую группу"
        taskStatus == "WAITING" && availabilityMode == "SECONDARY_PENDING" ->
            "Ожидает основного исполнителя"
        actions.isNotEmpty() -> null
        assignedOnlyToOthers -> "Задание выполняет другой рабочий"
        else -> "Действия недоступны в текущем состоянии"
    }
    return TaskActionPresentation(
        actions = actions,
        actionsEnabled = actions.isNotEmpty() && !locallyPending,
        message = message,
        performers = performers,
        takeLabel = "Взять",
    )
}

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

internal const val LOGISTICS_DRIVER_QUEUE_PURPOSE = "LOGISTICS_DRIVER"
