package dev.buhanzaz.rwms.worker.feature.taskdetail

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.network.TaskEvidenceDto
import dev.buhanzaz.rwms.worker.core.network.WorkerMediaReferenceDto
import dev.buhanzaz.rwms.worker.core.network.WorkerWorkDto
import dev.buhanzaz.rwms.worker.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay
import kotlinx.coroutines.delay

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
    val photoCapture = photoCapturePresentation(detail != null)
    val cabinNumber = cabinNumberForDisplay(task?.unitNumber, detail?.taskObject?.label)
    val displayedStatus = when {
        task?.locallyPending == true -> task.status
        detail != null -> detail.status
        else -> task?.status
    }
    val readyServerEvidence = detail?.evidence.orEmpty()
        .filter { evidence -> evidence.state == "READY" && !evidence.readPath.isNullOrBlank() }
    val readyEvidenceIds = readyServerEvidence.mapTo(mutableSetOf()) { it.evidenceId }.apply {
        addAll(state.evidence.filter { it.state == "READY" }.map { it.evidenceId })
    }
    val readyEvidenceCount = readyEvidenceIds.size
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
        assignments = state.assignments,
        locallyPending = task?.locallyPending == true,
        hasCurrentGroup = session?.currentGroupId != null,
        operationalAvailability = session?.operationalAvailability ?: "DISABLED",
    )
    LaunchedEffect(readyEvidenceCount) { viewModel.refresh() }
    WorkerScreenScaffold(title = detail?.title ?: state.task?.title ?: "Задание", onBack = onBack) { padding ->
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
                                    "SECONDARY_PENDING" -> "Ожидает основного исполнителя"
                                    else -> "Доступное"
                                },
                            )
                        }
                    }
                    cabinNumber?.let { Text("Бытовка: $it") }
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
                        )
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
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
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButtons(
                        presentation = actionPresentation,
                        completionAllowed = (detail?.completionAllowed == true) ||
                            (task?.let { readyEvidenceCount >= it.resultPhotoMinCount } == true),
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
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onCamera(requireNotNull(detail).routeIndex) },
                        enabled = photoCapture.enabled,
                    ) { Text("Добавить фото") }
                    Text("$readyEvidenceCount готово")
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
                item { Text("Работы", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
                items(detail.works, key = { it.id }) { work ->
                    WorkRow(work)
                }
            }
            if (detail?.sourceMedia?.isNotEmpty() == true) {
                item { Text("Исходные фото", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
                items(detail.sourceMedia, key = { it.mediaId }) { media ->
                    MediaRow(
                        media,
                        onClick = {
                            onMedia(
                                detail.title,
                                detail.sourceMedia.map { it.readPath }.filter(String::isNotBlank),
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
private fun ActionButtons(
    presentation: TaskActionPresentation,
    completionAllowed: Boolean,
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
        if (WorkerTaskAction.PAUSE in presentation.actions) {
            OutlinedButton(
                onClick = { onAction(WorkerTaskAction.PAUSE) },
                enabled = presentation.actionsEnabled,
            ) { Text("Пауза") }
        }
        if (WorkerTaskAction.COMPLETE in presentation.actions) {
            Button(
                onClick = { onAction(WorkerTaskAction.COMPLETE) },
                enabled = presentation.actionsEnabled && completionAllowed,
            ) { Text("Завершить") }
        }
        if (WorkerTaskAction.RESUME in presentation.actions) {
            Button(
                onClick = { onAction(WorkerTaskAction.RESUME) },
                enabled = presentation.actionsEnabled,
            ) { Text("Продолжить") }
        }
    }
}

@Composable
private fun MediaRow(media: WorkerMediaReferenceDto, onClick: () -> Unit) {
    MediaThumbnailRow(
        thumbnailPath = media.thumbnailPath ?: media.readPath,
        title = if (media.kind == "SOURCE") "Фото из ремонта / сметы" else "Фото результата",
        subtitle = media.recordedAt,
        onClick = onClick,
    )
}

@Composable
private fun WorkRow(work: WorkerWorkDto) {
    val presentation = workPresentation(work)
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(work.name, style = MaterialTheme.typography.titleSmall)
            Text("Количество: ${presentation.quantity}", style = MaterialTheme.typography.bodyMedium)
            presentation.plannedDuration?.let {
                Text("Плановое время: $it", style = MaterialTheme.typography.bodyMedium)
            }
            presentation.comment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
