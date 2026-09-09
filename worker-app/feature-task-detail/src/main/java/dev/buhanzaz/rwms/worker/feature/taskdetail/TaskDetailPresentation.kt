package dev.buhanzaz.rwms.worker.feature.taskdetail

import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerMediaReferenceDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskTimerSnapshotDto
import dev.buhanzaz.rwms.worker.core.network.WorkerWorkDto
import dev.buhanzaz.rwms.worker.core.ui.isInterwarehouseTransferTask
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class EvidencePresentation(
    val status: String,
    val message: String?,
    val canRetryReservation: Boolean,
)

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class PhotoCapturePresentation(
    val enabled: Boolean,
    val message: String?,
)

/** A photo can be captured only by an active participant of an in-progress synchronized task. */
internal fun photoCapturePresentation(
    hasLoadedDetail: Boolean,
    taskStatus: String?,
    currentWorkerId: String,
    queuePurpose: String?,
    assignments: List<WorkerAssignmentEntity>,
    locallyPending: Boolean,
): PhotoCapturePresentation = when {
    !hasLoadedDetail ->
        PhotoCapturePresentation(
            enabled = false,
            message = "Карточка задания ещё не синхронизирована. Добавление фото станет доступно после синхронизации.",
        )
    locallyPending -> PhotoCapturePresentation(
        enabled = false,
        message = "Действие по заданию ожидает синхронизации. Добавление фото станет доступно после подтверждения.",
    )
    taskStatus == "PAUSED" -> PhotoCapturePresentation(
        enabled = false,
        message = "Сначала продолжите задание, затем можно добавить фотографию.",
    )
    taskStatus != "IN_PROGRESS" -> PhotoCapturePresentation(
        enabled = false,
        message = "Добавление фото доступно только во время выполнения задания.",
    )
    assignments.none { assignment ->
        assignment.workerId == currentWorkerId &&
            assignment.status == "ACTIVE" &&
            (
                queuePurpose != LOGISTICS_DRIVER_QUEUE_PURPOSE ||
                    assignment.workerGroupId != null
                )
    } -> PhotoCapturePresentation(
        enabled = false,
        message = "Сначала возьмите задание, затем можно добавить фотографию.",
    )
    else -> PhotoCapturePresentation(enabled = true, message = null)
}

internal fun evidencePresentation(
    state: String,
    uploadPercent: Int,
    mediaId: String?,
    hasValidReservationPayload: Boolean,
): EvidencePresentation = when (state) {
    "CAPTURED" -> EvidencePresentation(
        status = "",
        message = null,
        canRetryReservation = false,
    )
    "RESERVED" -> EvidencePresentation(
        status = "",
        message = null,
        canRetryReservation = false,
    )
    "UPLOADING" -> EvidencePresentation(
        status = "",
        message = null,
        canRetryReservation = false,
    )
    "PROCESSING" -> EvidencePresentation(
        status = "",
        message = null,
        canRetryReservation = false,
    )
    "READY" -> EvidencePresentation(
        status = "",
        message = null,
        canRetryReservation = false,
    )
    "REVIEW_REQUIRED" -> if (mediaId == null && hasValidReservationPayload) {
        EvidencePresentation(
            status = "Ожидает повторной отправки",
            message = "Исходная попытка сохранена и будет отправлена без потери фото.",
            canRetryReservation = true,
        )
    } else if (mediaId == null) {
        EvidencePresentation(
            status = "Нужно новое фото",
            message = "Срок старой попытки истёк — снимите фото заново.",
            canRetryReservation = false,
        )
    } else {
        EvidencePresentation(
            status = "Требуется проверка",
            message = "Фото сохранено на сервере и ожидает проверки.",
            canRetryReservation = false,
        )
    }
    "REJECTED" -> EvidencePresentation(
        status = "Отклонено",
        message = "Добавьте новое фото результата.",
        canRetryReservation = false,
    )
    else -> EvidencePresentation(
        status = "Синхронизация",
        message = "Проверяем состояние фотографии.",
        canRetryReservation = false,
    )
}

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class WorkPresentation(
    val quantity: String,
    val plannedDuration: String?,
    val comment: String?,
)

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class TaskSourceMediaPresentation(
    val general: List<WorkerMediaReferenceDto>,
    val byWorkId: Map<String, List<WorkerMediaReferenceDto>>,
)

