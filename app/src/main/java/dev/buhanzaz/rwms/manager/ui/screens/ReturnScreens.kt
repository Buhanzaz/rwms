package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.LogisticsLineDto
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.ReturnEquipmentCatalogStatus
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
    onUpdateShortage: (lineId: String, equipmentId: String, quantity: String) -> Unit,
    onAccept: (() -> Unit) -> Unit,
    onCreateEstimate: (() -> Unit) -> Unit,
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
    val estimateAvailable = inspectionAvailable &&
        !uiState.busy &&
        uiState.returnEquipmentCatalogStatus == ReturnEquipmentCatalogStatus.AVAILABLE

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
                    equipmentCatalog = uiState.returnEquipmentCatalog,
                    equipmentCatalogStatus = uiState.returnEquipmentCatalogStatus,
                    shortageEquipment = uiState.returnShortageEquipment[line.id].orEmpty(),
                    shortageQuantity = uiState.returnShortageQuantity[line.id].orEmpty(),
                    onOpenPhotos = { onOpenPhotos(line.id) },
                    onConfirmEquipment = { onConfirmEquipment(line.id, it) },
                    onShortageChanged = { equipmentId, quantity ->
                        onUpdateShortage(line.id, equipmentId, quantity)
                    },
                    enabled = inspectionAvailable && !uiState.busy,
                )
            }
            item {
                ManagerPanel {
                    Text("Завершение осмотра", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Для любого действия нужна минимум одна фотография у каждой строки. Для принятия без сметы подтвердите комплектность мебели по всем строкам.",
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
                        "Для сметы выберите недостающее оборудование из справочника и укажите количество для каждой строки.",
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
                        "Будет создан запрос на смету по указанным недостачам и фотографиям."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingAction = null
                    if (action == ReturnAction.Accept) onAccept(onBack) else onCreateEstimate(onBack)
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
    equipmentCatalog: List<EquipmentCatalogItemDto>,
    equipmentCatalogStatus: ReturnEquipmentCatalogStatus,
    shortageEquipment: String,
    shortageQuantity: String,
    onOpenPhotos: () -> Unit,
    onConfirmEquipment: (Boolean) -> Unit,
    onShortageChanged: (equipmentId: String, quantity: String) -> Unit,
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
        Text("Недостача для сметы", style = MaterialTheme.typography.labelLarge)
        if (equipmentCatalogStatus == ReturnEquipmentCatalogStatus.AVAILABLE) {
            ReturnShortageEquipmentSelector(
                equipmentCatalog = equipmentCatalog,
                selectedEquipmentId = shortageEquipment,
                enabled = enabled,
                onSelected = { equipmentId ->
                    onShortageChanged(equipmentId, shortageQuantity)
                },
            )
            OutlinedTextField(
                value = shortageQuantity,
                onValueChange = { onShortageChanged(shortageEquipment, it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Количество") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = enabled,
            )
        } else {
            Text(
                returnEquipmentCatalogStatusMessage(equipmentCatalogStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReturnShortageEquipmentSelector(
    equipmentCatalog: List<EquipmentCatalogItemDto>,
    selectedEquipmentId: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember(selectedEquipmentId, equipmentCatalog) { mutableStateOf(false) }
    val selectedEquipment = equipmentCatalog.firstOrNull { it.id == selectedEquipmentId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { nextExpanded -> if (enabled) expanded = nextExpanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedEquipment?.name.orEmpty(),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = enabled,
                ),
            label = { Text("Оборудование") },
            placeholder = { Text("Выберите оборудование") },
            supportingText = {
                selectedEquipment?.category?.takeIf(String::isNotBlank)?.let { category ->
                    Text(category)
                }
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            readOnly = true,
            enabled = enabled,
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            equipmentCatalog.forEach { equipment ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(equipment.name)
                            equipment.category.takeIf(String::isNotBlank)?.let { category ->
                                Text(
                                    category,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelected(equipment.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun returnEquipmentCatalogStatusMessage(status: ReturnEquipmentCatalogStatus): String =
    when (status) {
        ReturnEquipmentCatalogStatus.NOT_LOADED,
        ReturnEquipmentCatalogStatus.LOADING ->
            "Загружаем справочник оборудования. Выбор для сметы станет доступен после загрузки."
        ReturnEquipmentCatalogStatus.EMPTY ->
            "В справочнике выбранного склада нет активного оборудования. Смету по недостаче создать нельзя."
        ReturnEquipmentCatalogStatus.UNAVAILABLE ->
            "Справочник оборудования недоступен. Повторно откройте возврат после восстановления связи."
        ReturnEquipmentCatalogStatus.AVAILABLE -> ""
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
