package dev.buhanzaz.rwms.manager.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.ui.InventoryEditorState
import dev.buhanzaz.rwms.manager.ui.InventoryReinspectionMode
import dev.buhanzaz.rwms.manager.ui.InventorySemanticChange
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.findExactInventoryRentalItem
import dev.buhanzaz.rwms.manager.ui.hasInventoryRentalItemNumberPrefix
import dev.buhanzaz.rwms.manager.ui.inventoryBusinessStatus
import dev.buhanzaz.rwms.manager.ui.inventoryBusinessStatusLabel
import dev.buhanzaz.rwms.manager.ui.inventoryCategoryOptions
import dev.buhanzaz.rwms.manager.ui.inventoryCharacteristicOptions
import dev.buhanzaz.rwms.manager.ui.inventoryCharacteristicsDisplayValue
import dev.buhanzaz.rwms.manager.ui.inventoryCreationValidationError
import dev.buhanzaz.rwms.manager.ui.inventoryDimensionOptions
import dev.buhanzaz.rwms.manager.ui.inventoryEquipmentObservationValidationError
import dev.buhanzaz.rwms.manager.ui.inventoryEquipmentQuantityText
import dev.buhanzaz.rwms.manager.ui.inventoryFurnitureCatalog
import dev.buhanzaz.rwms.manager.ui.inventoryFinishingOptions
import dev.buhanzaz.rwms.manager.ui.inventoryInspectionLabel
import dev.buhanzaz.rwms.manager.ui.inventoryInspectionSourceLabel
import dev.buhanzaz.rwms.manager.ui.inventoryReinspectionOpenMode
import dev.buhanzaz.rwms.manager.ui.inventoryPassportFacts
import dev.buhanzaz.rwms.manager.ui.inventoryPhotoValidationError
import dev.buhanzaz.rwms.manager.ui.inventoryRentalTypeOptions
import dev.buhanzaz.rwms.manager.ui.inventorySemanticChanges
import dev.buhanzaz.rwms.manager.ui.matchesInventoryNumberPrefix
import dev.buhanzaz.rwms.manager.ui.persistedInventoryMediaReferences
import dev.buhanzaz.rwms.manager.ui.toMaintenancePlanEditor
import dev.buhanzaz.rwms.manager.ui.withInventoryRentalType
import dev.buhanzaz.rwms.manager.ui.withMovementToRepair
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerPanel
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoPreview
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoGalleryDialog
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold
import dev.buhanzaz.rwms.manager.ui.components.StatusPill
import dev.buhanzaz.rwms.manager.ui.components.StatusPillEmphasis
import dev.buhanzaz.rwms.manager.ui.components.copyManagerPhotoToAppCache
import dev.buhanzaz.rwms.manager.ui.components.isManagerVideoUri
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal const val INVENTORY_FIELD_ONLY_EMPTY_STATE_DESCRIPTION =
    "Активную инвентаризацию начинает менеджер в панели. Когда сессия появится для " +
        "выбранного склада, здесь можно будет проверять бытовки."

internal const val INVENTORY_AFTER_RENT_ESTIMATE_NOTICE =
    "Замечания по бытовке после аренды станут черновиком сметы после общей сверки " +
        "инвентаризации. Задача ремонта из этой проверки не создаётся."

internal const val INVENTORY_REINSPECTION_DIALOG_TITLE = "Бытовка уже проверена"
internal const val INVENTORY_REINSPECTION_SUPPLEMENT_LABEL = "Дополнить осмотр"
internal const val INVENTORY_REINSPECTION_REPLACE_LABEL = "Перезаписать осмотр"
internal const val INVENTORY_REVIEW_EDIT_LABEL = "Изменить осмотр"

internal fun inventoryAfterRentEstimateNotice(status: String?): String? =
    INVENTORY_AFTER_RENT_ESTIMATE_NOTICE.takeIf { status == "AFTER_RENT" }