/** Exact sibling task step rendered as part of a transfer trip without inventing stop semantics. */
internal data class TransferRouteStepPresentation(
    val ordinal: Int,
    val label: String,
    val status: String,
)

/**
 * Worker-facing transfer projection derived only from the current public task-detail contract.
 * A missing route is explicit because warehouse identities are not yet part of that contract.
 */
internal data class TransferTaskPresentation(
    val route: String?,
    val cargoLines: List<String>,
    val materialLines: List<String>,
    val comments: List<String>,
    val routeSteps: List<TransferRouteStepPresentation>,
    val instructions: List<String>,
)

/**
 * Builds a transfer card only for a logistics-document task carrying canonical transfer text.
 * The function never parses UUIDs into warehouse or cabin labels and never synthesizes cargo.
 */
internal fun transferTaskPresentation(detail: WorkerTaskDetailDto): TransferTaskPresentation? {
    if (detail.source?.type != "LOGISTICS_DRIVER_TASK" ||
        !isInterwarehouseTransferTask(detail.title, detail.description, detail.taskText)
    ) {
        return null
    }
    val transferText = listOfNotNull(detail.taskText, detail.description)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val cabinNumbers = transferText.firstNotNullOfOrNull { text ->
        text.substringAfterMarker("Бытовки:")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.takeIf(List<String>::isNotEmpty)
    }.orEmpty()
    val furnitureSummary = transferText.firstNotNullOfOrNull { text ->
        text.substringAfterMarker("Межскладской груз:")
    }
    val cargoLines = when {
        cabinNumbers.isNotEmpty() -> cabinNumbers
        !furnitureSummary.isNullOrBlank() -> listOf(furnitureSummary)
        else -> transferText.take(1)
    }
    val route = transferText.firstNotNullOfOrNull(::routeLabelOrNull)
    val materialLines = detail.materials.map { material ->
        "${material.name} — ${quantityLabel(material.quantity, material.unit)}"
    }
    val comments = detail.comments.map { comment ->
        listOfNotNull(
            comment.authorDisplayName?.trim()?.takeIf(String::isNotEmpty),
            comment.text.trim().takeIf(String::isNotEmpty),
        ).joinToString(": ")
    }.filter(String::isNotEmpty)
    val routeSteps = detail.relatedSteps
        .sortedBy { it.routeIndex }
        .mapIndexed { index, step ->
            TransferRouteStepPresentation(
                ordinal = index + 1,
                label = step.taskText?.trim()?.takeIf(String::isNotEmpty) ?: step.queueName,
                status = step.status,
            )
        }
    return TransferTaskPresentation(
        route = route,
        cargoLines = cargoLines,
        materialLines = materialLines,
        comments = comments,
        routeSteps = routeSteps,
        instructions = listOf(
            "Сверьте груз с карточкой перед выездом",
            "Подтвердите погрузку фотографиями во время задания",
            "Следуйте по маршруту, назначенному логистом",
            "Выгрузите только подтверждённый груз",
            "Добавьте фото выгрузки и завершите задание",
        ),
    )
}

private fun String.substringAfterMarker(marker: String): String? {
    val markerIndex = indexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null
    return substring(markerIndex + marker.length).trim().takeIf(String::isNotEmpty)
}

private fun routeLabelOrNull(value: String): String? {
    val line = value.lineSequence().firstOrNull { candidate -> '→' in candidate } ?: return null
    val parts = line.split('→', limit = 2).map(String::trim)
    if (parts.size != 2 || parts.any(String::isEmpty)) return null
    return "${parts[0]} → ${parts[1]}"
}

/** Compact timer state rendered in the task app bar after the worker takes the task. */
internal data class TaskHeaderTimerPresentation(
    val label: String,
    val countdown: String?,
    val running: Boolean,
)

