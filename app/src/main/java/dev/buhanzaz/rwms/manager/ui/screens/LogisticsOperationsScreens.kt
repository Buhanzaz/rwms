package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.LogisticsLineDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.TransferEditorState
import dev.buhanzaz.rwms.manager.ui.WarehouseAccessPolicy
import dev.buhanzaz.rwms.manager.ui.shipmentFurnitureIsReady
import dev.buhanzaz.rwms.manager.ui.shipmentPlanRequiresFurnitureReadiness
import dev.buhanzaz.rwms.manager.ui.transferFurnitureIsReady
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerPanel
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold
import dev.buhanzaz.rwms.manager.ui.components.StatusPill
import dev.buhanzaz.rwms.manager.ui.components.StatusPillEmphasis

/** Lists shipment documents for the selected warehouse. */
@Composable
fun ShipmentsListScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onOpen: (String) -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoad()
    }
    ManagerScreenScaffold(title = "Отгрузки", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.shipments.isEmpty()) {
                item {
                    EmptyState(
                        title = "Отгрузок нет",
                        description = "Отгрузки создаются из заказов и появятся здесь автоматически.",
                    )
                }
            } else {
                items(
                    count = uiState.shipments.size,
                    key = { uiState.shipments[it].id },
                ) { index ->
                    LogisticsDocumentCard(
                        document = uiState.shipments[index],
                        uiState = uiState,
                        onClick = { onOpen(uiState.shipments[index].id) },
                    )
                }
            }
        }
    }
}

/** Renders shipment planning, preparation and cancellation commands for one document. */
@Composable
fun ShipmentDetailScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onPlan: (driver: String, date: String) -> Unit,
    onCreateFurnitureTasks: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val document = uiState.selectedShipment
    if (document == null) {
        MissingLogisticsDocument("Отгрузка не выбрана", onBack)
        return
    }
    var driver by remember(document.id, document.version) {
        mutableStateOf(document.driverSnapshot.orEmpty())
    }
    var date by remember(document.id, document.version) {
        mutableStateOf(document.scheduledDate.orEmpty())
    }
    var confirmCancel by remember(document.id) { mutableStateOf(false) }
    val readiness = uiState.shipmentFurnitureReadiness
    val furnitureReady = shipmentFurnitureIsReady(document, readiness)
    val canEdit = uiState.currentUser?.let { user ->
        WarehouseAccessPolicy.hasAccess(user, document.warehouseId, "EDIT")
    } == true

    ManagerScreenScaffold(title = "Отгрузка", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LogisticsDocumentSummary(document, uiState)
            }
            if (readiness != null) {
                item {
                    ManagerPanel {
                        Text("Мебель", style = MaterialTheme.typography.titleMedium)
                        Text("Состояние: ${furnitureReadinessLabel(readiness.state)}")
                        readiness.tasks.forEach { task ->
                            Text(
                                "${task.unitNumber}: ${taskBoardStateLabel(task.taskState)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (canEdit &&
                            document.state == "DRAFT" &&
                            readiness.state == "REQUIRES_TASK_CREATION"
                        ) {
                            FilledTonalButton(
                                onClick = onCreateFurnitureTasks,
                                enabled = !uiState.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Создать задания по мебели")
                            }
                        }
                    }
                }
            }
            if (canEdit &&
                (document.state == "DRAFT" || document.state == "AWAITING_CONFIRMATION")
            ) {
                item {
                    ManagerPanel {
                        Text("План отгрузки", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = driver,
                            onValueChange = { driver = it.take(512) },
                            label = { Text("Водитель") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = date,
                            onValueChange = { date = it.take(10) },
                            label = { Text("Дата ГГГГ-ММ-ДД") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onPlan(driver, date) },
                            enabled = !uiState.busy &&
                                (!document.shipmentPlanRequiresFurnitureReadiness() ||
                                    furnitureReady),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (document.state == "DRAFT") {
                                    "Запланировать отгрузку"
                                } else {
                                    "Изменить план"
                                },
                            )
                        }
                        if (document.shipmentPlanRequiresFurnitureReadiness() &&
                            !furnitureReady
                        ) {
                            Text(
                                "Сначала завершите задания по мебели.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (canEdit && document.state == "AWAITING_CONFIRMATION") {
                item {
                    Button(
                        onClick = onConfirm,
                        enabled = !uiState.busy && furnitureReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Подтвердить: отгружена")
                    }
                }
            }
            if (canEdit &&
                document.state in setOf("DRAFT", "PREPARING", "AWAITING_CONFIRMATION")
            ) {
                item {
                    FilledTonalButton(
                        onClick = { confirmCancel = true },
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Отменить отгрузку")
                    }
                }
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Отменить отгрузку?") },
            text = { Text("Сервис сохранит историю операции и отменит подготовку.") },
            confirmButton = {
                Button(onClick = {
                    confirmCancel = false
                    onCancel()
                }) { Text("Отменить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Назад") }
            },
        )
    }
}

/** Lists inter-warehouse transfer documents visible to the manager. */
@Composable
fun TransfersListScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoad()
    }
    val canCreate = uiState.currentUser?.let { user ->
        val sourceId = uiState.selectedWarehouseId
        sourceId != null &&
            WarehouseAccessPolicy.hasAccess(user, sourceId, "EDIT") &&
            uiState.warehouses.any { warehouse ->
                warehouse.id != sourceId &&
                    WarehouseAccessPolicy.hasAccess(user, warehouse.id, "EDIT")
            }
    } == true
    ManagerScreenScaffold(title = "Перемещения", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.transfers.isEmpty()) {
                item {
                    EmptyState(
                        title = "Перемещений нет",
                        description = "Создайте первое перемещение между доступными складами.",
                    )
                }
            } else {
                items(
                    count = uiState.transfers.size,
                    key = { uiState.transfers[it].id },
                ) { index ->
                    LogisticsDocumentCard(
                        document = uiState.transfers[index],
                        uiState = uiState,
                        onClick = { onOpen(uiState.transfers[index].id) },
                    )
                }
            }
            if (canCreate) {
                item {
                    Button(
                        onClick = onCreate,
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Создать перемещение")
                    }
                }
            }
        }
    }
}

