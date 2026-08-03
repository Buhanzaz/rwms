package dev.buhanzaz.rwms.manager.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.manager.network.InventoryCompletionPreviewDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.ui.InventoryEditorState
import dev.buhanzaz.rwms.manager.ui.InventoryFurnitureDisposition
import dev.buhanzaz.rwms.manager.ui.InventorySemanticChange
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.canCompleteInventory
import dev.buhanzaz.rwms.manager.ui.inventoryBlockingCompletionRisks
import dev.buhanzaz.rwms.manager.ui.inventoryBusinessStatus
import dev.buhanzaz.rwms.manager.ui.inventoryBusinessStatusLabel
import dev.buhanzaz.rwms.manager.ui.inventoryCategoryOptions
import dev.buhanzaz.rwms.manager.ui.inventoryCharacteristicOptions
import dev.buhanzaz.rwms.manager.ui.inventoryCharacteristicsDisplayValue
import dev.buhanzaz.rwms.manager.ui.inventoryCompletionRiskCounts
import dev.buhanzaz.rwms.manager.ui.inventoryCompletionRiskLabel
import dev.buhanzaz.rwms.manager.ui.inventoryCreationValidationError
import dev.buhanzaz.rwms.manager.ui.inventoryDimensionOptions
import dev.buhanzaz.rwms.manager.ui.inventoryEquipmentObservationValidationError
import dev.buhanzaz.rwms.manager.ui.inventoryEquipmentQuantityText
import dev.buhanzaz.rwms.manager.ui.hasObservedFurniture
import dev.buhanzaz.rwms.manager.ui.inventoryFinishingOptions
import dev.buhanzaz.rwms.manager.ui.inventoryInspectionLabel
import dev.buhanzaz.rwms.manager.ui.inventoryPassportFacts
import dev.buhanzaz.rwms.manager.ui.inventoryPhotoValidationError
import dev.buhanzaz.rwms.manager.ui.inventoryRentalItemSuggestions
import dev.buhanzaz.rwms.manager.ui.inventoryRentalTypeOptions
import dev.buhanzaz.rwms.manager.ui.inventorySemanticChanges
import dev.buhanzaz.rwms.manager.ui.hasExactInventoryRentalItemNumber
import dev.buhanzaz.rwms.manager.ui.withInventoryRentalType
import dev.buhanzaz.rwms.manager.ui.logisticsPlanningValidationError
import dev.buhanzaz.rwms.manager.ui.toMaintenancePlanEditor
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerPanel
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoPreview
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoGalleryDialog
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold
import dev.buhanzaz.rwms.manager.ui.components.StatusPill
import dev.buhanzaz.rwms.manager.ui.components.StatusPillEmphasis
import dev.buhanzaz.rwms.manager.ui.components.copyManagerPhotoToAppCache
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryDashboardScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoadInventory: () -> Unit,
    onStartInventory: () -> Unit,
    onPrepareNewNumber: (String, () -> Unit) -> Unit,
    onOpenEditor: () -> Unit,
    onResolveConflict: (
        InventoryFindingDto,
        String,
        String?,
        () -> Unit,
    ) -> Unit,
    onSupplementInspection: (InventoryFindingDto, () -> Unit) -> Unit,
    onPreviewCompletion: () -> Unit,
    onDismissCompletion: () -> Unit,
    onCompleteInventory: (Boolean, Boolean) -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoadInventory()
    }
    var number by remember(uiState.inventorySession?.id) { mutableStateOf("") }
    var confirmStart by remember { mutableStateOf(false) }
    var selectedConflictFindingId by remember(uiState.inventorySession?.id) {
        mutableStateOf<String?>(null)
    }
    var filters by remember(uiState.inventorySession?.id) {
        mutableStateOf(InventoryHistoryFilters())
    }
    val openExistingNumber: (String) -> Unit = { selectedNumber ->
        onPrepareNewNumber(selectedNumber) {
            number = ""
            onOpenEditor()
        }
    }
    val addNewNumber: (String) -> Unit = { selectedNumber ->
        onPrepareNewNumber(selectedNumber) {
            number = ""
            onOpenEditor()
        }
    }
    val openFinding: (InventoryFindingDto) -> Unit = { finding ->
        onSupplementInspection(finding, onOpenEditor)
    }
    val filteredFindings = remember(uiState.inventoryFindings, filters) {
        uiState.inventoryFindings.filter(filters::matches)
    }

    ManagerScreenScaffold(title = "Инвентаризация", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val session = uiState.inventorySession
            if (session == null) {
                item {
                    EmptyState(
                        title = "Нет активной инвентаризации",
                        description = "Начните сессию для выбранного склада, чтобы сверять номера бытовок и сохранять результаты.",
                        actionLabel = "Начать инвентаризацию",
                        onAction = { confirmStart = true },
                    )
                }
            } else {
                item { InventorySessionSummary(session) }
                item {
                    Button(
                        onClick = onPreviewCompletion,
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Проверить и завершить")
                    }
                }
                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ManagerPanel {
                            Text("Проверить бытовку", style = MaterialTheme.typography.titleMedium)
                            InventoryNumberSearch(
                                value = number,
                                onValueChange = { number = it },
                                enabled = !uiState.busy,
                                rentalItems = uiState.inventoryRentalItems,
                                onChooseExistingNumber = openExistingNumber,
                                onAddNewNumber = addNewNumber,
                            )
                        }
                        InventoryHistoryHeader(
                            findings = uiState.inventoryFindings,
                            filters = filters,
                            onFiltersChange = { filters = it },
                        )
                    }
                }
                if (filteredFindings.isEmpty()) {
                    item {
                        EmptyState(
                            title = if (uiState.inventoryFindings.isEmpty()) {
                                "Проверок пока нет"
                            } else {
                                "По выбранным фильтрам ничего нет"
                            },
                            description = if (uiState.inventoryFindings.isEmpty()) {
                                "Результаты появятся здесь после первой проверки номера."
                            } else {
                                "Измените или сбросьте фильтры истории."
                            },
                        )
                    }
                } else {
                    items(
                        count = filteredFindings.size,
                        key = { filteredFindings[it].id },
                    ) { index ->
                        val finding = filteredFindings[index]
                        InventoryFindingCard(
                            finding = finding,
                            enabled = !uiState.busy,
                            onView = {
                                openFinding(finding)
                            },
                            onOpenConflict = if (finding.conflicts.isEmpty()) {
                                null
                            } else {
                                { selectedConflictFindingId = finding.id }
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Начать инвентаризацию?") },
            text = { Text("Будет создана активная сессия для текущего склада.") },
            confirmButton = {
                Button(onClick = {
                    confirmStart = false
                    onStartInventory()
                }) { Text("Начать") }
            },
            dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("Отмена") } },
        )
    }

    val selectedConflict = selectedConflictFindingId?.let { findingId ->
        uiState.inventoryFindings.firstOrNull { it.id == findingId }
    }
    if (selectedConflict != null) {
        InventoryConflictDialog(
            finding = selectedConflict,
            busy = uiState.busy,
            onDismiss = { selectedConflictFindingId = null },
            onResolve = { strategy, reason ->
                onResolveConflict(
                    selectedConflict,
                    strategy,
                    reason,
                ) {
                    selectedConflictFindingId = null
                }
            },
            onSupplementInspection = {
                selectedConflictFindingId = null
                openFinding(selectedConflict)
            },
        )
    }

    uiState.inventoryCompletionPreview?.let { preview ->
        InventoryCompletionDialog(
            preview = preview,
            findings = uiState.inventoryFindings,
            busy = uiState.busy,
            onDismiss = onDismissCompletion,
            onRefresh = onPreviewCompletion,
            onResolveConflict = { findingId ->
                onDismissCompletion()
                selectedConflictFindingId = findingId
            },
            onComplete = onCompleteInventory,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryNumberSearch(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    rentalItems: List<RentalItemDto>,
    onChooseExistingNumber: (String) -> Unit,
    onAddNewNumber: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val suggestions = inventoryRentalItemSuggestions(rentalItems, value)
    val canAdd = value.isNotBlank() && !hasExactInventoryRentalItemNumber(rentalItems, value)
    val chooseExisting: (String) -> Unit = { number ->
        expanded = false
        focusManager.clearFocus()
        onChooseExistingNumber(number)
    }
    val addNew: (String) -> Unit = { number ->
        expanded = false
        focusManager.clearFocus()
        onAddNewNumber(number)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { nextExpanded ->
            if (enabled) expanded = nextExpanded
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                    enabled = enabled,
                )
                .onFocusChanged { state ->
                    if (state.isFocused && enabled) expanded = true
                },
            label = { Text("Номер бытовки") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    val exact = rentalItems.firstOrNull {
                        it.number.equals(value.trim(), ignoreCase = true)
                    }
                    if (exact != null) {
                        chooseExisting(exact.number)
                    } else if (canAdd) {
                        addNew(value.trim())
                    }
                },
            ),
        )
        ExposedDropdownMenu(
            expanded = expanded && (suggestions.isNotEmpty() || canAdd),
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            if (canAdd) {
                DropdownMenuItem(
                    text = { Text("+ Добавить бытовку «${value.trim()}»") },
                    onClick = { addNew(value.trim()) },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
                if (suggestions.isNotEmpty()) HorizontalDivider()
            }
            suggestions.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.number)
                            item.rentalType?.takeIf(String::isNotBlank)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "Просмотреть",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    trailingIcon = {
                        Text(
                            "→",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { chooseExisting(item.number) },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
}

private enum class InventoryInspectionFilter(val label: String) {
    Checked("Проверено"),
    NotChecked("Не проверено"),
}

private enum class InventoryConflictFilter(val label: String) {
    WithConflict("С конфликтом"),
    WithoutConflict("Без конфликта"),
}

private data class InventoryHistoryFilters(
    val inspection: InventoryInspectionFilter? = null,
    val conflict: InventoryConflictFilter? = null,
    val rentalType: String? = null,
    val dimensions: String? = null,
    val category: String? = null,
) {
    val hasActiveFilters: Boolean
        get() = inspection != null || conflict != null || rentalType != null ||
            dimensions != null || category != null

    fun matches(finding: InventoryFindingDto): Boolean {
        val facts = finding.inventoryPassportFacts()
        val checked = finding.inspection != "NOT_INSPECTED"
        return when (inspection) {
            InventoryInspectionFilter.Checked -> checked
            InventoryInspectionFilter.NotChecked -> !checked
            null -> true
        } && when (conflict) {
            InventoryConflictFilter.WithConflict -> finding.conflicts.isNotEmpty()
            InventoryConflictFilter.WithoutConflict -> finding.conflicts.isEmpty()
            null -> true
        } && (rentalType == null || facts.rentalType == rentalType) &&
            (dimensions == null || facts.dimensions == dimensions) &&
            (category == null || facts.category == category)
    }
}

@Composable
private fun InventoryHistoryHeader(
    findings: List<InventoryFindingDto>,
    filters: InventoryHistoryFilters,
    onFiltersChange: (InventoryHistoryFilters) -> Unit,
) {
    val types = remember(findings) {
        findings.mapNotNull { it.inventoryPassportFacts().rentalType }.distinct().sorted()
    }
    val dimensions = remember(findings) {
        findings.mapNotNull { it.inventoryPassportFacts().dimensions }.distinct().sorted()
    }
    val categories = remember(findings) {
        findings.mapNotNull { it.inventoryPassportFacts().category }.distinct().sorted()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("История сессии", style = MaterialTheme.typography.titleMedium)
        InventoryHistoryFilterMenu(
            defaultLabel = "Проверка",
            selectedLabel = filters.inspection?.label,
            entries = listOf("Все" to null) + InventoryInspectionFilter.entries.map {
                it.label to it
            },
            onSelected = { inspection ->
                onFiltersChange(filters.copy(inspection = inspection))
            },
        )
        InventoryHistoryFilterMenu(
            defaultLabel = "Конфликты",
            selectedLabel = filters.conflict?.label,
            entries = listOf("Все" to null) + InventoryConflictFilter.entries.map {
                it.label to it
            },
            onSelected = { conflict ->
                onFiltersChange(filters.copy(conflict = conflict))
            },
        )
        InventoryHistoryFilterMenu(
            defaultLabel = "Тип",
            selectedLabel = filters.rentalType,
            entries = listOf("Все" to null) + types.map { it to it },
            onSelected = { type -> onFiltersChange(filters.copy(rentalType = type)) },
        )
        InventoryHistoryFilterMenu(
            defaultLabel = "Габариты",
            selectedLabel = filters.dimensions,
            entries = listOf("Все" to null) + dimensions.map { it to it },
            onSelected = { value -> onFiltersChange(filters.copy(dimensions = value)) },
        )
        InventoryHistoryFilterMenu(
            defaultLabel = "Категория",
            selectedLabel = filters.category,
            entries = listOf("Все" to null) + categories.map { it to it },
            onSelected = { value -> onFiltersChange(filters.copy(category = value)) },
        )
        if (filters.hasActiveFilters) {
            TextButton(onClick = { onFiltersChange(InventoryHistoryFilters()) }) {
                Text("Сбросить")
            }
        }
    }
}

@Composable
private fun <T> InventoryHistoryFilterMenu(
    defaultLabel: String,
    selectedLabel: String?,
    entries: List<Pair<String, T?>>,
    onSelected: (T?) -> Unit,
) {
    var expanded by remember(defaultLabel, selectedLabel, entries) { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selectedLabel != null,
            onClick = { expanded = true },
            label = { Text(selectedLabel ?: defaultLabel) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            entries.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun InventorySessionSummary(session: InventorySessionDto) {
    ManagerPanel {
        StatusPill(
            label = inventoryLifecycleLabel(session.lifecycle),
            emphasis = StatusPillEmphasis.Positive,
        )
        Text("Активная сессия", style = MaterialTheme.typography.titleLarge)
        Text(
            "Проверено ${session.inspectedCount} из ${session.expectedCount}; найдено: ${session.findingCount}.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InventoryCompletionDialog(
    preview: InventoryCompletionPreviewDto,
    findings: List<InventoryFindingDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onResolveConflict: (String) -> Unit,
    onComplete: (confirmNotInspected: Boolean, confirmMissing: Boolean) -> Unit,
) {
    val riskCounts = preview.inventoryCompletionRiskCounts()
    val blockingRisks = preview.inventoryBlockingCompletionRisks()
    val conflictFindingId = preview.risks
        .firstOrNull { it.code == "CONFLICT" }
        ?.findingId
        ?.takeIf { findingId ->
            findings.any { finding -> finding.id == findingId && finding.conflicts.isNotEmpty() }
        }
    var confirmNotInspected by remember(
        preview.acknowledgementSha256,
        preview.validationSha256,
    ) {
        mutableStateOf(false)
    }
    var confirmMissing by remember(
        preview.acknowledgementSha256,
        preview.validationSha256,
    ) {
        mutableStateOf(false)
    }
    var showPositions by remember(preview.validationSha256) { mutableStateOf(false) }
    var positionFilter by remember(preview.validationSha256) {
        mutableStateOf(InventoryPositionFilter.ALL)
    }
    var expandedFindingId by remember(preview.validationSha256) {
        mutableStateOf<String?>(null)
    }
    val visiblePositionFindings = remember(findings, positionFilter) {
        findings.mapNotNull { finding ->
            val lines = finding.frozenPlan?.lines.orEmpty().filter { line ->
                when (positionFilter) {
                    InventoryPositionFilter.ALL -> true
                    InventoryPositionFilter.WORKS -> line.lineType == "WORK"
                    InventoryPositionFilter.MATERIALS -> line.lineType == "MATERIAL"
                }
            }
            if (lines.isEmpty()) null else finding to lines
        }
    }
    val canComplete = preview.canCompleteInventory(
        confirmNotInspected = confirmNotInspected,
        confirmMissing = confirmMissing,
    )

    AlertDialog(
        onDismissRequest = {
            if (!busy) onDismiss()
        },
        title = { Text("Завершение инвентаризации") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Свежая проверка: ${preview.validatedAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    ManagerPanel {
                        Text("Итог", style = MaterialTheme.typography.titleMedium)
                        InventoryCompletionMetric(
                            label = "Ожидалось",
                            value = preview.statistics.expectedCount.toString(),
                        )
                        InventoryCompletionMetric(
                            label = "Проверено",
                            value = preview.statistics.inspectedCount.toString(),
                        )
                        InventoryCompletionMetric(
                            label = "Не найдено",
                            value = preview.statistics.missingCount.toString(),
                        )
                        InventoryCompletionMetric(
                            label = "Добавлено",
                            value = preview.statistics.addedCount.toString(),
                        )
                        InventoryCompletionMetric(
                            label = "С работами",
                            value = preview.statistics.withWorkCount.toString(),
                        )
                        InventoryCompletionMetric(
                            label = "Работы",
                            value = "${preview.statistics.workLineCount} · " +
                                formatInventoryMinor(preview.statistics.workTotalMinor),
                        )
                        InventoryCompletionMetric(
                            label = "Материалы",
                            value = "${preview.statistics.materialLineCount} · " +
                                formatInventoryMinor(preview.statistics.materialTotalMinor),
                        )
                        InventoryCompletionMetric(
                            label = "Плановое время работ",
                            value = formatInventoryMinutes(preview.statistics.normativeMinutes),
                        )
                        InventoryCompletionMetric(
                            label = "Длительность инвентаризации",
                            value = formatInventoryDuration(preview.statistics.durationSeconds),
                        )
                        InventoryCompletionMetric(
                            label = "Конфликты",
                            value = preview.statistics.conflictCount.toString(),
                        )
                        InventoryCompletionMetric(
                            label = "Сумма",
                            value = formatInventoryMinor(preview.statistics.grandTotalMinor),
                        )
                        OutlinedButton(
                            onClick = { showPositions = !showPositions },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (showPositions) "Скрыть позиции" else "Посмотреть позиции")
                        }
                    }
                }
                if (showPositions) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(
                                    androidx.compose.foundation.rememberScrollState(),
                                ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            InventoryPositionFilter.entries.forEach { filter ->
                                FilterChip(
                                    selected = positionFilter == filter,
                                    onClick = { positionFilter = filter },
                                    label = { Text(filter.label) },
                                )
                            }
                        }
                    }
                    if (visiblePositionFindings.isEmpty()) {
                        item {
                            Text(
                                "В текущей сессии нет выбранных позиций.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(
                            count = visiblePositionFindings.size,
                            key = { index -> visiblePositionFindings[index].first.id },
                        ) { index ->
                            val (finding, lines) = visiblePositionFindings[index]
                            InventoryCompletionFindingPositions(
                                finding = finding,
                                lines = lines,
                                expanded = expandedFindingId == finding.id,
                                onToggle = {
                                    expandedFindingId = if (
                                        expandedFindingId == finding.id
                                    ) {
                                        null
                                    } else {
                                        finding.id
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    Text("Риски", style = MaterialTheme.typography.titleMedium)
                }
                if (riskCounts.isEmpty()) {
                    item {
                        Text(
                            "Рисков нет. Инвентаризацию можно завершить.",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    items(
                        count = riskCounts.size,
                        key = { riskCounts.keys.sorted()[it] },
                    ) { index ->
                        val code = riskCounts.keys.sorted()[index]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                inventoryCompletionRiskLabel(code),
                                modifier = Modifier.weight(1f),
                            )
                            StatusPill(
                                label = riskCounts.getValue(code).toString(),
                                emphasis = if (code in setOf("MISSING", "NOT_INSPECTED")) {
                                    StatusPillEmphasis.Warning
                                } else {
                                    StatusPillEmphasis.Warning
                                },
                            )
                        }
                    }
                }
                if (blockingRisks.isNotEmpty()) {
                    item {
                        Text(
                            "Завершение заблокировано. Устраните указанные риски и обновите проверку.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (riskCounts.getOrDefault("NOT_INSPECTED", 0) > 0) {
                    item {
                        InventoryCompletionConfirmation(
                            checked = confirmNotInspected,
                            enabled = !busy && blockingRisks.isEmpty(),
                            text = "Подтверждаю завершение с непроверенными бытовками (${riskCounts.getValue("NOT_INSPECTED")})",
                            onCheckedChange = { confirmNotInspected = it },
                        )
                    }
                }
                if (riskCounts.getOrDefault("MISSING", 0) > 0) {
                    item {
                        InventoryCompletionConfirmation(
                            checked = confirmMissing,
                            enabled = !busy && blockingRisks.isEmpty(),
                            text = "Подтверждаю отсутствующие бытовки (${riskCounts.getValue("MISSING")})",
                            onCheckedChange = { confirmMissing = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onComplete(confirmNotInspected, confirmMissing)
                    },
                    enabled = canComplete && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Завершить инвентаризацию")
                }
                if (conflictFindingId != null) {
                    OutlinedButton(
                        onClick = { onResolveConflict(conflictFindingId) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Разрешить конфликт")
                    }
                }
                TextButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Обновить проверку")
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Закрыть")
                }
            }
        },
    )
}

private enum class InventoryPositionFilter(val label: String) {
    ALL("Все"),
    WORKS("Работы"),
    MATERIALS("Материалы"),
}

@Composable
private fun InventoryCompletionFindingPositions(
    finding: InventoryFindingDto,
    lines: List<dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanLineDto>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    ManagerPanel(onClick = onToggle) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                finding.displayCanonicalNumber,
                style = MaterialTheme.typography.titleMedium,
            )
            Text("${lines.size} поз.", style = MaterialTheme.typography.labelLarge)
        }
        lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    line.description,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${line.quantity} ${line.unit}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (expanded) {
            HorizontalDivider()
            val passport = finding.inventoryPassportFacts()
            InventoryPassportFact("Тип", passport.rentalType)
            InventoryPassportFact("Габариты", passport.dimensions)
            InventoryPassportFact("Отделка", passport.finishing)
            Text(
                "Фотографий: ${finding.media.size}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                finding.comment.ifBlank { "Без комментария" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Нажмите, чтобы открыть данные бытовки",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InventoryCompletionMetric(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun InventoryCompletionConfirmation(
    checked: Boolean,
    enabled: Boolean,
    text: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(text, modifier = Modifier.weight(1f))
    }
}

private fun formatInventoryMinor(value: Long): String =
    "${value / 100},${(value % 100).toString().padStart(2, '0')} ₽"

private fun formatInventoryMinutes(value: String): String {
    val total = value.toBigDecimalOrNull()?.toLong() ?: 0L
    val hours = total / 60
    val minutes = total % 60
    return if (hours == 0L) "$minutes мин" else "$hours ч $minutes мин"
}

private fun formatInventoryDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    return if (hours == 0L) "$minutes мин" else "$hours ч $minutes мин"
}

@Composable
private fun InventoryFindingCard(
    finding: InventoryFindingDto,
    enabled: Boolean,
    onView: () -> Unit,
    onOpenConflict: (() -> Unit)?,
) {
    val hasConflict = finding.conflicts.isNotEmpty()
    val passport = finding.inventoryPassportFacts()
    val checked = finding.inspection != "NOT_INSPECTED"
    ManagerPanel {
        Row(
            modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(
                label = if (hasConflict) "Конфликт" else "Без конфликта",
                emphasis = if (hasConflict) {
                    StatusPillEmphasis.Warning
                } else {
                    StatusPillEmphasis.Positive
                },
            )
            finding.inventoryBusinessStatus()?.let { status ->
                InventoryWarehouseStatusPill(
                    label = inventoryBusinessStatusLabel(status),
                    status = status,
                )
            }
        }
        Text(finding.displayCanonicalNumber, style = MaterialTheme.typography.titleMedium)
        InventoryPassportFact(
            label = "Тип",
            value = passport.rentalType,
        )
        InventoryPassportFact(
            label = "Габариты",
            value = passport.dimensions,
        )
        InventoryPassportFact(
            label = "Отделка",
            value = passport.finishing,
        )
        Text(
            finding.comment.ifBlank { "Без комментария" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (checked) {
            Text(
                "Источник: ${finding.inspectionSource ?: "Инвентаризация"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasConflict) {
            Text(
                finding.conflicts.joinToString("\n") { it.message },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Сверьте актуальные данные перед завершением инвентаризации.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (finding.media.isNotEmpty()) {
            Text("Фотографий в проверке: ${finding.media.size}", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onView,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = if (checked) {
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF166534),
                    contentColor = Color.White,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(if (checked) "Проверено" else "Просмотреть")
        }
        if (onOpenConflict != null) {
            OutlinedButton(
                onClick = onOpenConflict,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Разрешить конфликт")
            }
        }
    }
}

/**
 * Exact dark-theme palette from panel/src/index.css for the shared asset-service status values.
 * Android consumes the same status identifiers from the warehouse snapshot; it does not invent
 * mobile-only status names or states.
 */
@Composable
private fun InventoryWarehouseStatusPill(label: String, status: String) {
    val (background, foreground) = when (status) {
        "RENTED" -> Color(0xFF142B67) to Color(0xFFBFE0FF)
        "AFTER_RENT", "WAITING_ESTIMATE_CONFIRMATION", "WAITING_REPAIR_CHECK" ->
            Color(0xFF512B00) to Color(0xFFFFDF8D)
        "BOOKED", "RESERVED" -> Color(0xFF3C1F58) to Color(0xFFF1D8FF)
        "REPAIR" -> Color(0xFF602200) to Color(0xFFFFD3A4)
        "CAPITAL_REPAIR" -> Color(0xFF651818) to Color(0xFFFFD1CA)
        "SALE", "USED_SALE" -> Color(0xFF5A1938) to Color(0xFFFFCFE7)
        "FREE" -> Color(0xFF09350F) to Color(0xFFBEEDBE)
        "WAREHOUSE" -> Color(0xFF243540) to Color(0xFFD2E1EA)
        "OWN_NEEDS" -> Color(0xFF003432) to Color(0xFFA0F0E5)
        "IN_TRANSFER" -> Color(0xFF27272A) to Color(0xFFF9F9F9)
        "WRITTEN_OFF" -> Color(0xFF010202) to Color(0xFFF8F8F8)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        modifier = Modifier
            .background(background, androidx.compose.foundation.shape.RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = foreground,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun InventoryPassportFact(
    label: String,
    value: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value ?: "Нет данных",
            color = if (value == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InventoryConflictDialog(
    finding: InventoryFindingDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onResolve: (strategy: String, reason: String?) -> Unit,
    onSupplementInspection: () -> Unit,
) {
    var keepInspection by remember(finding.id, finding.findingRevision) {
        mutableStateOf(false)
    }
    var reason by remember(finding.id, finding.findingRevision) {
        mutableStateOf("")
    }
    val semanticChanges = finding.inventorySemanticChanges()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Конфликт: ${finding.displayCanonicalNumber}") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusPill(label = inventoryInspectionLabel(finding.inspection))
                        StatusPill(
                            label = "Конфликт",
                            emphasis = StatusPillEmphasis.Warning,
                        )
                        finding.inventoryBusinessStatus()?.let { status ->
                            StatusPill(label = inventoryBusinessStatusLabel(status))
                        }
                    }
                }
                item {
                    Text(
                        "Было → Стало",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (semanticChanges.isEmpty()) {
                    items(
                        count = finding.conflicts.size,
                        key = { finding.conflicts[it].code },
                    ) { index ->
                        val conflict = finding.conflicts[index]
                        InventoryComparisonRow(
                            change = InventorySemanticChange(
                                label = conflict.message,
                                before = conflict.expected ?: "Не указано",
                                after = conflict.actual ?: "Не указано",
                            ),
                        )
                    }
                } else {
                    items(
                        count = semanticChanges.size,
                        key = { semanticChanges[it].label },
                    ) { index ->
                        InventoryComparisonRow(semanticChanges[index])
                    }
                }
                if (keepInspection) {
                    item {
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { reason = it.take(2_000) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Причина") },
                            supportingText = {
                                Text("Обязательно для сохранения данных осмотра")
                            },
                            minLines = 3,
                            enabled = !busy,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (keepInspection) {
                    Button(
                        onClick = { onResolve("KEEP_INSPECTION", reason) },
                        enabled = reason.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Оставить данные осмотра")
                    }
                    TextButton(
                        onClick = {
                            keepInspection = false
                            reason = ""
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Назад к действиям")
                    }
                } else {
                    Button(
                        onClick = { onResolve("ACCEPT_REGISTRY", null) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Принять реестр")
                    }
                    OutlinedButton(
                        onClick = { keepInspection = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Оставить данные осмотра")
                    }
                    OutlinedButton(
                        onClick = onSupplementInspection,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Дополнить осмотр")
                    }
                }
            }
        },
    )
}

@Composable
private fun InventoryComparisonRow(change: InventorySemanticChange) {
    ManagerPanel {
        Text(change.label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Было",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(change.before, style = MaterialTheme.typography.bodySmall)
            }
            Text("→", color = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Стало",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(change.after, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun InventoryEditorScreen(
    editor: InventoryEditorState?,
    busy: Boolean,
    onBack: () -> Unit,
    onEdit: ((InventoryEditorState) -> InventoryEditorState) -> Unit,
    onChooseOrigin: (String) -> Unit,
    onOpenPhotos: () -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Проверка бытовки", onBack = onBack) { padding ->
            EmptyState(
                title = "Проверка не найдена",
                description = "Вернитесь к активной инвентаризации и введите номер бытовки заново.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    var showOriginPrompt by remember(editor.findingId) {
        mutableStateOf(editor.isCreation && editor.creationOrigin == null)
    }

    ManagerScreenScaffold(title = "Проверка бытовки", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ManagerPanel {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        StatusPill(
                            label = if (editor.isCreation) {
                                "Объект не найден"
                            } else {
                                "Найден в сессии"
                            },
                            emphasis = if (editor.isCreation) {
                                StatusPillEmphasis.Warning
                            } else {
                                StatusPillEmphasis.Positive
                            },
                        )
                        if (!editor.isCreation) {
                            editor.finding?.inventoryBusinessStatus()?.let { status ->
                                StatusPill(label = inventoryBusinessStatusLabel(status))
                            }
                        }
                    }
                    Text(editor.number, style = MaterialTheme.typography.headlineSmall)
                    if (!editor.canInspect) {
                        Text(
                            editor.finding?.conflicts?.joinToString("\n") { it.message }
                                ?: "Конфликт сверки не позволяет сохранить проверку.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (editor.isCreation) {
                        Text(
                            "Выберите происхождение бытовки и заполните паспорт перед подтверждением.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (editor.isCreation) {
                item {
                    ManagerPanel {
                        Text("Происхождение", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                when (editor.creationOrigin) {
                                    "ADDED_NEW" -> "Новая бытовка"
                                    "ADDED_USED" -> "Б/у бытовка"
                                    else -> "Не выбрано"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            TextButton(onClick = { showOriginPrompt = true }) {
                                Text("Изменить")
                            }
                        }
                    }
                }
            }
            item {
                PassportEditor(editor = editor, onEdit = onEdit)
            }
            item {
                Button(
                    onClick = onOpenPhotos,
                    enabled = editor.canInspect && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Добавить фотографии")
                }
            }
        }
    }
    if (showOriginPrompt) {
        AlertDialog(
            onDismissRequest = { showOriginPrompt = false },
            title = { Text("Добавить бытовку") },
            text = {
                Text(
                    "Номер ${editor.number} не найден. Какую бытовку добавить?",
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onChooseOrigin("ADDED_USED")
                            showOriginPrompt = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Добавить б/у")
                    }
                    Button(
                        onClick = {
                            onChooseOrigin("ADDED_NEW")
                            showOriginPrompt = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Добавить новую")
                    }
                }
            },
        )
    }
}

@Composable
private fun PassportEditor(
    editor: InventoryEditorState,
    onEdit: ((InventoryEditorState) -> InventoryEditorState) -> Unit,
) {
    ManagerPanel {
        Text("Паспорт бытовки", style = MaterialTheme.typography.titleMedium)
        Text(
            "Поле номера задаётся сервисом и не меняется в приложении.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = editor.number,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Номер") },
            readOnly = true,
        )
        PassportDropdown(
            label = "Категория",
            value = editor.category,
            options = editor.inventoryCategoryOptions(),
            enabled = !editor.isCreation || editor.creationOrigin == "ADDED_USED",
        ) { value ->
            onEdit { it.copy(category = value) }
        }
        PassportDropdown(
            label = "Тип бытовки",
            value = editor.rentalType,
            options = editor.inventoryRentalTypeOptions(),
        ) { value ->
            onEdit { it.withInventoryRentalType(value) }
        }
        PassportDropdown(
            label = "Габариты",
            value = editor.dimensions,
            options = editor.inventoryDimensionOptions(),
            enabled = editor.rentalType.isNotBlank(),
        ) { value ->
            onEdit { it.copy(dimensions = value) }
        }
        PassportDropdown(
            label = "Отделка",
            value = editor.finishing,
            options = editor.inventoryFinishingOptions(),
        ) { value ->
            onEdit { it.copy(finishing = value) }
        }
        InventoryCharacteristicsSelector(editor = editor, onEdit = onEdit)
        Text("Линолеум", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = editor.linoleum == true,
                onClick = { onEdit { it.copy(linoleum = true) } },
                label = { Text("Есть") },
            )
            FilterChip(
                selected = editor.linoleum == false,
                onClick = { onEdit { it.copy(linoleum = false) } },
                label = { Text("Нет") },
            )
            if (!editor.isCreation) {
                FilterChip(
                    selected = editor.linoleum == null,
                    onClick = { onEdit { it.copy(linoleum = null) } },
                    label = { Text("Не указано") },
                )
            }
        }
        if (editor.isSanitary) {
            Text("Настройки санблока", style = MaterialTheme.typography.titleSmall)
            InventoryCounter(
                label = "Туалеты",
                value = editor.sanitaryToilets,
            ) { value ->
                onEdit { it.copy(sanitaryToilets = value) }
            }
            InventoryCounter(
                label = "Раковины",
                value = editor.sanitarySinks,
            ) { value ->
                onEdit { it.copy(sanitarySinks = value) }
            }
            InventoryCounter(
                label = "Душевые",
                value = editor.sanitaryShowers,
            ) { value ->
                onEdit { it.copy(sanitaryShowers = value) }
            }
        }
        PassportTextField("Комментарий проверки", editor.comment, singleLine = false) { value ->
            onEdit { it.copy(comment = value) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassportDropdown(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember(value, options) { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { nextExpanded ->
            if (enabled && options.isNotEmpty()) expanded = nextExpanded
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = enabled && options.isNotEmpty(),
                ),
            label = { Text(label) },
            placeholder = { Text("Выберите значение") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryCharacteristicsSelector(
    editor: InventoryEditorState,
    onEdit: ((InventoryEditorState) -> InventoryEditorState) -> Unit,
) {
    var expanded by remember(editor.findingId) { mutableStateOf(false) }
    val options = editor.inventoryCharacteristicOptions()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { nextExpanded ->
            if (options.isNotEmpty()) expanded = nextExpanded
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = inventoryCharacteristicsDisplayValue(editor.characteristics),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = options.isNotEmpty(),
                ),
            label = { Text("Характеристики") },
            placeholder = { Text("Выберите характеристики") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            readOnly = true,
            enabled = options.isNotEmpty(),
            singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { option ->
                val selected = option in editor.characteristics
                DropdownMenuItem(
                    text = { Text(option) },
                    trailingIcon = {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        onEdit {
                            it.copy(
                                characteristics = if (selected) {
                                    it.characteristics - option
                                } else {
                                    (it.characteristics + option).distinct()
                                },
                            )
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun InventoryCounter(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f).padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(
            onClick = { onValueChange((value - 1).coerceAtLeast(0)) },
            enabled = value > 0,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("−")
        }
        Text(
            value.toString(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(
            onClick = { onValueChange(value + 1) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("+")
        }
    }
}

@Composable
private fun PassportTextField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InventoryPhotosScreen(
    editor: InventoryEditorState?,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenCamera: () -> Unit,
    onAddPhoto: (String) -> Unit,
    onSelectCoverPhoto: (String) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onRotatePhoto: (String) -> Unit,
    onAddFurniture: () -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Фотографии", onBack = onBack) { padding ->
            EmptyState(
                title = "Проверка не найдена",
                description = "Вернитесь к проверке бытовки и откройте этот шаг снова.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    val context = LocalContext.current
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            // Import once into the app-owned original. Rotation then rewrites this same file, and
            // MediaUploader sends these exact bytes instead of creating an upload-time copy.
            copyManagerPhotoToAppCache(context, uri)?.let(onAddPhoto)
        }
    }
    var galleryPhotoUri by remember(editor.findingId) { mutableStateOf<String?>(null) }
    val photoError = editor.inventoryPhotoValidationError()

    // Inventory uses the same photo step as estimates and repairs.  It is deliberately not
    // labelled as a separate "photo inventory" mode: a tap chooses the cover and a hold opens
    // the common fullscreen carousel below.
    ManagerScreenScaffold(title = INVENTORY_PHOTOS_TITLE, onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ManagerPanel {
                    Text("Фото состояния", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Выберите титульное фото.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onOpenCamera,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Фотография") }
                        FilledTonalButton(
                            onClick = { galleryLauncher.launch(arrayOf("image/*")) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Галерея") }
                    }
                }
            }
            if (editor.photoUris.isNotEmpty()) {
                item {
                    Text("Выберите титульное фото", style = MaterialTheme.typography.titleMedium)
                }
                items(
                    count = (editor.photoUris.size + 1) / 2,
                    key = { row -> "inventory-photo-row-$row" },
                ) { row ->
                    val photos = editor.photoUris.drop(row * 2).take(2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        photos.forEach { uri ->
                            val selected = editor.coverPhotoUri == uri
                            Column(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(4f / 3f)
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) {
                                                Color(0xFF0A84C6)
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                        )
                                        .padding(3.dp)
                                        .inventoryPhotoGesture(
                                            onClick = {
                                                if (!busy) onSelectCoverPhoto(uri)
                                            },
                                            onLongPress = { galleryPhotoUri = uri },
                                        )
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = if (selected) {
                                                "Титульная фотография"
                                            } else {
                                                "Выбрать титульной фотографией; удерживайте для просмотра"
                                            }
                                            if (!busy) {
                                                onClick(label = "Выбрать титульной фотографией") {
                                                    onSelectCoverPhoto(uri)
                                                    true
                                                }
                                            }
                                            onLongClick(label = "Открыть полноэкранный просмотр") {
                                                galleryPhotoUri = uri
                                                true
                                            }
                                        },
                                ) {
                                    ManagerPhotoPreview(
                                        photoUri = uri,
                                        modifier = Modifier.fillMaxSize(),
                                        rotationDegrees = editor.photoRotationDegrees[uri] ?: 0,
                                    )
                                }
                                TextButton(
                                    onClick = { onRemovePhoto(uri) },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Удалить") }
                            }
                        }
                        if (photos.size == 1) Box(modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                photoError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = onAddFurniture,
                    enabled = photoError == null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Добавить мебель") }
            }
        }
    }
    galleryPhotoUri?.let { initialPhoto ->
        val initialIndex = editor.photoUris.indexOf(initialPhoto).coerceAtLeast(0)
        ManagerPhotoGalleryDialog(
            title = "Фото состояния",
            photoUris = editor.photoUris,
            initialIndex = initialIndex,
            onRemovePhotoUri = onRemovePhoto,
            photoRotationDegrees = { uri -> editor.photoRotationDegrees[uri] ?: 0 },
            onRotatePhotoUri = onRotatePhoto,
            onDismiss = { galleryPhotoUri = null },
        )
    }
}

/** Keeps the inventory route and the camera route in the same ordinary photo flow. */
internal const val INVENTORY_PHOTOS_TITLE = "Фотографии"

@Composable
fun InventoryFurnitureDecisionScreen(
    editor: InventoryEditorState?,
    onBack: () -> Unit,
    onFurnitureAbsent: () -> Unit,
    onFurniturePresent: () -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Мебель", onBack = onBack) { padding ->
            EmptyState(
                title = "Проверка не найдена",
                description = "Вернитесь к фотографиям и откройте этот шаг снова.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    ManagerScreenScaffold(title = "Мебель", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ManagerPanel {
                Text("Есть мебель в бытовке?", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Если мебели нет, состав комплектации не будет изменён.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onFurnitureAbsent, modifier = Modifier.fillMaxWidth()) {
                Text("Мебели нет")
            }
            Button(onClick = onFurniturePresent, modifier = Modifier.fillMaxWidth()) {
                Text("Мебель есть")
            }
        }
    }
}

@Composable
fun InventoryFurnitureScreen(
    editor: InventoryEditorState?,
    busy: Boolean,
    onBack: () -> Unit,
    onEdit: ((InventoryEditorState) -> InventoryEditorState) -> Unit,
    onContinue: (InventoryFurnitureDisposition) -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Комплектация мебелью", onBack = onBack) { padding ->
            EmptyState(
                title = "Проверка не найдена",
                description = "Вернитесь к выбору мебели и откройте этот шаг снова.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    val validationError = editor.inventoryEquipmentObservationValidationError()
    var dispositionDialogVisible by remember(editor.findingId) { mutableStateOf(false) }
    ManagerScreenScaffold(title = "Комплектация мебелью", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Укажите фактическое количество. Ноль означает, что позиции нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editor.equipmentCatalog.isEmpty()) {
                item {
                    EmptyState(
                        title = "Номенклатура не найдена",
                        description = "В панели нет активных позиций дополнительного оборудования.",
                    )
                }
            } else {
                items(
                    count = editor.equipmentCatalog.size,
                    key = { editor.equipmentCatalog[it].id },
                ) { index ->
                    val equipment = editor.equipmentCatalog[index]
                    val currentText = editor.inventoryEquipmentQuantityText(equipment.id)
                    ManagerPanel {
                        Text(
                            equipment.name,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val next = (currentText.toLongOrNull() ?: 0L)
                                        .minus(1L).coerceAtLeast(0L).toString()
                                    onEdit { current ->
                                        current.copy(
                                            equipmentQuantities = current.equipmentQuantities +
                                                (equipment.id to next),
                                        )
                                    }
                                },
                                enabled = !busy && (currentText.toLongOrNull() ?: 0L) > 0L,
                            ) { Text("−") }
                            OutlinedTextField(
                                value = currentText,
                                onValueChange = { raw ->
                                    if (raw.isEmpty() || raw.all(Char::isDigit)) {
                                        onEdit { current ->
                                            current.copy(
                                                equipmentQuantities = current.equipmentQuantities +
                                                    (equipment.id to raw),
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.width(92.dp).padding(horizontal = 8.dp),
                                enabled = !busy,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    textAlign = TextAlign.Center,
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedButton(
                                onClick = {
                                    val next = (currentText.toLongOrNull() ?: 0L) + 1L
                                    onEdit { current ->
                                        current.copy(
                                            equipmentQuantities = current.equipmentQuantities +
                                                (equipment.id to next.toString()),
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("+") }
                        }
                    }
                }
            }
            item {
                validationError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        if (editor.hasObservedFurniture()) {
                            dispositionDialogVisible = true
                        } else {
                            onContinue(InventoryFurnitureDisposition.KEEP_IN_CABIN)
                        }
                    },
                    enabled = validationError == null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Продолжить") }
            }
        }
    }
    if (dispositionDialogVisible) {
        InventoryFurnitureDispositionDialog(
            onDismiss = { dispositionDialogVisible = false },
            onKeepInCabin = {
                dispositionDialogVisible = false
                onContinue(InventoryFurnitureDisposition.KEEP_IN_CABIN)
            },
            onMoveToStock = {
                dispositionDialogVisible = false
                onContinue(InventoryFurnitureDisposition.MOVE_TO_STOCK)
            },
        )
    }
}

@Composable
private fun InventoryFurnitureDispositionDialog(
    onDismiss: () -> Unit,
    onKeepInCabin: () -> Unit,
    onMoveToStock: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Что сделать с мебелью?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Сохраните фактическую комплектацию в бытовке или создайте задание " +
                        "на перемещение всей мебели на склад.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onKeepInCabin,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Оставить в бытовке") }
                Button(
                    onClick = onMoveToStock,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Переместить на склад") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
fun InventoryCatalogScreen(
    editor: InventoryEditorState?,
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onAddCatalogNodes: (List<CatalogNodeDto>, String, String, String?) -> Boolean,
    onRefreshCatalog: () -> Unit,
    onEditPlan: ((dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState) ->
        dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState) -> Unit,
    onContinue: () -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Каталог работ", onBack = onBack) { padding ->
            EmptyState(
                title = "Проверка не найдена",
                description = "Вернитесь к инвентаризации и откройте бытовку заново.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    ManagerScreenScaffold(title = "Каталог работ", onBack = onBack) { padding ->
        MaintenanceCatalogStep(
            editor = editor.toMaintenancePlanEditor(),
            uiState = uiState,
            onAddCatalogNodes = onAddCatalogNodes,
            onRefreshCatalog = onRefreshCatalog,
            onEdit = onEditPlan,
            onContinue = onContinue,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
fun InventoryConfirmationScreen(
    editor: InventoryEditorState?,
    busy: Boolean,
    onBack: () -> Unit,
    onEditPlan: ((dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState) ->
        dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState) -> Unit,
    onSave: (() -> Unit) -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Подтверждение", onBack = onBack) { padding ->
            EmptyState(
                title = "Проверка не найдена",
                description = "Вернитесь к проверке бытовки и начните снова.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    val maintenancePlan = editor.toMaintenancePlanEditor()
    val creationError = editor.inventoryCreationValidationError(
        hasCreationPhoto = editor.photoUris.isNotEmpty(),
        hasCoverPhoto = editor.coverPhotoUri in editor.photoUris,
    )
    val photoError = editor.inventoryPhotoValidationError()
    val furnitureError = editor.inventoryEquipmentObservationValidationError()
    val planError = when {
        editor.planLines.isEmpty() -> null
        !maintenanceCanAdvance(maintenancePlan, step = 3) ->
            "Проверьте количество, цену и маршрут работ и материалов"
        editor.planPriority !in 1..5 -> if (maintenancePlan.movementToRepair) {
            "Выберите приоритет перемещения от 1 до 5"
        } else {
            "Выберите приоритет ремонта от 1 до 5"
        }
        maintenancePlan.logisticsPlanningValidationError() != null ->
            requireNotNull(maintenancePlan.logisticsPlanningValidationError())
        else -> null
    }
    val validationError = photoError ?: furnitureError ?: creationError ?: planError
    ManagerScreenScaffold(title = "Подтверждение проверки", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ManagerPanel {
                Text(editor.number, style = MaterialTheme.typography.titleLarge)
                Text("Фотографий: ${editor.photoUris.size}")
                Text(
                    "Работы: ${editor.planLines.count { it.lineType == "WORK" }} единиц",
                )
                Text(
                    "Материалы: ${editor.planLines.count { it.lineType == "MATERIAL" }} единиц",
                )
                Text(
                    when (editor.equipmentObservationRequested) {
                        true -> when (editor.furnitureDisposition) {
                            InventoryFurnitureDisposition.KEEP_IN_CABIN ->
                                "После сохранения будет создано задание по комплектации мебели"
                            InventoryFurnitureDisposition.MOVE_TO_STOCK ->
                                "После сохранения будет создано задание перемещения мебели на склад"
                        }
                        false -> "Мебель не проверялась"
                        null -> "Не выбран вариант мебели"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editor.planLines.isNotEmpty()) {
                ManagerPanel {
                    RepairMovementLogisticsOptions(
                        editor = maintenancePlan,
                        enabled = !busy,
                        onEdit = onEditPlan,
                    )
                    Text(
                        if (maintenancePlan.movementToRepair) {
                            "Приоритет перемещения"
                        } else {
                            "Приоритет ремонта"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (maintenancePlan.movementToRepair) {
                        Text(
                            "После доставки ремонт появится в очереди работ с системным " +
                                "приоритетом 1.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (1..5).forEach { priority ->
                            FilterChip(
                                selected = editor.planPriority == priority,
                                onClick = {
                                    onEditPlan { current ->
                                        current.copy(priority = priority)
                                    }
                                },
                                enabled = !busy,
                                label = { Text(priority.toString()) },
                            )
                        }
                    }
                }
            }
            validationError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { onSave(onBack) },
                enabled = validationError == null && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (editor.isCreation) "Подтвердить добавление" else "Подтвердить проверку")
            }
        }
    }
}

internal const val INVENTORY_PHOTO_PREVIEW_HOLD_MILLIS = 1_500L

internal enum class InventoryPhotoGestureAction {
    SelectCover,
    OpenPreview,
}

internal fun inventoryPhotoGestureAction(holdTimedOut: Boolean): InventoryPhotoGestureAction =
    if (holdTimedOut) InventoryPhotoGestureAction.OpenPreview else InventoryPhotoGestureAction.SelectCover

/** A deliberate 1.5 second hold prevents an ordinary cover-photo tap from opening the viewer. */
private fun Modifier.inventoryPhotoGesture(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(onClick, onLongPress) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var timedOut = false
        val up = try {
            withTimeout(INVENTORY_PHOTO_PREVIEW_HOLD_MILLIS) { waitForUpOrCancellation() }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
            null
        }
        when (inventoryPhotoGestureAction(timedOut)) {
            InventoryPhotoGestureAction.OpenPreview -> {
                onLongPress()
                waitForUpOrCancellation()
            }
            InventoryPhotoGestureAction.SelectCover -> if (up != null) onClick()
        }
    }
}

private fun inventoryLifecycleLabel(value: String): String = when (value) {
    "ACTIVE" -> "Активна"
    "COMPLETED" -> "Завершена"
    else -> value
}
