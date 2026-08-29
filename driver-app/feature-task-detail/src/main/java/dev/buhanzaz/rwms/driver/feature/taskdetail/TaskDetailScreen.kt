package dev.buhanzaz.rwms.driver.feature.taskdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.network.DriverTripDetailsDto
import dev.buhanzaz.rwms.driver.core.network.TaskEvidenceDto
import dev.buhanzaz.rwms.driver.core.network.DriverMediaReferenceDto
import dev.buhanzaz.rwms.driver.core.network.DriverWorkDto
import dev.buhanzaz.rwms.driver.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.driver.core.ui.DriverKpiColorRange
import dev.buhanzaz.rwms.driver.core.ui.DriverScreenScaffold
import dev.buhanzaz.rwms.driver.core.ui.cabinNumberForDisplay
import dev.buhanzaz.rwms.driver.core.ui.driverKpiTimeColor
import java.time.LocalDate
import kotlinx.coroutines.delay

/** Renders one task, its evidence and the actions allowed by the synchronized server state. */
@Composable
fun TaskDetailScreen(
    userId: String,
    entryId: String,
    onBack: (() -> Unit)?,
    onCamera: (routeIndex: Int) -> Unit,
    onMedia: (title: String, readPaths: List<String>) -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId, entryId) { viewModel.bind(userId, entryId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail = state.detail
    val task = state.task
    val session = state.session
    val cabinNumber = cabinNumberForDisplay(task?.unitNumber, detail?.taskObject?.label)
    val displayedStatus = effectiveTaskStatus(task = task, serverStatus = detail?.status)
    val photoCapture = photoCapturePresentation(
        hasLoadedDetail = detail != null,
        currentDriverId = userId,
        effectiveTaskStatus = displayedStatus,
        assignments = state.assignments,
        hasPendingTake = state.hasPendingTake,
    )
    val readyServerEvidence = detail?.evidence.orEmpty()
        .filter { evidence -> evidence.state == "READY" && !evidence.readPath.isNullOrBlank() }
    val readyEvidenceIds = readyServerEvidence.mapTo(mutableSetOf()) { it.evidenceId }.apply {
        addAll(detail?.evidence.orEmpty().filter { it.state == "READY" }.map { it.evidenceId })
        addAll(state.evidence.filter { it.state == "READY" }.map { it.evidenceId })
    }
    val readyEvidenceCount = readyEvidenceIds.size
    var explicitlySelectedEvidenceId by remember(entryId, readyEvidenceIds) {
        mutableStateOf<String?>(null)
    }
    val selectedCompletionEvidenceId = completionEvidenceId(
        queuePurpose = state.queuePurpose,
        readyEvidenceIds = readyEvidenceIds,
        selectedEvidenceId = explicitlySelectedEvidenceId,
    )
    val localEvidenceWithoutServerPhoto = state.evidence.filterNot { local ->
        readyServerEvidence.any { remote -> remote.evidenceId == local.evidenceId }
    }
    val evidenceCount = evidenceCountPresentation(
        readyEvidenceCount = readyEvidenceCount,
        locallyStoredEvidenceStates = state.evidence.map(TaskEvidenceEntity::state),
    )
    val plannedDurationMinutes = detail?.plannedDurationMinutes ?: task?.plannedDurationMinutes
    val timerSnapshot = detail?.timerSnapshot ?: task?.serverTimerSnapshotOrNull()
    var elapsedSinceSnapshotSeconds by remember(timerSnapshot) { mutableLongStateOf(0L) }
    LaunchedEffect(timerSnapshot) {
        while (timerSnapshot?.timerState == "WORKING") {
            delay(1_000)
            elapsedSinceSnapshotSeconds += 1
        }
    }
    val timing = taskTimingPresentation(
        snapshot = timerSnapshot,
        plannedDurationMinutes = plannedDurationMinutes,
        elapsedSinceSnapshotSeconds = elapsedSinceSnapshotSeconds,
    )
    val kpiTimeColor = driverKpiTimeColor(
        remainingPercent = timerSnapshot?.projectedAfter(elapsedSinceSnapshotSeconds)?.remainingPercent,
        ranges = state.kpiPalette?.ranges.orEmpty().map {
            DriverKpiColorRange(it.fromPercent, it.toPercent, it.color)
        },
        overdueColor = state.kpiPalette?.overdueColor,
    )
    val sourceMediaPresentation = taskSourceMediaPresentation(
        works = detail?.works.orEmpty(),
        sourceMedia = detail?.sourceMedia.orEmpty(),
    )
    val generalSourceMedia = sourceMediaPresentation.general
    val showsRichLogisticsDetails = canReadRichLogisticsDetails(
        sourceType = detail?.source?.type,
        driverAudienceMode = task?.driverAudienceMode,
        scheduledDate = detail?.scheduledDate ?: task?.scheduledDate,
        today = LocalDate.now(),
    )
    val canClaimExtraTask = canClaimFutureLogisticsTask(
        sourceType = detail?.source?.type,
        driverAudienceMode = task?.driverAudienceMode,
        scheduledDate = detail?.scheduledDate ?: task?.scheduledDate,
        today = LocalDate.now(),
    )
    val context = LocalContext.current
    LaunchedEffect(timerSnapshot?.nextTransitionAt, timerSnapshot?.serverTime) {
        val snapshot = timerSnapshot ?: return@LaunchedEffect
        val nextTransitionAt = snapshot.nextTransitionAt ?: return@LaunchedEffect
        val transitionDelay = runCatching {
            java.time.Duration.between(
                java.time.Instant.parse(snapshot.serverTime),
                java.time.Instant.parse(nextTransitionAt),
            ).toMillis()
        }.getOrNull() ?: return@LaunchedEffect
        if (transitionDelay > 0) delay(transitionDelay)
        viewModel.refresh()
    }
    val ordinaryActionPresentation = taskActionPresentation(
        currentDriverId = userId,
        taskStatus = displayedStatus,
        availabilityMode = detail?.availabilityMode,
        queuePurpose = state.queuePurpose,
        assignments = state.assignments,
        locallyPending = task?.locallyPending == true,
        hasCurrentGroup = session?.currentGroupId != null,
        operationalAvailability = session?.operationalAvailability ?: "DISABLED",
    )
    val actionPresentation = when {
        canClaimExtraTask && !state.extraTaskClaimed -> ordinaryActionPresentation.copy(
            actions = emptyList(),
            actionsEnabled = false,
            message = "Сначала возьмите будущую ходку кнопкой ниже",
        )
        state.extraTaskClaimed && task?.driverAudienceMode == WAREHOUSE_DRIVERS_AUDIENCE_MODE ->
            ordinaryActionPresentation.copy(
                actions = emptyList(),
                actionsEnabled = false,
                message = "Ждём обновлённое назначение с сервера",
            )
        else -> ordinaryActionPresentation
    }
    LaunchedEffect(readyEvidenceCount) { viewModel.refresh() }
    DriverScreenScaffold(title = detail?.title ?: state.task?.title ?: "Задание", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        displayedStatus?.let { TaskStatusChip(it) }
                        detail?.availabilityMode?.let {
                            Text(
                                when (it) {
                                    "MANDATORY" -> "Обязательное"
                                    "REQUIRED_JOIN" -> "Ожидает смежного исполнителя"
                                    "SECONDARY_PENDING" -> "Ожидает основного исполнителя"
                                    "OPTIONAL_JOIN" -> "Выполняет смежная группа"
                                    else -> "Доступное"
                                },
                            )
                        }
                    }
                    cabinNumber?.let { Text("Бытовка: $it") }
                    (detail?.taskId ?: task?.taskId)?.let { Text("Номер задания: $it") }
                    detail?.description?.let { Text(it) }
                    detail?.taskText?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Text(
                        "Плановое время: ${timing.planned ?: "не задано"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${timing.activeLabel}: ${timing.activeElapsed}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    timing.remaining?.let { remaining ->
                        Text(
                            "Осталось: $remaining${timing.remainingPercent?.let { " · $it" }.orEmpty()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = kpiTimeColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (kpiTimeColor == null) FontWeight.Normal else FontWeight.Bold,
                        )
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (state.queuePurpose != LOGISTICS_DRIVER_QUEUE_PURPOSE) {
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Текущая группа", style = MaterialTheme.typography.titleSmall)
                        Text(session?.currentGroupName ?: "Не выбрана руководителем")
                        if (session?.operationalAvailability == "DISABLED") {
                            Text(
                                "Группа временно недоступна",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        logisticsTaskAudienceLabel(task?.driverAudienceMode),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showsRichLogisticsDetails) {
                if (state.tripRefreshInProgress) {
                    item {
                        Text(
                            "Обновляем данные ходки…",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.tripRefreshError?.let { tripError ->
                    item {
                        Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(tripError, color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = viewModel::refresh) {
                                    Text("Повторить обновление")
                                }
                            }
                        }
                    }
                }
                state.tripDetails?.let { trip ->
                    item {
                        DriverTripDetailsBlock(
                            trip = trip,
                            onOpenInYandexMaps = { openYandexMapsRoute(context, it) },
                        )
                    }
                }
                if (
                    state.tripRefreshComplete &&
                    state.tripDetails == null &&
                    state.tripRefreshError == null
                ) {
                    item {
                        Text(
                            "Подробности ходки для этого задания не сформированы.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (canClaimExtraTask || state.extraTaskClaimed) {
                item {
                    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.extraTaskClaimed) {
                                Text(
                                    "Дополнительная ходка назначена вам",
                                    modifier = Modifier.testTag("extra-task-claimed"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Очередь обновляется с сервера.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "Дополнительное задание",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "Проверьте адрес и маршрут, затем закрепите ходку за собой.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    onClick = viewModel::claimExtraTask,
                                    enabled = !state.extraTaskClaimInProgress,
                                    modifier = Modifier.fillMaxWidth().testTag("claim-extra-task"),
                                ) {
                                    Text(
                                        if (state.extraTaskClaimInProgress) {
                                            "Закрепляем…"
                                        } else {
                                            "Взять доп. задание"
                                        },
                                    )
                                }
                            }
                            state.extraTaskClaimError?.let { claimError ->
                                Text(claimError, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButtons(
                        presentation = actionPresentation,
                        completionAllowed = (
                            (detail?.completionAllowed == true) ||
                                (task?.let { readyEvidenceCount >= it.resultPhotoMinCount } == true)
                            ) &&
                            (
                                state.queuePurpose != LOGISTICS_DRIVER_QUEUE_PURPOSE ||
                                    selectedCompletionEvidenceId != null
                                ),
                        onAction = {
                            viewModel.perform(
                                action = it.wireValue,
                                evidenceId = selectedCompletionEvidenceId,
                            )
                        },
                    )
                    if (
                        state.queuePurpose == LOGISTICS_DRIVER_QUEUE_PURPOSE &&
                        DriverTaskAction.COMPLETE in actionPresentation.actions
                    ) {
                        when {
                            readyEvidenceIds.isEmpty() ->
                                Text(
                                    "Для завершения добавьте фотографию бытовки",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            readyEvidenceIds.size == 1 ->
                                Text(
                                    "Единственная готовая фотография выбрана автоматически",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            else -> {
                                Text(
                                    "Выберите фотографию, которая станет титульной",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                readyEvidenceIds.sorted().forEachIndexed { index, evidenceId ->
                                    OutlinedButton(
                                        onClick = { explicitlySelectedEvidenceId = evidenceId },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if (explicitlySelectedEvidenceId == evidenceId) {
                                                "Фото ${index + 1} · выбрано"
                                            } else {
                                                "Выбрать фото ${index + 1}"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    actionPresentation.message?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (actionPresentation.performers.isNotEmpty()) {
                        Text(
                            "Исполнители: ${actionPresentation.performers.joinToString()}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onCamera(requireNotNull(detail).routeIndex) },
                        enabled = photoCapture.enabled,
                    ) { Text("Добавить фото") }
                    Text(evidenceCount.readyLabel)
                }
                evidenceCount.locallyStoredNotReadyLabel?.let { label ->
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                photoCapture.message?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (detail?.materials?.isNotEmpty() == true) {
                item { Text("Материалы", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
                items(detail.materials, key = { it.id }) { material ->
                    Text(
                        "${material.name}: ${material.quantity} ${material.unit.orEmpty()}",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (detail?.works?.isNotEmpty() == true) {
                item { Text("Работа на складе", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
                items(detail.works, key = { it.id }) { work ->
                    val media = sourceMediaPresentation.byWorkId[work.id].orEmpty()
                    WorkRow(
                        work = work,
                        sourceMedia = media,
                        onMedia = {
                            onMedia(
                                "${detail.title} · ${work.name}",
                                media.map(DriverMediaReferenceDto::readPath).filter(String::isNotBlank),
                            )
                        },
                    )
                }
            }
            if (generalSourceMedia.isNotEmpty()) {
                item { Text("Общие фото", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
                items(generalSourceMedia, key = { it.mediaId }) { media ->
                    MediaRow(
                        media,
                        onClick = {
                            onMedia(
                                detail?.title ?: task?.title ?: "Задание",
                                generalSourceMedia.map { it.readPath }.filter(String::isNotBlank),
                            )
                        },
                    )
                }
            }
            if (readyServerEvidence.isNotEmpty() || localEvidenceWithoutServerPhoto.isNotEmpty()) {
                item { Text("Фото результата", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
                if (readyServerEvidence.isNotEmpty()) {
                    items(readyServerEvidence, key = { "server-${it.evidenceId}" }) { evidence ->
                        ServerEvidenceRow(
                            evidence = evidence,
                            onClick = {
                                onMedia(
                                    "${detail?.title ?: task?.title ?: "Задание"} · фото результата",
                                    readyServerEvidence.mapNotNull { it.readPath?.takeIf(String::isNotBlank) },
                                )
                            },
                        )
                    }
                }
                items(localEvidenceWithoutServerPhoto, key = { "local-${it.evidenceId}" }) { evidence ->
                    EvidenceRow(
                        evidence = evidence,
                        hasValidReservationPayload = evidence.evidenceId in state.retryableEvidenceIds,
                        onRetry = { viewModel.retryEvidence(evidence.evidenceId) },
                    )
                }
            }
            if (detail?.comments?.isNotEmpty() == true) {
                item { Text("Комментарии", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
                items(detail.comments, key = { it.id }) { comment ->
                    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(comment.authorDisplayName ?: "RWMS", style = MaterialTheme.typography.labelMedium)
                            Text(comment.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriverTripDetailsBlock(
    trip: DriverTripDetailsDto,
    onOpenInYandexMaps: (DriverTripDetailsDto) -> Unit,
) {
    val primaryContact = listOfNotNull(
        trip.primaryContactName?.takeIf(String::isNotBlank),
        trip.primaryContactPhone?.takeIf(String::isNotBlank),
    ).joinToString(" · ").ifBlank { "не указан" }
    val coordinates = listOfNotNull(
        trip.latitude?.let { "широта $it" },
        trip.longitude?.let { "долгота $it" },
    ).joinToString(" · ").ifBlank { "не указаны" }
    val cabins = driverTripCabinPresentations(trip)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ходка №${trip.tripNumber}", style = MaterialTheme.typography.titleLarge)
        Text("Задание №${trip.taskNumber}", style = MaterialTheme.typography.titleMedium)
        Text("Операция: ${driverTripOperationLabel(trip.operationType)}")
        Text("Клиент: ${trip.clientName}")
        Text("Адрес: ${trip.address?.takeIf(String::isNotBlank) ?: "не указан"}")
        Text("Координаты: $coordinates")
        Button(
            onClick = { onOpenInYandexMaps(trip) },
            enabled = yandexMapsRouteUrl(trip) != null,
            modifier = Modifier.testTag("open-yandex-maps-route"),
        ) {
            Text("Открыть в Яндекс Картах")
        }
        Text("Основной контакт: $primaryContact")
        if (trip.additionalContacts.isNotEmpty()) {
            Text("Дополнительные контакты", style = MaterialTheme.typography.titleSmall)
            trip.additionalContacts.forEach { contact ->
                Text("${contact.name} · ${contact.phone}")
            }
        }
        Text("Комментарий: ${trip.comment?.takeIf(String::isNotBlank) ?: "не указан"}")
        Text("Желаемые даты клиента", style = MaterialTheme.typography.titleSmall)
        if (trip.desiredDeliveryWindows.isEmpty()) {
            Text("Не указаны", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            trip.desiredDeliveryWindows.forEach { window ->
                Text(desiredDeliveryWindowLabel(window))
            }
        }
        Text("Назначенная дата", style = MaterialTheme.typography.titleSmall)
        Text(scheduledTripLabel(trip.scheduledDate))
        Text("Бытовки", style = MaterialTheme.typography.titleMedium)
        cabins.forEach { cabin ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Бытовка ${cabin.unitNumber}", style = MaterialTheme.typography.titleMedium)
                    Text("Мебель по заказу", style = MaterialTheme.typography.titleSmall)
                    if (cabin.desiredContents.isEmpty()) {
                        Text("Не требуется", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        cabin.desiredContents.forEach { Text(it) }
                    }
                    Text("Фактическое наполнение", style = MaterialTheme.typography.titleSmall)
                    if (cabin.actualContents.isEmpty()) {
                        Text("Мебель отсутствует", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        cabin.actualContents.forEach { Text(it) }
                    }
                    Text(
                        "Задание на перемещение: " +
                            if (cabin.movementTaskCreated) "создано" else "не создано",
                    )
                    Text(
                        "Перемещение мебели: " +
                            if (cabin.movementTaskCompleted) "выполнено" else "не выполнено",
                    )
                    Text(
                        "Наполнение: " + if (cabin.contentReady) "готово" else "не готово",
                        color = if (cabin.contentReady) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    presentation: TaskActionPresentation,
    completionAllowed: Boolean,
    onAction: (DriverTaskAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (DriverTaskAction.TAKE in presentation.actions) {
            Button(
                onClick = { onAction(DriverTaskAction.TAKE) },
                enabled = presentation.actionsEnabled,
            ) { Text(presentation.takeLabel) }
        }
        if (DriverTaskAction.PAUSE in presentation.actions) {
            OutlinedButton(
                onClick = { onAction(DriverTaskAction.PAUSE) },
                enabled = presentation.actionsEnabled,
            ) { Text("Пауза") }
        }
        if (DriverTaskAction.COMPLETE in presentation.actions) {
            Button(
                onClick = { onAction(DriverTaskAction.COMPLETE) },
                enabled = presentation.actionsEnabled && completionAllowed,
            ) { Text("Завершить") }
        }
        if (DriverTaskAction.RESUME in presentation.actions) {
            Button(
                onClick = { onAction(DriverTaskAction.RESUME) },
                enabled = presentation.actionsEnabled,
            ) { Text("Продолжить") }
        }
    }
}

@Composable
private fun MediaRow(media: DriverMediaReferenceDto, onClick: () -> Unit) {
    MediaThumbnailRow(
        thumbnailPath = media.thumbnailPath ?: media.readPath,
        title = if (media.kind == "SOURCE") "Общее фото задания" else "Фото результата",
        subtitle = media.recordedAt,
        onClick = onClick,
    )
}

@Composable
private fun WorkRow(
    work: DriverWorkDto,
    sourceMedia: List<DriverMediaReferenceDto>,
    onMedia: () -> Unit,
) {
    val presentation = workPresentation(work)
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(work.name, style = MaterialTheme.typography.titleSmall)
            Text("Количество: ${presentation.quantity}", style = MaterialTheme.typography.bodyMedium)
            presentation.plannedDuration?.let {
                Text("Плановое время: $it", style = MaterialTheme.typography.bodyMedium)
            }
            presentation.comment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (sourceMedia.isNotEmpty()) {
                Text("Фото к работе", style = MaterialTheme.typography.titleSmall)
                sourceMedia.forEach { media ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onMedia)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteMediaThumbnail(media.thumbnailPath ?: media.readPath)
                        Column {
                            Text("Фото работы", style = MaterialTheme.typography.bodyMedium)
                            Text(media.recordedAt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerEvidenceRow(evidence: TaskEvidenceDto, onClick: () -> Unit) {
    MediaThumbnailRow(
        thumbnailPath = evidence.thumbnailPath ?: requireNotNull(evidence.readPath),
        title = "Фото результата",
        subtitle = evidence.recordedAt,
        onClick = onClick,
    )
}

@Composable
private fun MediaThumbnailRow(
    thumbnailPath: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteMediaThumbnail(thumbnailPath)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RemoteMediaThumbnail(
    path: String,
    viewModel: TaskMediaThumbnailViewModel = hiltViewModel(),
) {
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    LaunchedEffect(path) { viewModel.load(path) }
    Box(
        modifier = Modifier.size(88.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val thumbnail = thumbnails[path]) {
            is TaskMediaThumbnail.Ready -> Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = "Миниатюра фото задания",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            is TaskMediaThumbnail.Failed -> Text(
                "Фото недоступно",
                style = MaterialTheme.typography.labelSmall,
            )
            null, TaskMediaThumbnail.Loading -> Text("Загрузка…", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EvidenceRow(
    evidence: TaskEvidenceEntity,
    hasValidReservationPayload: Boolean,
    onRetry: () -> Unit,
) {
    val presentation = evidencePresentation(
        evidence.state,
        evidence.uploadPercent,
        evidence.mediaId,
        hasValidReservationPayload,
    )
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(presentation.status, style = MaterialTheme.typography.titleSmall)
            presentation.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            evidence.lastError?.let {
                Text(
                    "Последняя попытка отправки не удалась. Фото остаётся сохранённым на устройстве.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (presentation.canRetryReservation) {
                OutlinedButton(onClick = onRetry) { Text("Повторить отправку") }
            }
        }
    }
}