@Composable
private fun InventoryStepActions(
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    readOnly: Boolean,
    onRequestEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(primaryLabel)
        }
        if (readOnly) {
            OutlinedButton(
                onClick = onRequestEdit,
                enabled = primaryEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(INVENTORY_REVIEW_EDIT_LABEL)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryDashboardScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoadInventory: () -> Unit,
    onPrepareNewNumber: (
        String,
        (InventoryFindingDto) -> Unit,
        () -> Unit,
    ) -> Unit,
    onOpenEditor: () -> Unit,
    onResolveConflict: (
        InventoryFindingDto,
        String,
        String?,
        () -> Unit,
    ) -> Unit,
    onOpenInventoryFinding: (
        InventoryFindingDto,
        InventoryReinspectionMode,
        () -> Unit,
    ) -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoadInventory()
    }
    var number by remember(uiState.inventorySession?.id) { mutableStateOf("") }
    var selectedConflictFindingId by remember(uiState.inventorySession?.id) {
        mutableStateOf<String?>(null)
    }
    var filters by remember(uiState.inventorySession?.id) {
        mutableStateOf(InventoryHistoryFilters())
    }
    val prepareNumber: (String) -> Unit = { selectedNumber ->
        onPrepareNewNumber(
            selectedNumber,
            { finding ->
                number = ""
                onOpenInventoryFinding(
                    finding,
                    finding.inventoryReinspectionOpenMode(),
                    onOpenEditor,
                )
            },
            {
                number = ""
                onOpenEditor()
            },
        )
    }
    val openExistingNumber: (String) -> Unit = prepareNumber
    val addNewNumber: (String) -> Unit = prepareNumber
    val openFinding: (InventoryFindingDto) -> Unit = { finding ->
        onOpenInventoryFinding(
            finding,
            finding.inventoryReinspectionOpenMode(),
            onOpenEditor,
        )
    }
    val filteredFindings = remember(uiState.inventoryFindings, filters, number) {
        uiState.inventoryFindings
            .filter { finding -> finding.matchesInventoryNumberPrefix(number) }
            .filter(filters::matches)
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
                        description = INVENTORY_FIELD_ONLY_EMPTY_STATE_DESCRIPTION,
                    )
                }
            } else {
                item { InventorySessionSummary(session) }
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
                            title = when {
                                uiState.inventoryFindings.isEmpty() -> "Проверок пока нет"
                                number.isNotBlank() -> "По номеру ничего нет"
                                else -> "По выбранным фильтрам ничего нет"
                            },
                            description = when {
                                uiState.inventoryFindings.isEmpty() ->
                                    "Результаты появятся здесь после первой проверки номера."
                                number.isNotBlank() ->
                                    "Продолжайте ввод или добавьте бытовку, если номер отсутствует в базе."
                                else -> "Измените или сбросьте фильтры истории."
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
                onOpenInventoryFinding(
                    selectedConflict,
                    InventoryReinspectionMode.SUPPLEMENT,
                    onOpenEditor,
                )
            },
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
    val canAdd = value.isNotBlank() &&
        !hasInventoryRentalItemNumberPrefix(rentalItems, value)
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
        expanded = expanded && canAdd,
        onExpandedChange = { nextExpanded ->
            if (enabled) expanded = nextExpanded && canAdd
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
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && canAdd)
            },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    val exact = findExactInventoryRentalItem(rentalItems, value)
                    if (exact != null) {
                        chooseExisting(exact.number)
                    } else if (canAdd) {
                        addNew(value.trim())
                    }
                },
            ),
        )
        ExposedDropdownMenu(
            expanded = expanded && canAdd,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("+ Добавить бытовку «${value.trim()}»") },
                onClick = { addNew(value.trim()) },
                modifier = Modifier.heightIn(min = 56.dp),
            )
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
        Text("Активная сессия", style = MaterialTheme.typography.titleLarge)
        Text(
            "Проверено ${session.inspectedCount} из ${session.expectedCount}; найдено: ${session.findingCount}.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
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
            StatusPill(label = inventoryInspectionLabel(finding.inspection))
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
        inventoryAfterRentEstimateNotice(finding.inventoryBusinessStatus())?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            finding.comment.ifBlank { "Без комментария" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (checked) {
            Text(
                "Источник: ${finding.inventoryInspectionSourceLabel()}",
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
            Text(if (checked) "Открыть осмотр" else "Просмотреть")
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

/** Lets the manager retain or replace a saved inspection before local editing is enabled. */
@Composable
internal fun InventoryReinspectionDialog(
    finding: InventoryFindingDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSupplement: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(INVENTORY_REINSPECTION_DIALOG_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${finding.displayCanonicalNumber} уже имеет сохранённый осмотр.")
                Text(
                    "Дополнить сохранит прежние фото, мебель, работы, материалы и комментарий, " +
                        "чтобы их можно было уточнить.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Перезаписать начнёт новый осмотр: прежние данные этой проверки не попадут " +
                        "в новую ревизию. Потребуется заново добавить фотографию и подтвердить мебель.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSupplement,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(INVENTORY_REINSPECTION_SUPPLEMENT_LABEL)
                }
                OutlinedButton(
                    onClick = onReplace,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(INVENTORY_REINSPECTION_REPLACE_LABEL)
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Отмена")
                }
            }
        },
    )
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
    onRequestEdit: () -> Unit,
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
        mutableStateOf(!editor.readOnly && editor.isCreation && editor.creationOrigin == null)
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
                    editor.finding?.takeIf { it.inspection != "NOT_INSPECTED" }?.let { finding ->
                        Text(
                            "Источник: ${finding.inventoryInspectionSourceLabel()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inventoryAfterRentEstimateNotice(editor.finding?.inventoryBusinessStatus())?.let {
                        notice ->
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            if (!editor.readOnly) {
                                TextButton(onClick = { showOriginPrompt = true }) {
                                    Text("Изменить")
                                }
                            }
                        }
                    }
                }
            }
            item {
                PassportEditor(
                    editor = editor,
                    readOnly = editor.readOnly,
                    onEdit = onEdit,
                )
            }
            item {
                InventoryStepActions(
                    primaryLabel = if (editor.readOnly) "Далее" else "Добавить фотографии",
                    primaryEnabled = (editor.readOnly || editor.canInspect) && !busy,
                    onPrimary = onOpenPhotos,
                    readOnly = editor.readOnly,
                    onRequestEdit = onRequestEdit,
                )
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
    readOnly: Boolean,
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
            enabled = !readOnly && (!editor.isCreation || editor.creationOrigin == "ADDED_USED"),
        ) { value ->
            onEdit { it.copy(category = value) }
        }
        PassportDropdown(
            label = "Тип бытовки",
            value = editor.rentalType,
            options = editor.inventoryRentalTypeOptions(),
            enabled = !readOnly,
        ) { value ->
            onEdit { it.withInventoryRentalType(value) }
        }
        PassportDropdown(
            label = "Габариты",
            value = editor.dimensions,
            options = editor.inventoryDimensionOptions(),
            enabled = !readOnly && editor.rentalType.isNotBlank(),
        ) { value ->
            onEdit { it.copy(dimensions = value) }
        }
        PassportDropdown(
            label = "Отделка",
            value = editor.finishing,
            options = editor.inventoryFinishingOptions(),
            enabled = !readOnly,
        ) { value ->
            onEdit { it.copy(finishing = value) }
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
    enabled: Boolean,
    onEdit: ((InventoryEditorState) -> InventoryEditorState) -> Unit,
) {
    var expanded by remember(editor.findingId) { mutableStateOf(false) }
    val options = editor.inventoryCharacteristicOptions()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { nextExpanded ->
            if (enabled && options.isNotEmpty()) expanded = nextExpanded
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
                    enabled = enabled && options.isNotEmpty(),
                ),
            label = { Text("Характеристики") },
            placeholder = { Text("Выберите характеристики") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            readOnly = true,
            enabled = enabled && options.isNotEmpty(),
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
    enabled: Boolean,
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
            enabled = enabled && value > 0,
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
            enabled = enabled,
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
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        enabled = enabled,
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
    onFurnitureAbsent: () -> Unit,
    onAddFurniture: () -> Unit,
    onReviewContinue: () -> Unit,
    onRequestEdit: () -> Unit,
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
            // Import one app-owned, upright original for the UI and durable queue. The background
            // encoder derives its three WebP parts from these pixels without a server transform.
            copyManagerPhotoToAppCache(context, uri)?.let(onAddPhoto)
        }
    }
    var galleryPhotoUri by remember(editor.findingId) { mutableStateOf<String?>(null) }
    val photoError = editor.inventoryPhotoValidationError()
    val persistedPhotoCount = editor.persistedInventoryMediaReferences().size
    val unavailablePersistedPhotoCount = (
        persistedPhotoCount - editor.persistedPhotoMedia.values
            .distinctBy { reference -> reference.mediaId }
            .size
        ).coerceAtLeast(0)

    // Inventory uses the same photo step as estimates and repairs.  It is deliberately not
    // labelled as a separate "photo inventory" mode: a tap chooses the cover and a hold opens
    // the common fullscreen carousel below.
    ManagerScreenScaffold(title = INVENTORY_PHOTOS_TITLE, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Leave room for the fixed furniture actions. The last photograph can still be
                // scrolled fully above it instead of being covered by the footer.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 152.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ManagerPanel {
                        Text("Фото состояния", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (persistedPhotoCount > 0) {
                                if (unavailablePersistedPhotoCount > 0) {
                                    "Сохранённые фотографии: $persistedPhotoCount. Сейчас не загрузились: " +
                                        "$unavailablePersistedPhotoCount. Они останутся в осмотре; " +
                                        "проверьте связь и откройте проверку снова."
                                } else {
                                    "Сохранённые фотографии: $persistedPhotoCount. " +
                                        "Их можно просмотреть, удалить или выбрать титульной; " +
                                        "новые фото можно добавить при необходимости."
                                }
                            } else {
                                "Добавьте фото и выберите титульное."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = onOpenCamera,
                                enabled = !editor.readOnly && !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("Фотография") }
                            FilledTonalButton(
                                onClick = {
                                    galleryLauncher.launch(
                                        arrayOf("image/*", "video/mp4", "video/webm"),
                                    )
                                },
                                enabled = !editor.readOnly && !busy,
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
                                val video = isManagerVideoUri(uri)
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
                                                    if (video) {
                                                        galleryPhotoUri = uri
                                                    } else if (!editor.readOnly && !busy) {
                                                        onSelectCoverPhoto(uri)
                                                    }
                                                },
                                                onLongPress = { galleryPhotoUri = uri },
                                            )
                                            .semantics {
                                                role = Role.Button
                                                contentDescription = if (video) {
                                                    "Видеозапись; открыть просмотр"
                                                } else if (selected) {
                                                    "Титульная фотография"
                                                } else {
                                                    "Выбрать титульной фотографией; удерживайте для просмотра"
                                                }
                                                if (video) {
                                                    onClick(label = "Открыть видеозапись") {
                                                        galleryPhotoUri = uri
                                                        true
                                                    }
                                                } else if (!editor.readOnly && !busy) {
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
                                        )
                                    }
                                    if (!editor.readOnly) {
                                        TextButton(
                                            onClick = { onRemovePhoto(uri) },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("Удалить") }
                                    }
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
                }
            }
            val footerModifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp)
            if (editor.readOnly) {
                InventoryStepActions(
                    primaryLabel = "Далее",
                    primaryEnabled = !busy,
                    onPrimary = onReviewContinue,
                    readOnly = true,
                    onRequestEdit = onRequestEdit,
                    modifier = footerModifier,
                )
            } else {
                Column(
                    modifier = footerModifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onFurnitureAbsent,
                        enabled = photoError == null && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Мебели нет")
                    }
                    Button(
                        onClick = onAddFurniture,
                        enabled = photoError == null && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Добавить мебель")
                    }
                }
            }
        }
    }
    galleryPhotoUri?.let { initialPhoto ->
        val initialIndex = editor.photoUris.indexOf(initialPhoto).coerceAtLeast(0)
        ManagerPhotoGalleryDialog(
            title = "Фото состояния",
            photoUris = editor.photoUris,
            initialIndex = initialIndex,
            onRemovePhotoUri = onRemovePhoto.takeUnless { editor.readOnly },
            onDismiss = { galleryPhotoUri = null },
        )
    }
}