internal fun taskSourceMediaPresentation(
    works: List<WorkerWorkDto>,
    sourceMedia: List<WorkerMediaReferenceDto>,
): TaskSourceMediaPresentation {
    val mediaById = sourceMedia.associateBy(WorkerMediaReferenceDto::mediaId)
    val byWorkId = works.associate { work ->
        work.id to work.sourceMediaIds.mapNotNull(mediaById::get)
    }
    val workMediaIds = works.flatMapTo(mutableSetOf()) { it.sourceMediaIds }
    return TaskSourceMediaPresentation(
        general = sourceMedia.filterNot { it.mediaId in workMediaIds },
        byWorkId = byWorkId,
    )
}

/**
 * Keeps the task-board timer authoritative while giving immediate feedback for an optimistic take.
 * The client never invents elapsed work: it starts decrementing only after a WORKING snapshot arrives.
 */
internal fun taskHeaderTimerPresentation(
    taskStatus: String?,
    locallyPending: Boolean,
    timerState: String?,
    remaining: String?,
): TaskHeaderTimerPresentation? {
    if (taskStatus != "IN_PROGRESS") return null
    if (locallyPending) return null
    return when (timerState) {
        "WORKING" -> TaskHeaderTimerPresentation(
            label = "Осталось",
            countdown = remaining ?: "—",
            running = remaining != null,
        )
        "BREAK" -> TaskHeaderTimerPresentation("Перерыв", remaining, running = false)
        "OFF_SHIFT" -> TaskHeaderTimerPresentation("Вне смены", remaining, running = false)
        "PAUSED" -> TaskHeaderTimerPresentation("Таймер на паузе", remaining, running = false)
        else -> TaskHeaderTimerPresentation("Таймер", "—", running = false)
    }
}

/** Worker-facing work metadata deliberately has no price/cost field. */
internal fun workPresentation(work: WorkerWorkDto): WorkPresentation = WorkPresentation(
    quantity = quantityLabel(work.quantity, work.unit),
    plannedDuration = work.durationMinutes?.let(::plannedDurationLabel),
    comment = work.comment?.trim()?.takeIf(String::isNotBlank),
)

/**
 * Defines worker UI/presentation state; it does not decide a server task transition.
 */
internal data class TaskTimingPresentation(
    val activeElapsed: String,
    val activeLabel: String,
    val planned: String? = null,
    val remaining: String? = null,
    val remainingPercent: String? = null,
    val nextTransitionAt: String? = null,
)

/**
 * Presents the schedule-aware values exactly as anchored by the service.
 * The device deliberately does not extend this snapshot with wall-clock time:
 * doing so would count breaks or off-shift time before the next refresh.
 */
internal fun taskTimingPresentation(
    snapshot: WorkerTaskTimerSnapshotDto?,
    plannedDurationMinutes: Int? = null,
    elapsedSinceSnapshotSeconds: Long = 0,
): TaskTimingPresentation {
    if (snapshot == null) {
        return TaskTimingPresentation(
            planned = plannedDurationMinutes?.let(::plannedDurationLabel),
            activeElapsed = "—",
            activeLabel = "Серверный таймер недоступен",
        )
    }
    val projected = snapshot.projectedAfter(elapsedSinceSnapshotSeconds)
    return TaskTimingPresentation(
        planned = plannedDurationMinutes?.let(::plannedDurationLabel),
        activeElapsed = elapsedDurationLabel(projected.countedActiveSeconds),
        activeLabel = when (projected.timerState) {
            "WORKING" -> "В работе"
            "BREAK" -> "Перерыв"
            "OFF_SHIFT" -> "Вне смены"
            "PAUSED" -> "Пауза"
            "DONE" -> "Фактическое время"
            else -> "Серверный таймер"
        },
        remaining = projected.remainingSeconds?.let(::signedDurationLabel),
        remainingPercent = projected.remainingPercent?.let {
            String.format(Locale.forLanguageTag("ru-RU"), "%.1f%%", it)
        },
        nextTransitionAt = projected.nextTransitionAt,
    )
}

