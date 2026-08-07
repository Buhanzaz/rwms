package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.LogisticsLineDto
import dev.buhanzaz.rwms.manager.network.ReturnEstimateSourceDto
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.returnInspectionActionError
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerPanel
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoPreview
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold
import dev.buhanzaz.rwms.manager.ui.components.StatusPill
import dev.buhanzaz.rwms.manager.ui.components.StatusPillEmphasis

@Composable
fun ReturnsListScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoadReturns: () -> Unit,
    onOpenReturn: (LogisticsDocumentDto) -> Unit,
    onOpenInspection: () -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoadReturns()
    }
    ManagerScreenScaffold(title = "Возвраты", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Откройте возврат, осмотрите каждую строку и приложите фотографии до принятия или создания сметы.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.returns.isEmpty()) {
                item {
                    EmptyState(
                        title = "Возвратов нет",
                        description = "Для выбранного склада нет документов, ожидающих осмотра.",
                    )
                }
            } else {
                items(
                    count = uiState.returns.size,
                    key = { uiState.returns[it].id },
                ) { index ->
                    val document = uiState.returns[index]
                    ReturnDocumentCard(
                        document = document,
                        onClick = {
                            onOpenReturn(document)
                            onOpenInspection()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReturnDocumentCard(document: LogisticsDocumentDto, onClick: () -> Unit) {
    ManagerPanel(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(label = returnStateLabel(document.state), emphasis = StatusPillEmphasis.Warning)
            document.scheduledDate?.let { StatusPill(label = it) }
        }
        Text("Возврат", style = MaterialTheme.typography.titleMedium)
        Text(
            "Строк: ${document.lines.size}${document.partySnapshot?.let { " · $it" }.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Открыть осмотр ›", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ReturnInspectionScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onOpenPhotos: (String) -> Unit,
    onConfirmEquipment: (lineId: String, confirmed: Boolean) -> Unit,
    onAccept: (() -> Unit) -> Unit,
    onStartEstimates: (
        onSourcesReady: (List<ReturnEstimateSourceDto>) -> Unit,
        onQueued: () -> Unit,
    ) -> Unit,
    onEstimatesReady: (List<ReturnEstimateSourceDto>) -> Unit,
) {
    val document = uiState.selectedReturn
    if (document == null) {
        ManagerScreenScaffold(title = "Осмотр возврата", onBack = onBack) { padding ->
            EmptyState(
                title = "Возврат не выбран",
                description = "Вернитесь к списку и откройте документ для осмотра.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    var pendingAction by remember(document.id) { mutableStateOf<ReturnAction?>(null) }
    val inspectionActionError = document.returnInspectionActionError()
    val inspectionAvailable = inspectionActionError == null
    val estimateAvailable = inspectionAvailable && !uiState.busy

    ManagerScreenScaffold(title = "Осмотр возврата", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ReturnInspectionHeader(document) }
            item {
                Text(
                    "Строки возврата", style = MaterialTheme.typography.titleLarge)
            }
            items(
                count = document.lines.size,
                key = { document.lines[it].id },
            ) { index ->
                val line = document.lines[index]
                ReturnLineInspectionCard(
                    line = line,
                    localPhotoUris = uiState.returnPhotoUris[line.id].orEmpty(),
                    readyPhotoCount = uiState.returnReadyMedia[line.id].orEmpty().size,
                    equipmentConfirmed = line.id in uiState.returnEquipmentConfirmed,
                    onOpenPhotos = { onOpenPhotos(line.id) },
                    onConfirmEquipment = { onConfirmEquipment(line.id, it) },
                    enabled = inspectionAvailable && !uiState.busy,
                )
            }
            item {
                ManagerPanel {
                    Text("Завершение осмотра", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Для любого действия нужна минимум одна фотография у каждой строки. " +
                            "Для принятия без сметы подтвердите комплектность мебели по всем строкам.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    inspectionActionError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { pendingAction = ReturnAction.Accept },
                        enabled = inspectionAvailable && !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Принять без сметы") }
                    FilledTonalButton(
                        onClick = { pendingAction = ReturnAction.Estimate },
                        enabled = estimateAvailable,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Создать смету") }
                    Text(
                        "Будет создан отдельный черновик сметы для каждой бытовки. Общая смета " +
                            "для нескольких бытовок не создаётся.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(if (action == ReturnAction.Accept) "Принять возврат?" else "Создать смету?")
            },
            text = {
                Text(
                    if (action == ReturnAction.Accept) {
                        "Документ будет принят без сметы после проверки комплектности и обязательных фотографий."
                    } else {
                        "Для каждой бытовки будет создан отдельный черновик сметы по фотографиям " +
                            "осмотра."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingAction = null
                    if (action == ReturnAction.Accept) {
                        onAccept(onBack)
                    } else {
                        onStartEstimates(onEstimatesReady, onBack)
                    }
                }) { Text("Подтвердить") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ReturnInspectionHeader(document: LogisticsDocumentDto) {
    ManagerPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(label = returnStateLabel(document.state), emphasis = StatusPillEmphasis.Warning)
            document.scheduledDate?.let { StatusPill(label = it) }
        }
        Text("Возврат", style = MaterialTheme.typography.titleLarge)
        document.partySnapshot?.let { Text("Контрагент: $it") }
        document.driverSnapshot?.let { Text("Водитель: $it") }
    }
}

@Composable
private fun ReturnLineInspectionCard(
    line: LogisticsLineDto,
    localPhotoUris: List<String>,
    readyPhotoCount: Int,
    equipmentConfirmed: Boolean,
    onOpenPhotos: () -> Unit,
    onConfirmEquipment: (Boolean) -> Unit,
    enabled: Boolean,
) {
    ManagerPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(label = "Строка ${line.lineNumber}")
            StatusPill(label = returnLineStateLabel(line.state))
        }
        Text("Бытовка · строка ${line.lineNumber}", style = MaterialTheme.typography.titleMedium)
        Text(
            "Сделайте минимум одно фото состояния. Загружено: ${readyPhotoCount + localPhotoUris.size}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = onOpenPhotos,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Фотографии строки")
        }
        if (localPhotoUris.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                localPhotoUris.forEach { uri ->
                    ManagerPhotoPreview(uri, Modifier.width(88.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = equipmentConfirmed,
                onCheckedChange = onConfirmEquipment,
                enabled = enabled,
            )
            Column {
                Text("Комплектность мебели подтверждена")
                Text(
                    "Требуется для принятия возврата без сметы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class ReturnAction { Accept, Estimate }

private fun returnStateLabel(value: String): String = when (value) {
    "INSPECTION_REQUIRED", "RETURNED", "ARRIVED", "PENDING_INSPECTION" -> "Ожидает осмотра"
    "REGISTERING" -> "Регистрация"
    "RECONCILIATION_REQUIRED" -> "Требуется сверка"
    "ACCEPTING", "ESTIMATE_PENDING" -> "В обработке"
    "ACCEPTED" -> "Принят"
    "ESTIMATE_REQUESTED" -> "Смета запрошена"
    else -> value
}

private fun returnLineStateLabel(value: String): String = when (value) {
    "PENDING_INSPECTION" -> "Осмотр"
    "ACCEPTED" -> "Принято"
    else -> value
}