/** Keeps the inventory route and the camera route in the same ordinary photo flow. */
internal const val INVENTORY_PHOTOS_TITLE = "Фотографии"

@Composable
fun InventoryFurnitureScreen(
    editor: InventoryEditorState?,
    busy: Boolean,
    onBack: () -> Unit,
    onEdit: ((InventoryEditorState) -> InventoryEditorState) -> Unit,
    onContinue: () -> Unit,
    onRequestEdit: () -> Unit,
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
    val furnitureCatalog = editor.equipmentCatalog.inventoryFurnitureCatalog()
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
            if (furnitureCatalog.isEmpty()) {
                item {
                    EmptyState(
                        title = "Номенклатура не найдена",
                        description = "В панели нет активных позиций дополнительного оборудования.",
                    )
                }
            } else {
                items(
                    count = furnitureCatalog.size,
                    key = { furnitureCatalog[it].id },
                ) { index ->
                    val equipment = furnitureCatalog[index]
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
                                enabled = !editor.readOnly && !busy &&
                                    (currentText.toLongOrNull() ?: 0L) > 0L,
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
                                enabled = !editor.readOnly && !busy,
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
                                enabled = !editor.readOnly && !busy,
                            ) { Text("+") }
                        }
                    }
                }
            }
            item {
                validationError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                InventoryStepActions(
                    primaryLabel = "Продолжить",
                    primaryEnabled = (editor.readOnly || validationError == null) && !busy,
                    onPrimary = onContinue,
                    readOnly = editor.readOnly,
                    onRequestEdit = onRequestEdit,
                )
            }
        }
    }
}

