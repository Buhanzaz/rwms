package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.buhanzaz.rwms.manager.uploads.BackgroundPhotoStatus
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadOperation
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadPhoto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadStatus
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerPanel
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold
import dev.buhanzaz.rwms.manager.ui.components.StatusPill
import dev.buhanzaz.rwms.manager.ui.components.StatusPillEmphasis

/**
 * Persistent operations which are still being sent to RWMS.
 *
 * Completed operations are deliberately removed by the queue, so this screen only contains
 * work that is pending, running, or needs the user's attention.
 */
@Composable
fun BackgroundUploadsScreen(
    operations: List<BackgroundUploadOperation>,
    onBack: () -> Unit,
    onRetryOperation: (String) -> Unit,
    onRetryPhoto: (String, String) -> Unit,
    onCancelOperation: (String) -> Unit,
) {
    val groupedOperations = operations.groupBy(BackgroundUploadOperation::area)
    var pendingCancellationOperationId by remember { mutableStateOf<String?>(null) }

    ManagerScreenScaffold(title = "Загрузки", onBack = onBack) { padding ->
        if (operations.isEmpty()) {
            EmptyState(
                title = "Нет активных загрузок",
                description = "Проверки, сметы, ремонты и логистические операции исчезают " +
                    "из этого списка автоматически после успешной отправки на сервер.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Фоновая отправка", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Можно продолжать работу: загрузки выполняются в фоне. " +
                                "Ошибочные фотографии можно отправить повторно.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                BackgroundUploadArea.entries.forEach { area ->
                    val areaOperations = groupedOperations[area].orEmpty()
                    if (areaOperations.isNotEmpty()) {
                        item(key = "area-${area.name}") {
                            Text(
                                text = area.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(areaOperations, key = BackgroundUploadOperation::id) { operation ->
                            BackgroundUploadOperationPanel(
                                operation = operation,
                                onRetryOperation = onRetryOperation,
                                onRetryPhoto = onRetryPhoto,
                                onCancelOperation = { pendingCancellationOperationId = it },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingCancellationOperationId?.let { operationId ->
        AlertDialog(
            onDismissRequest = { pendingCancellationOperationId = null },
            title = { Text("Удалить отправку из очереди?") },
            text = {
                Text(
                    "Операция и сохранённые на устройстве фотографии будут удалены из очереди.\n\n" +
                        "Уже загруженные фотографии не удаляются с сервера. Если итоговая " +
                        "команда уже принята сервером, это действие её не откатит.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingCancellationOperationId = null
                    onCancelOperation(operationId)
                }) {
                    Text("Удалить из очереди")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancellationOperationId = null }) {
                    Text("Назад")
                }
            },
        )
    }
}

@Composable
private fun BackgroundUploadOperationPanel(
    operation: BackgroundUploadOperation,
    onRetryOperation: (String) -> Unit,
    onRetryPhoto: (String, String) -> Unit,
    onCancelOperation: (String) -> Unit,
) {
    ManagerPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = operation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                operation.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(
                label = operation.status.label,
                emphasis = operation.status.emphasis,
            )
        }

        Text(
            text = operation.stage.ifBlank { "Ожидание сети" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = operation.photoCountLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        operation.error?.takeIf(String::isNotBlank)?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (operation.photos.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            operation.photos.forEach { photo ->
                BackgroundUploadPhotoRow(
                    operationId = operation.id,
                    photo = photo,
                    onRetryPhoto = onRetryPhoto,
                )
            }
        }

        if (operation.status == BackgroundUploadStatus.FAILED) {
            OutlinedButton(
                onClick = { onRetryOperation(operation.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Повторить")
            }
        }
        OutlinedButton(
            onClick = { onCancelOperation(operation.id) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Отменить отправку")
        }
    }
}

@Composable
private fun BackgroundUploadPhotoRow(
    operationId: String,
    photo: BackgroundUploadPhoto,
    onRetryPhoto: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = photo.durableUri,
                contentDescription = photo.sourceName.ifBlank { "Фотография" },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = photo.sourceName.ifBlank { "Фотография" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                photo.error?.takeIf(String::isNotBlank)?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(label = photo.status.label, emphasis = photo.status.emphasis)
        }
        if (photo.status == BackgroundPhotoStatus.FAILED) {
            Button(
                onClick = { onRetryPhoto(operationId, photo.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Дозагрузить")
            }
        }
    }
}

private val BackgroundUploadOperation.photoCountLabel: String
    get() = if (photos.isEmpty()) {
        "Фотографии не требуются"
    } else {
        "Фотографии: $readyPhotoCount из ${photos.size}" +
            if (failedPhotoCount > 0) " · ошибок: $failedPhotoCount" else ""
    }

private val BackgroundUploadStatus.label: String
    get() = when (this) {
        BackgroundUploadStatus.QUEUED -> "В очереди"
        BackgroundUploadStatus.RUNNING -> "Отправляется"
        BackgroundUploadStatus.FAILED -> "Ошибка"
    }

private val BackgroundUploadStatus.emphasis: StatusPillEmphasis
    get() = when (this) {
        BackgroundUploadStatus.QUEUED -> StatusPillEmphasis.Neutral
        BackgroundUploadStatus.RUNNING -> StatusPillEmphasis.Positive
        BackgroundUploadStatus.FAILED -> StatusPillEmphasis.Warning
    }

private val BackgroundPhotoStatus.label: String
    get() = when (this) {
        BackgroundPhotoStatus.QUEUED -> "Ожидает"
        BackgroundPhotoStatus.UPLOADING -> "Загружается"
        BackgroundPhotoStatus.READY -> "Загружено"
        BackgroundPhotoStatus.FAILED -> "Ошибка"
    }

private val BackgroundPhotoStatus.emphasis: StatusPillEmphasis
    get() = when (this) {
        BackgroundPhotoStatus.READY -> StatusPillEmphasis.Positive
        BackgroundPhotoStatus.FAILED -> StatusPillEmphasis.Warning
        BackgroundPhotoStatus.QUEUED,
        BackgroundPhotoStatus.UPLOADING,
        -> StatusPillEmphasis.Neutral
    }