internal fun WorkerTaskTimerSnapshotDto.projectedAfter(elapsedSeconds: Long): WorkerTaskTimerSnapshotDto {
    if (timerState != "WORKING" || elapsedSeconds <= 0) return this
    val secondsUntilTransition = nextTransitionAt?.let { transition ->
        runCatching {
            Duration.between(Instant.parse(serverTime), Instant.parse(transition)).seconds.coerceAtLeast(0)
        }.getOrNull()
    }
    val countedElapsed = secondsUntilTransition
        ?.let { elapsedSeconds.coerceAtMost(it) }
        ?: elapsedSeconds
    val projectedRemaining = remainingSeconds?.minus(countedElapsed)
    val originalBudget = remainingSeconds?.plus(countedActiveSeconds)
    val projectedPercent = if (projectedRemaining != null && originalBudget != null && originalBudget > 0) {
        projectedRemaining.toDouble() * 100.0 / originalBudget.toDouble()
    } else {
        remainingPercent
    }
    return copy(
        countedActiveSeconds = countedActiveSeconds + countedElapsed,
        remainingSeconds = projectedRemaining,
        remainingPercent = projectedPercent,
    )
}

/** Legacy calculation retained for old cached-fixture tests; production UI uses the server snapshot above. */
internal fun taskTimingPresentation(
    plannedDurationMinutes: Int?,
    activeStartedAt: String?,
    activeWorkSeconds: Long,
    status: String?,
    now: Instant,
): TaskTimingPresentation = TaskTimingPresentation(
    activeElapsed = elapsedDurationLabel(
        activeElapsedSeconds(
            activeStartedAt = activeStartedAt,
            activeWorkSeconds = activeWorkSeconds,
            status = status,
            now = now,
        ),
    ),
    activeLabel = if (status == "IN_PROGRESS" && activeStartedAt != null) "В работе" else "Фактическое время",
    planned = plannedDurationMinutes?.let(::plannedDurationLabel),
)

internal fun activeElapsedSeconds(
    activeStartedAt: String?,
    activeWorkSeconds: Long,
    status: String?,
    now: Instant,
): Long {
    val saved = activeWorkSeconds.coerceAtLeast(0)
    if (status != "IN_PROGRESS" || activeStartedAt == null) return saved
    val startedAt = runCatching { Instant.parse(activeStartedAt) }.getOrNull() ?: return saved
    return saved + Duration.between(startedAt, now).seconds.coerceAtLeast(0)
}

internal fun plannedDurationLabel(minutes: Int): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    val hours = safeMinutes / 60
    val remainingMinutes = safeMinutes % 60
    return when {
        hours == 0 -> "$remainingMinutes мин"
        remainingMinutes == 0 -> "$hours ч"
        else -> "$hours ч $remainingMinutes мин"
    }
}

internal fun elapsedDurationLabel(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3_600
    val minutes = (safeSeconds % 3_600) / 60
    val remainder = safeSeconds % 60
    return "%d:%02d:%02d".format(hours, minutes, remainder)
}

internal fun signedDurationLabel(seconds: Long): String {
    val sign = if (seconds < 0) "−" else ""
    return sign + elapsedDurationLabel(kotlin.math.abs(seconds))
}

internal fun WorkerTaskEntity.serverTimerSnapshotOrNull(): WorkerTaskTimerSnapshotDto? {
    val countedActiveSeconds = timerCountedActiveSeconds ?: return null
    val timerState = timerState ?: return null
    val serverTime = timerServerTime ?: return null
    return WorkerTaskTimerSnapshotDto(
        countedActiveSeconds = countedActiveSeconds,
        remainingSeconds = timerRemainingSeconds,
        remainingPercent = timerRemainingPercent,
        timerState = timerState,
        nextTransitionAt = timerNextTransitionAt,
        serverTime = serverTime,
    )
}

private fun quantityLabel(quantity: Double, unit: String?): String {
    val amount = BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()
    return listOf(amount, unit?.trim()?.takeIf(String::isNotBlank)).joinToString(" ")
}