@Composable
fun InventoryCatalogScreen(
    editor: InventoryEditorState?,
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onAddCatalogNodes: (
        List<CatalogNodeDto>,
        String,
        String,
        String?,
        List<String>,
        List<dev.buhanzaz.rwms.manager.network.MediaReferenceDto>,
    ) -> Boolean,
    onRefreshCatalog: () -> Unit,
    onEditPlan: ((dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState) ->
        dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState) -> Unit,
    onContinue: () -> Unit,
    onRequestEdit: () -> Unit,
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
            secondaryActionLabel = INVENTORY_REVIEW_EDIT_LABEL.takeIf { editor.readOnly },
            onSecondaryAction = onRequestEdit,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/** Collects late inspection observations after work selection and before final confirmation. */
@Composable
fun InventoryInspectionDetailsScreen(
    editor: InventoryEditorState?,
    busy: Boolean,
    onBack: () -> Unit,
    onEdit: ((InventoryEditorState) -> InventoryEditorState) -> Unit,
    onContinue: () -> Unit,
    onRequestEdit: () -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Детали проверки", onBack = onBack) { padding ->
            EmptyState(
                title = "Проверка не найдена",
                description = "Вернитесь к инвентаризации и откройте бытовку заново.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    val validationError = when {
        editor.isCreation && editor.linoleum == null -> "Укажите, есть ли линолеум"
        editor.comment.length > 2_000 -> "Комментарий не может быть длиннее 2000 символов"
        else -> null
    }
    ManagerScreenScaffold(title = "Детали проверки", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ManagerPanel {
                    InventoryCharacteristicsSelector(
                        editor = editor,
                        enabled = !editor.readOnly,
                        onEdit = onEdit,
                    )
                    Text("Линолеум", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = editor.linoleum == true,
                            onClick = { onEdit { it.copy(linoleum = true) } },
                            enabled = !editor.readOnly,
                            label = { Text("Есть") },
                        )
                        FilterChip(
                            selected = editor.linoleum == false,
                            onClick = { onEdit { it.copy(linoleum = false) } },
                            enabled = !editor.readOnly,
                            label = { Text("Нет") },
                        )
                        if (!editor.isCreation) {
                            FilterChip(
                                selected = editor.linoleum == null,
                                onClick = { onEdit { it.copy(linoleum = null) } },
                                enabled = !editor.readOnly,
                                label = { Text("Не указано") },
                            )
                        }
                    }
                    if (editor.isSanitary) {
                        Text("Настройки санблока", style = MaterialTheme.typography.titleSmall)
                        InventoryCounter(
                            label = "Туалеты",
                            value = editor.sanitaryToilets,
                            enabled = !editor.readOnly,
                        ) { value ->
                            onEdit { it.copy(sanitaryToilets = value) }
                        }
                        InventoryCounter(
                            label = "Раковины",
                            value = editor.sanitarySinks,
                            enabled = !editor.readOnly,
                        ) { value ->
                            onEdit { it.copy(sanitarySinks = value) }
                        }
                        InventoryCounter(
                            label = "Душевые",
                            value = editor.sanitaryShowers,
                            enabled = !editor.readOnly,
                        ) { value ->
                            onEdit { it.copy(sanitaryShowers = value) }
                        }
                    }
                    PassportTextField(
                        label = "Комментарий проверки",
                        value = editor.comment,
                        singleLine = false,
                        enabled = !editor.readOnly,
                    ) { value ->
                        onEdit { it.copy(comment = value.take(2_000)) }
                    }
                }
            }
            validationError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            item {
                InventoryStepActions(
                    primaryLabel = "Продолжить",
                    primaryEnabled = (editor.readOnly ||
                        (editor.canInspect && validationError == null)) && !busy,
                    onPrimary = onContinue,
                    readOnly = editor.readOnly,
                    onRequestEdit = onRequestEdit,
                )
            }
        }
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
    onCloseReview: () -> Unit,
    onRequestEdit: () -> Unit,
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
        editor.planLines.isEmpty() &&
            editor.planMovementToRepair ->
            "Передача в ремонт доступна, когда в проверке есть работы или материалы"
        editor.planLines.isEmpty() -> null
        !maintenanceCanAdvance(maintenancePlan, step = 3) ->
            "Проверьте количество, цену и маршрут работ и материалов"
        editor.planPriority !in 1..5 -> "Выберите приоритет от 1 до 5"
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
                        true -> "Комплектация мебели сохранится как доказательство осмотра"
                        false -> "Мебель отсутствует"
                        null -> "Не выбран вариант мебели"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                inventoryAfterRentEstimateNotice(editor.finding?.inventoryBusinessStatus())?.let { notice ->
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (editor.planLines.isNotEmpty()) {
                ManagerPanel {
                    InventoryRepairDeliveryOptions(
                        editor = maintenancePlan,
                        enabled = !editor.readOnly && !busy,
                        onEdit = onEditPlan,
                    )
                }
            }
            validationError?.takeUnless { editor.readOnly }?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            InventoryStepActions(
                primaryLabel = when {
                    editor.readOnly -> "Закрыть просмотр"
                    editor.isCreation -> "Подтвердить добавление"
                    else -> "Подтвердить проверку"
                },
                primaryEnabled = (editor.readOnly || validationError == null) && !busy,
                onPrimary = {
                    if (editor.readOnly) onCloseReview() else onSave(onBack)
                },
                readOnly = editor.readOnly,
                onRequestEdit = onRequestEdit,
            )
        }
    }
}

@Composable
private fun InventoryRepairDeliveryOptions(
    editor: dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState,
    enabled: Boolean,
    onEdit: ((dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState) ->
        dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState) -> Unit,
) {
    Text("Результат проверки", style = MaterialTheme.typography.titleMedium)
    Text(
        "Выберите приоритет для будущей сверки в панели.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Row(
        modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (1..5).forEach { priority ->
            FilterChip(
                selected = editor.priority == priority,
                onClick = { onEdit { current -> current.copy(priority = priority) } },
                enabled = enabled,
                label = { Text("Приоритет $priority") },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = editor.movementToRepair,
            onCheckedChange = { deliverToRepair ->
                onEdit { current -> current.withMovementToRepair(deliverToRepair) }
            },
            enabled = enabled,
        )
        Text(
            "Добавить перемещение на ремонт",
            modifier = Modifier.weight(1f),
        )
    }
    ForceCapitalRepairOption(
        editor = editor,
        enabled = enabled,
        onEdit = onEdit,
    )
    Text(
        "Телефон сохраняет только проверку бытовки. Задача ремонта или перемещения " +
            "из этой проверки не создаётся.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

internal const val INVENTORY_PHOTO_PREVIEW_HOLD_MILLIS = 1_500L

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
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
