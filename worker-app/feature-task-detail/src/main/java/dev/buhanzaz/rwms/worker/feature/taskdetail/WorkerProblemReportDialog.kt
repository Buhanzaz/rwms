package dev.buhanzaz.rwms.worker.feature.taskdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportStore
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerOutlinedButton

/**
 * Durable problem-report editor. Closing it only hides the sheet: the local draft and encrypted
 * attachments remain available until a report is explicitly queued or removed.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WorkerProblemReportDialog(
    userId: String,
    entryId: String,
    routeIndex: Int,
    onDismiss: () -> Unit,
    onCapture: (reportId: String, routeIndex: Int, remainingPhotos: Int, fromGallery: Boolean) -> Unit,
    viewModel: WorkerProblemReportViewModel = hiltViewModel(key = "problem:$entryId"),
) {
    LaunchedEffect(userId, entryId, routeIndex) { viewModel.bind(userId, entryId, routeIndex) }
    DisposableEffect(viewModel) { onDispose(viewModel::release) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var attachmentActionsVisible by remember(entryId) { mutableStateOf(false) }
    LaunchedEffect(state.dismissAfterSubmit) {
        if (state.dismissAfterSubmit) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Сообщить о проблеме", style = MaterialTheme.typography.headlineSmall)
            when {
                state.loading -> Unit
                state.report == null -> state.error?.let { ProblemReportError(it) }
                else -> ProblemReportContent(
                    state = state,
                    onCommentChanged = { viewModel.updateComment(userId, it) },
                    onCapture = { fromGallery ->
                        state.report?.let { report ->
                            onCapture(report.reportId, report.routeIndex, state.remainingPhotos, fromGallery)
                        }
                    },
                    attachmentActionsVisible = attachmentActionsVisible,
                    onAttachmentActionsVisibleChange = { attachmentActionsVisible = it },
                    onRemovePhoto = { viewModel.removePhoto(userId, it) },
                    onLoadPreview = viewModel::loadPreview,
                    onSubmit = { viewModel.submit(userId) },
                    onRetry = { viewModel.retry(userId) },
                )
            }
        }
    }
}

@Composable
private fun ProblemReportContent(
    state: WorkerProblemReportUiState,
    onCommentChanged: (String) -> Unit,
    onCapture: (Boolean) -> Unit,
    attachmentActionsVisible: Boolean,
    onAttachmentActionsVisibleChange: (Boolean) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onLoadPreview: (TaskEvidenceEntity) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
) {
    val report = requireNotNull(state.report)
    val editable = state.isDraft && !state.saving
    OutlinedTextField(
        value = state.comment,
        onValueChange = onCommentChanged,
        modifier = Modifier.fillMaxWidth().testTag("problem-report-comment"),
        enabled = editable,
        label = { Text("Опишите проблему") },
        supportingText = { Text("${state.comment.length}/2000") },
        minLines = 3,
        maxLines = 6,
        isError = state.isDraft && state.comment.isNotEmpty() && state.comment.trim().isEmpty(),
    )
    if (report.attachments.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(report.attachments, key = TaskEvidenceEntity::evidenceId) { evidence ->
                ProblemReportPhoto(
                    evidence = evidence,
                    preview = state.previews[evidence.evidenceId],
                    editable = editable,
                    onRemove = { onRemovePhoto(evidence.evidenceId) },
                    onLoadPreview = { onLoadPreview(evidence) },
                )
            }
        }
    }
    if (editable && state.remainingPhotos > 0) {
        WorkerOutlinedButton(
            onClick = { onAttachmentActionsVisibleChange(!attachmentActionsVisible) },
            modifier = Modifier.fillMaxWidth().testTag("problem-report-attach"),
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = null)
            Text("Добавить фото (${state.remainingPhotos})", modifier = Modifier.padding(start = 8.dp))
        }
        if (attachmentActionsVisible) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                WorkerOutlinedButton(
                    onClick = { onCapture(false) },
                    modifier = Modifier.weight(1f).testTag("problem-report-camera"),
                ) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null)
                    Text("Камера", modifier = Modifier.padding(start = 6.dp))
                }
                WorkerOutlinedButton(
                    onClick = { onCapture(true) },
                    modifier = Modifier.weight(1f).testTag("problem-report-gallery"),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Text("Галерея", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    state.error?.let { ProblemReportError(it) }
    when (report.state) {
        WorkerProblemReportStore.OUTBOX_DRAFT -> WorkerButton(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth().testTag("problem-report-submit"),
        ) { Text("Отправить обращение") }
        WorkerProblemReportStore.OUTBOX_PENDING,
        WorkerProblemReportStore.OUTBOX_RETRY -> Text(
            "Обращение сохранено и ожидает отправки. Оно ещё не доставлено.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WorkerProblemReportStore.OUTBOX_REVIEW_REQUIRED,
        WorkerProblemReportStore.OUTBOX_CONFLICT -> {
            Text(
                report.lastError ?: "Сервер не принял обращение. Проверьте подключение и повторите.",
                color = MaterialTheme.colorScheme.error,
            )
            WorkerOutlinedButton(
                onClick = onRetry,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("problem-report-retry"),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("Повторить отправку", modifier = Modifier.padding(start = 8.dp))
            }
        }
        WorkerProblemReportStore.OUTBOX_REPORTED -> Text(
            "Обращение принято.",
            color = MaterialTheme.colorScheme.primary,
        )
        else -> Text(
            "Состояние обращения: ${report.state}",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ProblemReportPhoto(
    evidence: TaskEvidenceEntity,
    preview: WorkerProblemReportPreview?,
    editable: Boolean,
    onRemove: () -> Unit,
    onLoadPreview: () -> Unit,
) {
    LaunchedEffect(evidence.evidenceId, preview) { if (preview == null) onLoadPreview() }
    Box(modifier = Modifier.size(96.dp).testTag("problem-report-photo-${evidence.evidenceId}")) {
        when (preview) {
            is WorkerProblemReportPreview.Ready -> Image(
                bitmap = preview.bitmap.asImageBitmap(),
                contentDescription = "Фотография проблемы",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            is WorkerProblemReportPreview.Failed -> Box(contentAlignment = Alignment.Center, modifier = Modifier.matchParentSize()) {
                Text(preview.message, style = MaterialTheme.typography.labelSmall)
            }
            null, WorkerProblemReportPreview.Loading -> Unit
        }
        if (editable) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp).testTag("problem-report-remove-${evidence.evidenceId}"),
            ) { Icon(Icons.Filled.Close, contentDescription = "Удалить фотографию") }
        }
    }
}

@Composable
private fun ProblemReportError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
