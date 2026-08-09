package dev.buhanzaz.rwms.manager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.ui.MaintenanceAcceptanceEditorState
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.repairSourceLabel
import dev.buhanzaz.rwms.manager.ui.hasAcceptanceEvidence
import dev.buhanzaz.rwms.manager.ui.hasAcceptedAllWorkLines
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerPanel
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoGalleryDialog
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold

/** Presents repairs awaiting acceptance and the accept-or-rework decision for each stage. */
@Composable
fun MaintenanceAcceptanceScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onOpen: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onEditComment: (String) -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenCabinPhotos: () -> Unit,
    onOpenStagePhotos: (String) -> Unit,
    onOpenWorkSourcePhotos: (String, String) -> Unit,
    onCloseGallery: () -> Unit,
    onAccept: () -> Unit,
    onAcceptWork: (String) -> Unit,
    onReworkWork: (String, String) -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoad()
    }
    val editor = uiState.acceptanceEditor
    val navigateBack = if (editor == null) onBack else onCloseDetail
    BackHandler(enabled = editor != null, onBack = navigateBack)
    ManagerScreenScaffold(
        title = if (editor == null) "Приёмка" else "Приёмка ремонта",
        onBack = navigateBack,
    ) { padding ->
        if (editor == null) {
            AcceptanceList(
                repairs = uiState.acceptanceRepairs,
                assetLabels = uiState.maintenanceAssetLabels,
                enabled = !uiState.busy,
                onOpen = onOpen,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            AcceptanceDetails(
                editor = editor,
                busy = uiState.busy,
                onEditComment = onEditComment,
                onOpenPhotos = onOpenPhotos,
                onOpenCabinPhotos = onOpenCabinPhotos,
                onOpenStagePhotos = onOpenStagePhotos,
                onOpenWorkSourcePhotos = onOpenWorkSourcePhotos,
                onAccept = onAccept,
                onAcceptWork = onAcceptWork,
                onReworkWork = { workLineId -> onReworkWork(editor.repair.id, workLineId) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
    uiState.acceptanceGallery?.let { gallery ->
        if (gallery.photoUris.isEmpty()) {
            AlertDialog(
                onDismissRequest = onCloseGallery,
                title = { Text(gallery.title) },
                text = { Text(gallery.emptyMessage) },
                confirmButton = {
                    TextButton(onClick = onCloseGallery) { Text("Понятно") }
                },
            )
        } else {
            ManagerPhotoGalleryDialog(
                photoUris = gallery.photoUris,
                initialIndex = 0,
                title = gallery.title,
                onDismiss = onCloseGallery,
            )
        }
    }
}

@Composable
private fun AcceptanceList(
    repairs: List<RepairDto>,
    assetLabels: Map<String, String>,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (repairs.isEmpty()) {
        EmptyState(
            title = "Заданий на приёмку нет",
            description = "Здесь появятся завершённые ремонты со статусом «Ожидает приёмки».",
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(repairs, key = RepairDto::id) { repair ->
            ManagerPanel(onClick = if (enabled) ({ onOpen(repair.id) }) else null) {
                Text(
                    assetLabels[repair.rentalItemId] ?: "Бытовка без номера",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (repair.kind == "REWORK") "Доработка" else repairOriginLabel(repair.origin),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Дата ремонта: ${repair.dispatchDate}")
                Text("Приоритет: ${repair.priority}")
                Text(
                    "Ожидает приёмки",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AcceptanceDetails(
    editor: MaintenanceAcceptanceEditorState,
    busy: Boolean,
    onEditComment: (String) -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenCabinPhotos: () -> Unit,
    onOpenStagePhotos: (String) -> Unit,
    onOpenWorkSourcePhotos: (String, String) -> Unit,
    onAccept: () -> Unit,
    onAcceptWork: (String) -> Unit,
    onReworkWork: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repair = editor.repair
    val allWorkLinesAccepted = editor.hasAcceptedAllWorkLines()
    val canAccept = editor.hasAcceptanceEvidence() && allWorkLinesAccepted
    var acceptConfirmationOpen by remember(repair.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ManagerPanel {
                Text(editor.asset.number, style = MaterialTheme.typography.titleLarge)
                AcceptanceInfoRow(
                    "Тип",
                    if (repair.kind == "REWORK") "Доработка" else repairOriginLabel(repair.origin),
                )
                AcceptanceInfoRow("Дата", repair.dispatchDate)
                AcceptanceInfoRow("Приоритет", repair.priority.toString())
                AcceptanceInfoRow(
                    "Источник",
                    repairSourceLabel(repair.origin, repair.sourceParty),
                )
                AcceptanceInfoRow("Статус", "Ожидает приёмки")
                FilledTonalButton(
                    onClick = onOpenCabinPhotos,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Просмотр фото бытовки") }
            }
        }
        items(repair.plan.stages.sortedBy(RepairStageDto::order), key = RepairStageDto::id) { stage ->
            AcceptanceStage(
                stage = stage,
                enabled = !busy,
                acceptedWorkLineIds = editor.acceptedWorkLineIds,
                onOpenAfterPhotos = { onOpenStagePhotos(stage.id) },
                onOpenSourcePhotos = { workLineId ->
                    onOpenWorkSourcePhotos(stage.id, workLineId)
                },
                onAcceptWork = onAcceptWork,
                onReworkWork = onReworkWork,
            )
        }
        item {
            ManagerPanel {
                Text("Решение", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = editor.comment,
                    onValueChange = onEditComment,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Комментарий") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !busy,
                )
                FilledTonalButton(
                    onClick = onOpenPhotos,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Фото приёмки: ${editor.readyMedia.size + editor.photoUris.size}",
                    )
                }
                Button(
                    onClick = { acceptConfirmationOpen = true },
                    enabled = !busy && canAccept,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Принять отмеченные работы") }
                if (!canAccept) {
                    Text(
                        when {
                            !editor.hasAcceptanceEvidence() ->
                                "Для приёмки добавьте хотя бы одно фото."
                            !allWorkLinesAccepted ->
                                "Для каждой работы выберите «Принято» или «Переделать»."
                            else -> "Проверьте данные приёмки."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (acceptConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { if (!busy) acceptConfirmationOpen = false },
            title = { Text("Принять бытовку?") },
            text = {
                Text(
                    "Ремонтный цикл будет завершён, а бытовка переведена в свободное состояние.",
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { acceptConfirmationOpen = false },
                    enabled = !busy,
                ) { Text("Отмена") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        acceptConfirmationOpen = false
                        onAccept()
                    },
                    enabled = !busy && canAccept,
                ) { Text("Принять") }
            },
        )
    }
}

@Composable
private fun AcceptanceStage(
    stage: RepairStageDto,
    enabled: Boolean,
    acceptedWorkLineIds: Set<String>,
    onOpenAfterPhotos: () -> Unit,
    onOpenSourcePhotos: (String) -> Unit,
    onAcceptWork: (String) -> Unit,
    onReworkWork: (String) -> Unit,
) {
    ManagerPanel {
        Text(
            "Этап ${stage.order + 1}: Ремонтные работы",
            style = MaterialTheme.typography.titleMedium,
        )
        AcceptanceInfoRow("Очередь", stage.routing.queueName)
        AcceptanceInfoRow("Статус", repairStageStateLabel(stage.state))
        stage.taskDeadline?.let { AcceptanceInfoRow("Срок", it) }
        if (stage.workLines.isEmpty() && stage.materialLines.isEmpty()) {
            Text(
                "Работы и материалы не указаны.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stage.workLines.isNotEmpty()) {
            Text("Работы", style = MaterialTheme.typography.labelLarge)
            stage.workLines.forEach { work ->
                AcceptanceWorkLine(
                    line = work,
                    afterPhotoCount = stage.evidence.size,
                    accepted = work.id in acceptedWorkLineIds,
                    enabled = enabled,
                    onOpenSourcePhotos = { onOpenSourcePhotos(work.id) },
                    onOpenAfterPhotos = onOpenAfterPhotos,
                    onAccept = { onAcceptWork(work.id) },
                    onRework = { onReworkWork(work.id) },
                )
            }
        }
        if (stage.materialLines.isNotEmpty()) {
            Text("Материалы", style = MaterialTheme.typography.labelLarge)
            stage.materialLines.forEach { line -> AcceptanceLine("Материал", line) }
        }
        if (stage.workLines.isNotEmpty() && stage.groupComment.isNotBlank()) {
            Text(
                "Комментарий к работам: ${stage.groupComment}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FilledTonalButton(
            onClick = onOpenAfterPhotos,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Фото после этапа: ${stage.evidence.size}") }
    }
}

@Composable
private fun AcceptanceWorkLine(
    line: EstimateLineDto,
    afterPhotoCount: Int,
    accepted: Boolean,
    enabled: Boolean,
    onOpenSourcePhotos: () -> Unit,
    onOpenAfterPhotos: () -> Unit,
    onAccept: () -> Unit,
    onRework: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AcceptanceLine("Работа", line)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onOpenSourcePhotos,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("Фото до: ${line.mediaReferences.size}") }
            FilledTonalButton(
                onClick = onOpenAfterPhotos,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("Фото после: $afterPhotoCount") }
        }
        if (accepted) {
            Text(
                "Решение: Принято",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(
                onClick = onRework,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Переделать") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAccept,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Принято") }
                OutlinedButton(
                    onClick = onRework,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Переделать") }
            }
        }
    }
}

@Composable
private fun AcceptanceLine(kind: String, line: EstimateLineDto) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            if (kind == "Работа") "Р" else "М",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(line.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${line.quantity} ${line.unit.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            line.comment?.takeIf { kind == "Работа" && it.isNotBlank() }?.let { comment ->
                Text(comment, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AcceptanceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.8f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun repairOriginLabel(value: String): String = when (value) {
    "ESTIMATE" -> "Ремонт по смете"
    "INVENTORY" -> "Ремонт по инвентаризации"
    else -> "Прямой ремонт"
}


private fun repairStageStateLabel(value: String): String = when (value) {
    "PLANNED" -> "Запланирован"
    "QUEUED" -> "Ожидает"
    "IN_PROGRESS" -> "В работе"
    "DONE" -> "Завершён"
    "CANCELLED" -> "Отменён"
    else -> value
}
