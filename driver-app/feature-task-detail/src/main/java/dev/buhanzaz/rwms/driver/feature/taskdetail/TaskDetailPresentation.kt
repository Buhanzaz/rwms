package dev.buhanzaz.rwms.driver.feature.taskdetail

import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import dev.buhanzaz.rwms.driver.core.network.DriverTripDetailsDto
import dev.buhanzaz.rwms.driver.core.network.DriverTripDesiredDeliveryWindowDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskTimerSnapshotDto
import dev.buhanzaz.rwms.driver.core.network.DriverMediaReferenceDto
import dev.buhanzaz.rwms.driver.core.network.DriverWorkDto
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal data class EvidencePresentation(
    val status: String,
    val message: String?,
    val canRetryReservation: Boolean,
)

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal data class PhotoCapturePresentation(
    val enabled: Boolean,
    val message: String?,
)

/** A visible photo control must never silently ignore a tap while detail loads. */
internal fun photoCapturePresentation(hasLoadedDetail: Boolean): PhotoCapturePresentation =
    if (hasLoadedDetail) {
        PhotoCapturePresentation(enabled = true, message = null)
    } else {
        PhotoCapturePresentation(
            enabled = false,
            message = "Загружаем карточку задания. Добавление фото станет доступно после синхронизации.",
        )
    }

internal fun evidencePresentation(
    state: String,
    uploadPercent: Int,
    mediaId: String?,
    hasValidReservationPayload: Boolean,
): EvidencePresentation = when (state) {
    "CAPTURED" -> EvidencePresentation(
        status = "Ожидает отправки",
        message = "Фото сохранено на устройстве и будет отправлено при синхронизации.",
        canRetryReservation = false,
    )
    "RESERVED" -> EvidencePresentation(
        status = "Подготовка к отправке",
        message = null,
        canRetryReservation = false,
    )
    "UPLOADING" -> EvidencePresentation(
        status = "Загрузка · ${uploadPercent.coerceIn(0, 100)}%",
        message = null,
        canRetryReservation = false,
    )
    "PROCESSING" -> EvidencePresentation(
        status = "Обработка",
        message = "Фото загружено и обрабатывается.",
        canRetryReservation = false,
    )
    "READY" -> EvidencePresentation(
        status = "Готово",
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
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal data class WorkPresentation(
    val quantity: String,
    val plannedDuration: String?,
    val comment: String?,
)

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal data class TaskSourceMediaPresentation(
    val general: List<DriverMediaReferenceDto>,
    val byWorkId: Map<String, List<DriverMediaReferenceDto>>,
)

internal fun taskSourceMediaPresentation(
    works: List<DriverWorkDto>,
    sourceMedia: List<DriverMediaReferenceDto>,
): TaskSourceMediaPresentation {
    val mediaById = sourceMedia.associateBy(DriverMediaReferenceDto::mediaId)
    val byWorkId = works.associate { work ->
        work.id to work.sourceMediaIds.mapNotNull(mediaById::get)
    }
    val workMediaIds = works.flatMapTo(mutableSetOf()) { it.sourceMediaIds }
    return TaskSourceMediaPresentation(
        general = sourceMedia.filterNot { it.mediaId in workMediaIds },
        byWorkId = byWorkId,
    )
}

/** Driver-facing labels for one cabin member of a logistics trip. */
internal data class DriverTripCabinPresentation(
    val cabinId: String,
    val unitNumber: String,
    val desiredContents: List<String>,
    val actualContents: List<String>,
    val movementTaskCreated: Boolean,
    val movementTaskCompleted: Boolean,
    val contentReady: Boolean,
)

/** Preserves one presentation card per cabin without merging grouped-trip members. */
internal fun driverTripCabinPresentations(
    trip: DriverTripDetailsDto,
): List<DriverTripCabinPresentation> = trip.cabins.map { cabin ->
    DriverTripCabinPresentation(
        cabinId = cabin.cabinId,
        unitNumber = cabin.unitNumber,
        desiredContents = cabin.desiredContents.map { equipment ->
            "${equipment.equipmentName}: ${equipment.quantity}"
        },
        actualContents = cabin.actualContents.map { equipment ->
            val name = equipment.equipmentName?.takeIf(String::isNotBlank)
                ?: "Оборудование ${equipment.equipmentId}"
            "$name: ${equipment.quantity} · ${equipment.locationKind}"
        },
        movementTaskCreated = cabin.movementTaskCreated,
        movementTaskCompleted = cabin.movementTaskCompleted,
        contentReady = cabin.contentReady,
    )
}

/** Maps existing logistics operation enum names to concise driver-facing wording. */
internal fun driverTripOperationLabel(operationType: String): String = when (operationType) {
    "SHIPMENT" -> "Доставка / аренда"
    "RETURN" -> "Вывоз"
    "TRANSFER" -> "Перемещение"
    else -> operationType
}

/** Formats one advisory client-requested date or date range. */
internal fun desiredDeliveryWindowLabel(window: DriverTripDesiredDeliveryWindowDto): String {
    return if (window.startDate == window.endDate) {
        displayDate(window.startDate)
    } else {
        "${displayDate(window.startDate)}–${displayDate(window.endDate)}"
    }
}

/** Formats the logistics-assigned date, never substituting a client preference. */
internal fun scheduledTripLabel(date: String): String = displayDate(date)

internal fun isLogisticsDriverTaskSource(type: String?): Boolean =
    type == "LOGISTICS_DRIVER_TASK"

private fun displayDate(raw: String): String = runCatching {
    LocalDate.parse(raw).format(DRIVER_DATE_FORMATTER)
}.getOrDefault(raw)

private val DRIVER_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Driver-facing work metadata deliberately has no price/cost field. */
internal fun workPresentation(work: DriverWorkDto): WorkPresentation = WorkPresentation(
    quantity = quantityLabel(work.quantity, work.unit),
    plannedDuration = work.durationMinutes?.let(::plannedDurationLabel),
    comment = work.comment?.trim()?.takeIf(String::isNotBlank),
)

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
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
    snapshot: DriverTaskTimerSnapshotDto?,
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
            "BREAK" -> "Перерыв · таймер остановлен"
            "OFF_SHIFT" -> "Вне смены · таймер остановлен"
            "PAUSED" -> "Пауза · таймер остановлен"
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

internal fun DriverTaskTimerSnapshotDto.projectedAfter(elapsedSeconds: Long): DriverTaskTimerSnapshotDto {
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

internal fun DriverTaskEntity.serverTimerSnapshotOrNull(): DriverTaskTimerSnapshotDto? {
    val countedActiveSeconds = timerCountedActiveSeconds ?: return null
    val timerState = timerState ?: return null
    val serverTime = timerServerTime ?: return null
    return DriverTaskTimerSnapshotDto(
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
