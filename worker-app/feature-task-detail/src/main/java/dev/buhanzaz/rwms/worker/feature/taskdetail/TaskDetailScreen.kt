package dev.buhanzaz.rwms.worker.feature.taskdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.network.TaskEvidenceDto
import dev.buhanzaz.rwms.worker.core.network.WorkerMediaReferenceDto
import dev.buhanzaz.rwms.worker.core.network.WorkerWorkDto
import dev.buhanzaz.rwms.worker.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.worker.core.ui.WorkerKpiColorRange
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay
import dev.buhanzaz.rwms.worker.core.ui.workerKpiTimeColor
import kotlinx.coroutines.delay

/** Renders one task, its evidence and the actions allowed by the synchronized server state. */
@Composable
fun TaskDetailScreen(
    userId: String,
    entryId: String,
    onBack: (() -> Unit)?,
    onCamera: (routeIndex: Int) -> Unit,
    onMedia: (title: String, readPaths: List<String>, initialIndex: Int) -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId, entryId) { viewModel.bind(userId, entryId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail = state.detail
    val task = state.task
    val session = state.session
    val cabinNumber = cabinNumberForDisplay(task?.unitNumber, detail?.taskObject?.label)
    val displayedStatus = when {
        task?.locallyPending == true -> task.status
        detail != null -> detail.status
        else -> task?.status
    }
    val photoCapture = photoCapturePresentation(
        hasLoadedDetail = detail != null,
        taskStatus = displayedStatus,
        currentWorkerId = userId,
        queuePurpose = state.queuePurpose,
        assignments = state.assignments,
        locallyPending = task?.locallyPending == true,
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
    val plannedDurationMinutes = detail?.plannedDurationMinutes ?: task?.plannedDurationMinutes
    val timerSnapshot = detail?.timerSnapshot ?: task?.serverTimerSnapshotOrNull()
    var elapsedSinceSnapshotSeconds by remember(timerSnapshot) { mutableStateOf(0L) }
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
    val kpiTimeColor = workerKpiTimeColor(
        remainingPercent = timerSnapshot?.projectedAfter(elapsedSinceSnapshotSeconds)?.remainingPercent,
        ranges = state.kpiPalette?.ranges.orEmpty().map {
            WorkerKpiColorRange(it.fromPercent, it.toPercent, it.color)
        },
        overdueColor = state.kpiPalette?.overdueColor,
    )
    val sourceMediaPresentation = taskSourceMediaPresentation(
        works = detail?.works.orEmpty(),
        sourceMedia = detail?.sourceMedia.orEmpty(),
    )
    val generalSourceMedia = sourceMediaPresentation.general
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
    val actionPresentation = taskActionPresentation(
        currentWorkerId = userId,
        taskStatus = displayedStatus,
        availabilityMode = detail?.availabilityMode,
        queuePurpose = state.queuePurpose,
        assignments = state.assignments,
        locallyPending = task?.locallyPending == true,
        hasCurrentGroup = session?.currentGroupId != null,
        operationalAvailability = session?.operationalAvailability ?: "DISABLED",
    )
    LaunchedEffect(readyEvidenceCount) { viewModel.refresh() }
    val taskTitle = detail?.title ?: state.task?.title ?: "Задание"
    val headerTimer = taskHeaderTimerPresentation(
        taskStatus = displayedStatus,
        locallyPending = task?.locallyPending == true,
        timerState = timerSnapshot?.timerState,
        remaining = timing.remaining,
    )
    WorkerScreenScaffold(
        title = cabinNumber?.let { "Бытовка $it" } ?: "Задание",
        onBack = onBack,
        actions = {
            headerTimer?.let { timer ->
                Column(
                    modifier = Modifier.padding(end = 8.dp).testTag("task-header-countdown"),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        timer.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    timer.countdown?.let { countdown ->
                        Text(
                            countdown,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (timer.running) {
                                kpiTimeColor ?: MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .testTag("task-summary"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            taskTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            displayedStatus?.let { TaskStatusChip(it) }
                            detail?.availabilityMode?.let {
                                Text(
                                    when (it) {
                                        "MANDATORY" -> "Обязательное"
                                        "REQUIRED_JOIN" -> "Требуется присоединение"
                                        "SECONDARY_PENDING" -> "Ожидает основного исполнителя"
                                        "OPTIONAL_JOIN" -> "Можно присоединиться"
                                        else -> "Доступное"
                                    },
                                )
                            }
                        }
                        cabinNumber?.let { Text("Бытовка: $it") }
                        (detail?.taskId ?: task?.taskId)?.let { Text("Номер задания: $it") }
                        Text("Тип объекта: ${workerTaskObjectKindLabel(detail?.taskObject?.kind)}")
                        detail?.taskObject?.label?.takeIf { it.isNotBlank() && it != cabinNumber }
                            ?.let { Text("Объект: $it") }
                        detail?.description?.takeIf(String::isNotBlank)?.let { Text(it) }
                        detail?.taskText?.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
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
                                fontWeight = if (kpiTimeColor == null) {
                                    FontWeight.Normal
                                } else {
                                    FontWeight.Bold
                                },
                            )
                        }
                        Text(
                            "Группа: ${session?.currentGroupName ?: "не выбрана руководителем"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (session?.operationalAvailability == "DISABLED") {
                            Text(
                                "Группа временно недоступна",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProgressActionButtons(
                        presentation = actionPresentation,
                        onAction = { viewModel.perform(it.wireValue) },
                    )
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

            item { TaskSectionTitle("Общие фото", "Фото, приложенные к заданию") }
            if (generalSourceMedia.isEmpty()) {
                item { EmptyTaskSection("Общих фотографий нет") }
            } else {
                item {
                    SourceMediaStrip(
                        media = generalSourceMedia,
                        contentDescription = "Общее фото задания",
                        onOpen = { index ->
                            onMedia(
                                "$taskTitle · общие фото",
                                generalSourceMedia.map(WorkerMediaReferenceDto::readPath),
                                index,
                            )
                        },
                    )
                }
            }

            item { TaskSectionTitle("Материалы", "Что подготовить для выполнения") }
            if (detail?.materials.isNullOrEmpty()) {
                item { EmptyTaskSection("Материалы не указаны") }
            } else {
                items(requireNotNull(detail).materials, key = { it.id }) { material ->
                    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                material.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${material.quantity} ${material.unit.orEmpty()}".trim(),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            item { TaskSectionTitle("Работы", "Что именно нужно сделать") }
            if (detail?.works.isNullOrEmpty()) {
                item { EmptyTaskSection("Состав работ не указан") }
            } else {
                items(requireNotNull(detail).works, key = { it.id }) { work ->
                    val media = sourceMediaPresentation.byWorkId[work.id].orEmpty()
                    WorkRow(
                        work = work,
                        sourceMedia = media,
                        onMedia = { index ->
                            onMedia(
                                "$taskTitle · ${work.name}",
                                media.map(WorkerMediaReferenceDto::readPath),
                                index,
                            )
                        },
                    )
                }
            }

            if (detail?.comments?.isNotEmpty() == true) {
                item { TaskSectionTitle("Комментарии", "Уточнения к выполнению") }
                items(detail.comments, key = { it.id }) { comment ->
                    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                comment.authorDisplayName ?: "RWMS",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(comment.text)
                        }
                    }
                }
            }

            item { TaskSectionTitle("Фото результата", "Снимите выполненную работу перед завершением") }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { onCamera(requireNotNull(detail).routeIndex) },
                        enabled = photoCapture.enabled,
                    ) { Text("Сфотографировать результат") }
                    Text("Готово: $readyEvidenceCount")
                }
                photoCapture.message?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (readyServerEvidence.isNotEmpty()) {
                item {
                    EvidenceMediaStrip(
                        evidence = readyServerEvidence,
                        onOpen = { index ->
                            onMedia(
                                "$taskTitle · фото результата",
                                readyServerEvidence.map { requireNotNull(it.readPath) },
                                index,
                            )
                        },
                    )
                }
            }
            if (localEvidenceWithoutServerPhoto.isNotEmpty()) {
                items(localEvidenceWithoutServerPhoto, key = { "local-${it.evidenceId}" }) { evidence ->
                    EvidenceRow(
                        evidence = evidence,
                        hasValidReservationPayload = evidence.evidenceId in state.retryableEvidenceIds,
                        onRetry = { viewModel.retryEvidence(evidence.evidenceId) },
                    )
                }
            }

            if (
                state.queuePurpose == LOGISTICS_DRIVER_QUEUE_PURPOSE &&
                WorkerTaskAction.COMPLETE in actionPresentation.actions
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            readyEvidenceIds.isEmpty() -> Text(
                                "Для завершения добавьте фотографию бытовки",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            readyEvidenceIds.size == 1 -> Text(
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
                }
            }

            if (WorkerTaskAction.COMPLETE in actionPresentation.actions) {
                item {
                    val completionAllowed = (
                        (detail?.completionAllowed == true) ||
                            (task?.let { readyEvidenceCount >= it.resultPhotoMinCount } == true)
                        ) &&
                        (
                            state.queuePurpose != LOGISTICS_DRIVER_QUEUE_PURPOSE ||
                                selectedCompletionEvidenceId != null
                            )
                    Button(
                        onClick = {
                            viewModel.perform(
                                action = WorkerTaskAction.COMPLETE.wireValue,
                                evidenceId = selectedCompletionEvidenceId,
                            )
                        },
                        enabled = actionPresentation.actionsEnabled && completionAllowed,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .testTag("task-complete"),
                    ) {
                        Text("Завершить задание")
                    }
                }
            }
        }
    }
}

/** Shows take/join and pause/resume controls before the completion section. */
@Composable
private fun ProgressActionButtons(
    presentation: TaskActionPresentation,
    onAction: (WorkerTaskAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (WorkerTaskAction.TAKE in presentation.actions) {
            Button(
                onClick = { onAction(WorkerTaskAction.TAKE) },
                enabled = presentation.actionsEnabled,
            ) { Text(presentation.takeLabel) }
        }
        if (WorkerTaskAction.JOIN in presentation.actions) {
            Button(
                onClick = { onAction(WorkerTaskAction.JOIN) },
                enabled = presentation.actionsEnabled,
            ) { Text(presentation.joinLabel) }
        }
        if (WorkerTaskAction.PAUSE in presentation.actions) {
            OutlinedButton(
                onClick = { onAction(WorkerTaskAction.PAUSE) },
                enabled = presentation.actionsEnabled,
            ) { Text("Пауза") }
        }
        if (WorkerTaskAction.RESUME in presentation.actions) {
            Button(
                onClick = { onAction(WorkerTaskAction.RESUME) },
                enabled = presentation.actionsEnabled,
            ) { Text("Продолжить") }
        }
    }
}

/** Renders a consistent heading for one stage of task execution. */
@Composable
private fun TaskSectionTitle(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Makes a missing optional server section explicit without fabricating content. */
@Composable
private fun EmptyTaskSection(message: String) {
    Text(
        message,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Displays source photos as a swipeable strip and opens the exact selected item. */
@Composable
private fun SourceMediaStrip(
    media: List<WorkerMediaReferenceDto>,
    contentDescription: String,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("task-source-photo-strip"),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(media, key = { _, item -> item.mediaId }) { index, item ->
            TaskMediaTile(
                thumbnailPath = item.thumbnailPath ?: item.readPath,
                contentDescription = "$contentDescription ${index + 1}",
                subtitle = item.recordedAt,
                onClick = { onOpen(index) },
            )
        }
    }
}

/** Displays uploaded result evidence separately from source photos. */
@Composable
private fun EvidenceMediaStrip(
    evidence: List<TaskEvidenceDto>,
    onOpen: (index: Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("task-result-photo-strip"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(evidence, key = { _, item -> item.evidenceId }) { index, item ->
            TaskMediaTile(
                thumbnailPath = item.thumbnailPath ?: requireNotNull(item.readPath),
                contentDescription = "Фото результата ${index + 1}",
                subtitle = item.recordedAt,
                onClick = { onOpen(index) },
            )
        }
    }
}

/** One compact photo preview shared by general, work and result carousels. */
@Composable
private fun TaskMediaTile(
    thumbnailPath: String,
    contentDescription: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.width(168.dp).clickable(onClick = onClick),
    ) {
        Column {
            RemoteMediaThumbnail(
                path = thumbnailPath,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxWidth().height(112.dp),
            )
            Text(
                subtitle,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WorkRow(
    work: WorkerWorkDto,
    sourceMedia: List<WorkerMediaReferenceDto>,
    onMedia: (index: Int) -> Unit,
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
                SourceMediaStrip(
                    media = sourceMedia,
                    contentDescription = "Фото работы ${work.name}",
                    onOpen = onMedia,
                    contentPadding = PaddingValues(0.dp),
                )
            } else {
                Text(
                    "Фотографий к этой работе нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RemoteMediaThumbnail(
    path: String,
    contentDescription: String,
    modifier: Modifier = Modifier.size(88.dp),
    viewModel: TaskMediaThumbnailViewModel = hiltViewModel(),
) {
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    LaunchedEffect(path) { viewModel.load(path) }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (val thumbnail = thumbnails[path]) {
            is TaskMediaThumbnail.Ready -> Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
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
                    "Последняя попытка отправки не удалась. Фото сохранено и будет отправлено повторно.",
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