/** Collects a version-fenced transfer request and its cabin lines. */
@Composable
fun TransferCreateScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onEdit: ((TransferEditorState) -> TransferEditorState) -> Unit,
    onToggleAsset: (String, Boolean) -> Unit,
    onFurnitureQuantity: (String, String, String) -> Unit,
    onResetFurniture: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val editor = uiState.transferEditor
    if (editor == null) {
        MissingLogisticsDocument("Создание перемещения не открыто", onBack)
        return
    }
    var destinationMenu by remember { mutableStateOf(false) }
    val destinations = uiState.warehouses.filter {
        it.id in editor.destinationWarehouseIds
    }
    val selectedDestination = destinations.firstOrNull {
        it.id == editor.destinationWarehouseId
    }

    ManagerScreenScaffold(title = "Новое перемещение", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ManagerPanel {
                    Text("Маршрут и дата", style = MaterialTheme.typography.titleMedium)
                    Column {
                        FilledTonalButton(
                            onClick = { destinationMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedDestination?.label ?: "Выберите склад назначения")
                        }
                        DropdownMenu(
                            expanded = destinationMenu,
                            onDismissRequest = { destinationMenu = false },
                        ) {
                            destinations.forEach { warehouse ->
                                DropdownMenuItem(
                                    text = { Text(warehouse.label) },
                                    onClick = {
                                        destinationMenu = false
                                        onEdit {
                                            it.copy(destinationWarehouseId = warehouse.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = editor.driverSnapshot,
                        onValueChange = { value ->
                            onEdit { it.copy(driverSnapshot = value.take(512)) }
                        },
                        label = { Text("Водитель (необязательно)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editor.scheduledDate,
                        onValueChange = { value ->
                            onEdit { it.copy(scheduledDate = value.take(10)) }
                        },
                        label = { Text("Дата задания ГГГГ-ММ-ДД") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Text("Свободные бытовки", style = MaterialTheme.typography.titleLarge)
            }
            if (editor.candidates.isEmpty()) {
                item {
                    EmptyState(
                        title = "Свободных бытовок нет",
                        description = "На складе-отправителе нет бытовок со статусом FREE.",
                    )
                }
            } else {
                items(
                    count = editor.candidates.size,
                    key = { editor.candidates[it].id },
                ) { index ->
                    val cabin = editor.candidates[index]
                    val selected = cabin.id in editor.selectedAssetIds
                    TransferCabinEditor(
                        cabin = cabin,
                        editor = editor,
                        selected = selected,
                        onSelected = { onToggleAsset(cabin.id, it) },
                        onFurnitureQuantity = { equipmentId, quantity ->
                            onFurnitureQuantity(cabin.id, equipmentId, quantity)
                        },
                        onResetFurniture = { onResetFurniture(cabin.id) },
                    )
                }
            }
            item {
                Button(
                    onClick = onCreate,
                    enabled = !uiState.busy &&
                        editor.destinationWarehouseId.isNotBlank() &&
                        editor.selectedAssetIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Создать перемещение")
                }
            }
        }
    }
}

@Composable
private fun TransferCabinEditor(
    cabin: RentalItemDto,
    editor: TransferEditorState,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
    onFurnitureQuantity: (String, String) -> Unit,
    onResetFurniture: () -> Unit,
) {
    var compositionOpen by remember(cabin.id) { mutableStateOf(false) }
    val replacement = editor.furnitureReplacements[cabin.id]
    val currentFurniture = replacement ?: cabin.contents
        .filter { content ->
            editor.furnitureCatalog.any { it.id == content.equipmentId }
        }
        .associate { it.equipmentId to it.quantity }

    ManagerPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = selected, onCheckedChange = onSelected)
            Column(modifier = Modifier.weight(1f)) {
                Text(cabin.number, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(cabin.rentalType, cabin.dimensions)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            FilledTonalButton(
                onClick = { compositionOpen = !compositionOpen },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (compositionOpen) "Скрыть состав" else "Изменить состав мебели")
            }
            if (compositionOpen) {
                if (editor.furnitureCatalog.isEmpty()) {
                    Text("В справочнике нет активной мебели.")
                } else {
                    editor.furnitureCatalog.forEach { equipment ->
                        OutlinedTextField(
                            value = currentFurniture[equipment.id]?.toString().orEmpty(),
                            onValueChange = { onFurnitureQuantity(equipment.id, it) },
                            label = { Text(equipment.name) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (replacement != null) {
                    TextButton(onClick = onResetFurniture) {
                        Text("Оставить текущий состав без изменений")
                    }
                }
            }
        }
    }
}

/** Renders the departure, arrival and cancellation lifecycle of one transfer. */
@Composable
fun TransferDetailScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onDepart: (String) -> Unit,
    onStartArrival: (String) -> Unit,
    onOpenArrivalPhotos: () -> Unit,
    onArrive: () -> Unit,
    onCloseArrival: () -> Unit,
    onCancel: () -> Unit,
    onReconcile: (String) -> Unit,
) {
    val document = uiState.selectedTransfer
    if (document == null) {
        MissingLogisticsDocument("Перемещение не выбрано", onBack)
        return
    }
    var reconcileReason by remember(document.id) { mutableStateOf("") }
    var confirmCancel by remember(document.id) { mutableStateOf(false) }
    val readiness = uiState.transferFurnitureReadiness
    val furnitureReady = transferFurnitureIsReady(document, readiness)
    val canManage = uiState.currentUser?.let { user ->
        WarehouseAccessPolicy.hasAccess(user, document.warehouseId, "MANAGE") &&
            document.destinationWarehouseId?.let { destinationId ->
                WarehouseAccessPolicy.hasAccess(user, destinationId, "MANAGE")
            } == true
    } == true

    ManagerScreenScaffold(title = "Перемещение", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LogisticsDocumentSummary(document, uiState) }
            if (readiness != null) {
                item {
                    ManagerPanel {
                        Text("Мебель", style = MaterialTheme.typography.titleMedium)
                        Text("Состояние: ${furnitureReadinessLabel(readiness.state)}")
                        readiness.tasks.forEach { task ->
                            Text("${task.unitNumber}: ${taskBoardStateLabel(task.taskState)}")
                        }
                    }
                }
            }
            items(
                count = document.lines.size,
                key = { document.lines[it].id },
            ) { index ->
                val line = document.lines[index]
                TransferLineCard(
                    document = document,
                    line = line,
                    cabinNumber = uiState.logisticsAssetLabels[line.assetId],
                    busy = uiState.busy,
                    canManage = canManage,
                    furnitureReady = furnitureReady,
                    arrivalSelected = uiState.transferArrivalLineId == line.id,
                    arrivalPhotoCount =
                        uiState.transferPhotoUris.size + uiState.transferReadyMedia.size,
                    onDepart = { onDepart(line.id) },
                    onStartArrival = { onStartArrival(line.id) },
                    onOpenArrivalPhotos = onOpenArrivalPhotos,
                    onArrive = onArrive,
                    onCloseArrival = onCloseArrival,
                )
            }
            if (canManage && document.state == "DRAFT") {
                item {
                    FilledTonalButton(
                        onClick = { confirmCancel = true },
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Отменить перемещение")
                    }
                }
            }
            if (canManage &&
                (document.state == "CONFLICT" ||
                    document.state == "RECONCILIATION_REQUIRED")
            ) {
                item {
                    ManagerPanel {
                        Text("Сверка", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = reconcileReason,
                            onValueChange = { reconcileReason = it.take(500) },
                            label = { Text("Причина") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onReconcile(reconcileReason) },
                            enabled = !uiState.busy && reconcileReason.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Выполнить сверку")
                        }
                    }
                }
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Отменить перемещение?") },
            text = { Text("Сервис отменит ещё не выполненные задания и сохранит историю.") },
            confirmButton = {
                Button(onClick = {
                    confirmCancel = false
                    onCancel()
                }) { Text("Отменить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Назад") }
            },
        )
    }
}

@Composable
private fun TransferLineCard(
    document: LogisticsDocumentDto,
    line: LogisticsLineDto,
    cabinNumber: String?,
    busy: Boolean,
    canManage: Boolean,
    furnitureReady: Boolean,
    arrivalSelected: Boolean,
    arrivalPhotoCount: Int,
    onDepart: () -> Unit,
    onStartArrival: () -> Unit,
    onOpenArrivalPhotos: () -> Unit,
    onArrive: () -> Unit,
    onCloseArrival: () -> Unit,
) {
    val canDepart = canManage && line.state == "PENDING" &&
        (document.state == "DRAFT" || document.state == "DEPARTING")
    val canArrive = canManage && line.state == "DEPARTED" &&
        (document.state == "IN_TRANSIT" || document.state == "ARRIVING")
    ManagerPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(label = "Строка ${line.lineNumber}")
            StatusPill(label = transferLineStateLabel(line.state))
        }
        Text(
            "Бытовка ${cabinNumber ?: "не указана"}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Версия бытовки: ${line.assetVersion} · версия строки: ${line.version}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canDepart) {
            Button(
                onClick = onDepart,
                enabled = !busy && furnitureReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Отправить")
            }
            if (!furnitureReady) {
                Text("Сначала завершите задания по мебели.")
            }
        }
        if (canArrive && !arrivalSelected) {
            Button(
                onClick = onStartArrival,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Принять")
            }
        }
        if (arrivalSelected) {
            Text("Фотографий приёмки: $arrivalPhotoCount")
            FilledTonalButton(
                onClick = onOpenArrivalPhotos,
                enabled = !busy && arrivalPhotoCount < 20,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Добавить фотографии")
            }
            Button(
                onClick = onArrive,
                enabled = !busy && arrivalPhotoCount in 1..20,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Подтвердить приёмку")
            }
            TextButton(onClick = onCloseArrival, enabled = !busy) {
                Text("Закрыть приёмку")
            }
        }
    }
}

@Composable
private fun LogisticsDocumentCard(
    document: LogisticsDocumentDto,
    uiState: ManagerUiState,
    onClick: () -> Unit,
) {
    ManagerPanel(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                label = logisticsStateLabel(document.state),
                emphasis = if (
                    document.state == "CONFLICT" ||
                    document.state == "RECONCILIATION_REQUIRED"
                ) {
                    StatusPillEmphasis.Warning
                } else {
                    StatusPillEmphasis.Neutral
                },
            )
            document.scheduledDate?.let { StatusPill(label = it) }
        }
        Text(
            if (document.documentType == "SHIPMENT") "Отгрузка" else "Перемещение",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            document.lines.joinToString(
                prefix = "Бытовки: ",
                separator = ", ",
            ) { line -> uiState.logisticsAssetLabels[line.assetId] ?: "бытовка" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Открыть ›", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LogisticsDocumentSummary(
    document: LogisticsDocumentDto,
    uiState: ManagerUiState,
) {
    ManagerPanel {
        StatusPill(label = logisticsStateLabel(document.state))
        Text(
            if (document.documentType == "SHIPMENT") "Отгрузка" else "Перемещение",
            style = MaterialTheme.typography.titleLarge,
        )
        document.partySnapshot?.let { Text("Контрагент: $it") }
        document.driverSnapshot?.let { Text("Водитель: $it") }
        document.scheduledDate?.let { Text("Дата: $it") }
        if (document.documentType == "TRANSFER") {
            val source = uiState.warehouses.firstOrNull { it.id == document.warehouseId }
            val destination = uiState.warehouses.firstOrNull {
                it.id == document.destinationWarehouseId
            }
            Text(
                "Маршрут: ${source?.label ?: "склад не указан"} → " +
                    (destination?.label ?: "склад не указан"),
            )
        }
    }
}

@Composable
private fun MissingLogisticsDocument(title: String, onBack: () -> Unit) {
    ManagerScreenScaffold(title = "Логистика", onBack = onBack) { padding ->
        EmptyState(
            title = title,
            description = "Вернитесь к списку и откройте документ снова.",
            modifier = Modifier.padding(padding),
        )
    }
}

private fun logisticsStateLabel(state: String): String = when (state) {
    "DRAFT" -> "Черновик"
    "PREPARING" -> "Подготовка"
    "AWAITING_CONFIRMATION" -> "Ждёт подтверждения"
    "CONFIRMING_PREPARATION" -> "Подтверждается"
    "SHIPPED" -> "Отгружено"
    "CANCELLING" -> "Отменяется"
    "DEPARTING" -> "Отправляется"
    "IN_TRANSIT" -> "В пути"
    "ARRIVING" -> "Принимается"
    "COMPLETED" -> "Завершено"
    "CANCELLED" -> "Отменено"
    "CONFLICT" -> "Конфликт"
    "RECONCILIATION_REQUIRED" -> "Требуется сверка"
    else -> state
}

private fun transferLineStateLabel(state: String): String = when (state) {
    "PENDING" -> "Ожидает отправки"
    "DEPARTING" -> "Отправляется"
    "DEPARTED" -> "В пути"
    "ARRIVING" -> "Принимается"
    "ARRIVED" -> "Принята"
    "CONFLICT" -> "Конфликт"
    "CANCELLED" -> "Отменена"
    else -> state
}

private fun furnitureReadinessLabel(state: String): String = when (state) {
    "NOT_REQUIRED" -> "не требуется"
    "READY" -> "готово"
    "REQUIRES_TASK_CREATION" -> "нужно создать задания"
    "AWAITING_TASK_COMPLETION" -> "задания выполняются"
    "BLOCKED" -> "требуется устранить конфликт"
    else -> state
}

private fun taskBoardStateLabel(state: String): String = when (state) {
    "COMPLETED" -> "завершено"
    "CANCELLED" -> "отменено"
    "CONFLICT", "RECONCILIATION_REQUIRED" -> "требуется сверка"
    else -> "в работе"
}
