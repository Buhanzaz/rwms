package dev.buhanzaz.rwms.manager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.ReworkCandidateDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorMode
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState
import dev.buhanzaz.rwms.manager.ui.MaintenanceLineEditorState
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.catalogDisplayColorArgb
import dev.buhanzaz.rwms.manager.ui.catalogDisplayColorNeedsLightContent
import dev.buhanzaz.rwms.manager.ui.isEmptyMaintenanceEstimate
import dev.buhanzaz.rwms.manager.ui.isEmptyMaintenanceOutcome
import dev.buhanzaz.rwms.manager.ui.maintenanceDocumentAlreadySubmitted
import dev.buhanzaz.rwms.manager.ui.maintenanceWorkRoutingOptions
import dev.buhanzaz.rwms.manager.ui.maintenanceHasCoverPhoto
import dev.buhanzaz.rwms.manager.ui.maintenanceHasPhotos
import dev.buhanzaz.rwms.manager.ui.maintenanceRequiresPhotos
import dev.buhanzaz.rwms.manager.ui.maintenanceLocalPhotoKey
import dev.buhanzaz.rwms.manager.ui.maintenanceReadyPhotoKey
import dev.buhanzaz.rwms.manager.ui.removeMaintenanceLocalPhoto
import dev.buhanzaz.rwms.manager.ui.routingLabel
import dev.buhanzaz.rwms.manager.ui.logisticsPlanningValidationError
import dev.buhanzaz.rwms.manager.ui.logisticsTaskPriorityValidationError
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerMenuCard
import dev.buhanzaz.rwms.manager.ui.components.ManagerPanel
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoGalleryDialog
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoCaptureScreen
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoPreview
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold
import dev.buhanzaz.rwms.manager.ui.components.StatusPill
import dev.buhanzaz.rwms.manager.ui.components.StatusPillEmphasis
import dev.buhanzaz.rwms.manager.ui.components.copyManagerPhotoToAppCache
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAINTENANCE_STEP_COUNT = 5
internal const val MAINTENANCE_CATALOG_PAGE_SIZE = 9
internal const val MAINTENANCE_CATALOG_LABEL_HOLD_MILLIS = 2_000L

internal enum class MaintenanceSearchFeedbackPlacement {
    AboveField,
    BelowField,
}

internal fun maintenanceSearchFeedbackPlacement(
    imeVisible: Boolean,
): MaintenanceSearchFeedbackPlacement = if (imeVisible) {
    MaintenanceSearchFeedbackPlacement.AboveField
} else {
    MaintenanceSearchFeedbackPlacement.BelowField
}

internal fun maintenanceCatalogPageCount(itemCount: Int): Int =
    ((itemCount.coerceAtLeast(0) + MAINTENANCE_CATALOG_PAGE_SIZE - 1) /
        MAINTENANCE_CATALOG_PAGE_SIZE).coerceAtLeast(1)

internal fun <T> maintenanceCatalogPage(items: List<T>, page: Int): List<T> {
    val normalizedPage = page.coerceIn(0, maintenanceCatalogPageCount(items.size) - 1)
    val fromIndex = normalizedPage * MAINTENANCE_CATALOG_PAGE_SIZE
    return items.subList(
        fromIndex = fromIndex.coerceAtMost(items.size),
        toIndex = (fromIndex + MAINTENANCE_CATALOG_PAGE_SIZE).coerceAtMost(items.size),
    )
}

@Composable
fun MaintenanceMenuScreen(
    onBack: () -> Unit,
    onOpenEstimates: () -> Unit,
    onOpenRepairs: () -> Unit,
    onOpenRepairQueue: () -> Unit,
    onOpenAcceptance: () -> Unit,
) {
    ManagerScreenScaffold(title = "Ремонтный цикл", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Выберите этап ремонтного цикла.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ManagerMenuCard(
                    title = "Сметы",
                    description = "Черновики, завершённые сметы и создание ремонта",
                    accent = androidx.compose.ui.graphics.Color(0xFF7C3AED),
                    onClick = onOpenEstimates,
                )
            }
            item {
                ManagerMenuCard(
                    title = "Ремонты",
                    description = "Таблица прямых ремонтов и работ из смет",
                    accent = androidx.compose.ui.graphics.Color(0xFFEA580C),
                    onClick = onOpenRepairs,
                )
            }
            item {
                ManagerMenuCard(
                    title = "Очередь ремонтов",
                    description = "Задания по датам и изменение порядка",
                    accent = androidx.compose.ui.graphics.Color(0xFF0284C7),
                    onClick = onOpenRepairQueue,
                )
            }
            item {
                ManagerMenuCard(
                    title = "Приёмка",
                    description = "Завершённые ремонты, решения и доработки",
                    accent = androidx.compose.ui.graphics.Color(0xFF16A34A),
                    onClick = onOpenAcceptance,
                )
            }
        }
    }
}

@Composable
fun EstimatesListScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoadMaintenance: () -> Unit,
    onStartEstimate: () -> Unit,
    onOpenEstimate: (String) -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoadMaintenance()
    }
    ManagerScreenScaffold(title = "Сметы", onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (uiState.estimates.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Смет пока нет",
                            description = "Создайте первую смету для бытовки выбранного склада.",
                        )
                    }
                } else {
                    items(uiState.estimates, key = EstimateDto::id) { estimate ->
                        EstimateCard(
                            estimate = estimate,
                            assetLabel = uiState.maintenanceAssetLabels[estimate.rentalItemId],
                            enabled = !uiState.busy,
                            onOpen = { onOpenEstimate(estimate.id) },
                        )
                    }
                }
            }
            Button(
                onClick = onStartEstimate,
                enabled = !uiState.busy,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Новая смета")
            }
        }
    }
}

@Composable
fun RepairsListScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoadMaintenance: () -> Unit,
    onStartRepair: () -> Unit,
    onOpenRepair: (String) -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoadMaintenance()
    }
    ManagerScreenScaffold(title = "Ремонты", onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (uiState.repairs.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Ремонтов пока нет",
                            description = "Создайте прямой ремонт или завершите смету.",
                        )
                    }
                } else {
                    items(uiState.repairs, key = RepairDto::id) { repair ->
                        RepairCard(
                            repair = repair,
                            assetLabel = uiState.maintenanceAssetLabels[repair.rentalItemId],
                            enabled = !uiState.busy,
                            onOpen = { onOpenRepair(repair.id) },
                        )
                    }
                }
            }
            Button(
                onClick = onStartRepair,
                enabled = !uiState.busy,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Новый ремонт")
            }
        }
    }
}

@Composable
fun RepairQueueScreen(
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onLoadQueue: () -> Unit,
    onOpenRepair: (String) -> Unit,
    onMoveQueueEntry: (dev.buhanzaz.rwms.manager.ui.RepairQueueItem, String, Int) -> Unit,
    onSwapQueueDateColumns: (String, String, () -> Unit) -> Unit,
) {
    LaunchedEffect(uiState.selectedWarehouseId) {
        if (uiState.selectedWarehouseId != null) onLoadQueue()
    }
    ManagerScreenScaffold(title = "Очередь ремонтов", onBack = onBack) { padding ->
        RepairQueueView(
            boards = uiState.repairTaskBoards,
            repairs = uiState.repairs,
            assetLabels = uiState.maintenanceAssetLabels,
            busy = uiState.busy,
            onMove = onMoveQueueEntry,
            onSwapDateColumns = onSwapQueueDateColumns,
            onOpenRepair = onOpenRepair,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
fun MaintenanceEditorScreen(
    editor: MaintenanceEditorState?,
    uiState: ManagerUiState,
    onBack: () -> Unit,
    onUpdateAssetSearch: (String) -> Unit,
    onSearchAssets: () -> Unit,
    onSelectAsset: (RentalItemDto) -> Unit,
    onAddCatalogNodes: (
        List<CatalogNodeDto>,
        String,
        String,
        String?,
        List<String>,
        List<MediaReferenceDto>,
    ) -> Boolean,
    onToggleReworkCandidate: (ReworkCandidateDto) -> Unit,
    onRefreshCatalog: () -> Unit,
    onEdit: ((MaintenanceEditorState) -> MaintenanceEditorState) -> Unit,
    onOpenPhotos: () -> Unit,
    onAddPhoto: (String) -> Unit,
    onOpenFurniture: () -> Unit,
    onSelectCoverPhoto: (String) -> Unit,
    onSaveDraft: (() -> Unit) -> Unit,
    onSubmit: (() -> Unit) -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Смета и ремонт", onBack = onBack) { padding ->
            EmptyState(
                title = "Редактор не найден",
                description = "Вернитесь к списку и откройте смету или ремонт ещё раз.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    var readOnlyStep by remember(editor.entityId, editor.mode, editor.readOnly) {
        mutableIntStateOf(maintenanceWizardStep(editor.step))
    }
    val step = if (editor.readOnly) readOnlyStep else maintenanceWizardStep(editor.step)
    val canSubmit = maintenanceCanSubmit(editor)
    val moveToStep: (Int) -> Unit = { targetStep ->
        val normalizedStep = maintenanceWizardStep(targetStep)
        if (editor.readOnly) {
            readOnlyStep = normalizedStep
        } else {
            onEdit { current -> current.copy(step = normalizedStep) }
        }
    }
    val navigateBack = {
        when (step) {
            1 -> onBack()
            5 -> moveToStep(3)
            else -> moveToStep(step - 1)
        }
    }

    BackHandler(onBack = navigateBack)
    ManagerScreenScaffold(
        title = maintenanceWizardTitle(step),
        onBack = navigateBack,
    ) { padding ->
        when (step) {
            1 -> MaintenanceDetailsStep(
                editor = editor,
                uiState = uiState,
                onUpdateAssetSearch = onUpdateAssetSearch,
                onSearchAssets = onSearchAssets,
                onSelectAsset = onSelectAsset,
                onEdit = onEdit,
                onContinue = { moveToStep(2) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            2 -> MaintenancePhotosStep(
                editor = editor,
                onOpenPhotos = onOpenPhotos,
                onAddPhoto = onAddPhoto,
                onSelectCover = onSelectCoverPhoto,
                onRemovePhoto = { key ->
                    when {
                        key.startsWith("local:") -> {
                            editor.photoUris
                                .firstOrNull { maintenanceLocalPhotoKey(it) == key }
                                ?.let { uri ->
                                    onEdit { current -> removeMaintenanceLocalPhoto(current, uri) }
                                }
                        }

                        key.startsWith("media:") -> {
                            val mediaId = editor.readyMedia
                                .firstOrNull { maintenanceReadyPhotoKey(it.mediaId) == key }
                                ?.mediaId
                            if (mediaId != null) {
                                onEdit { current ->
                                    current.copy(
                                        readyMedia = current.readyMedia.filterNot {
                                            it.mediaId == mediaId
                                        },
                                        readyPhotoUris = current.readyPhotoUris - mediaId,
                                        coverPhotoKey = current.coverPhotoKey.takeUnless {
                                            it == key
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                onContinue = {
                    moveToStep(maintenancePhotoStepPolicy(editor).targetStep)
                },
                busy = uiState.busy,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            3 -> MaintenanceCatalogStep(
                editor = editor,
                uiState = uiState,
                onAddCatalogNodes = onAddCatalogNodes,
                onToggleReworkCandidate = onToggleReworkCandidate,
                onRefreshCatalog = onRefreshCatalog,
                onEdit = onEdit,
                onContinue = onOpenFurniture,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> MaintenanceReviewStep(
                editor = editor,
                onEdit = onEdit,
                canSubmit = canSubmit,
                busy = uiState.busy,
                onSaveDraft = onSaveDraft,
                onSubmit = onSubmit,
                onDone = onBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

internal fun maintenanceWizardStep(step: Int): Int =
    if (step.coerceIn(1, MAINTENANCE_STEP_COUNT) == 4) 3 else {
        step.coerceIn(1, MAINTENANCE_STEP_COUNT)
    }

@Composable
private fun EstimateCard(
    estimate: EstimateDto,
    assetLabel: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    ManagerPanel(onClick = if (enabled) onOpen else null) {
        RowWithPill(
            title = "Смета",
            pill = estimateLifecycleLabel(estimate.lifecycle),
        )
        Text("Бытовка: ${assetLabel ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        Text("Редакция: ${estimate.currentRevision}", style = MaterialTheme.typography.bodyMedium)
        estimate.repairId?.let { Text("Связана с ремонтом", style = MaterialTheme.typography.bodySmall) }
        Text(
            "Создана: ${estimate.createdAt}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RepairCard(
    repair: RepairDto,
    assetLabel: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    ManagerPanel(onClick = if (enabled) onOpen else null) {
        RowWithPill(
            title = "Ремонт",
            pill = repairExecutionStateLabel(repair.executionState),
        )
        Text("Бытовка: ${assetLabel ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        Text("Тип: ${repair.kind} · приоритет: ${repair.priority}")
        Text("Приёмка: ${repair.acceptanceState}")
        Text(
            "Обновлён: ${repair.updatedAt}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MaintenanceDetailsStep(
    editor: MaintenanceEditorState,
    uiState: ManagerUiState,
    onUpdateAssetSearch: (String) -> Unit,
    onSearchAssets: () -> Unit,
    onSelectAsset: (RentalItemDto) -> Unit,
    onEdit: ((MaintenanceEditorState) -> MaintenanceEditorState) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ManagerPanel {
                Text("Бытовка", style = MaterialTheme.typography.titleMedium)
                if (editor.selectedAsset == null) {
                    Text(
                        if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                            "Выберите бытовку после возврата из текущего склада."
                        } else {
                            "Выберите бытовку текущего склада для прямого ремонта."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SelectedMaintenanceAsset(asset = editor.selectedAsset)
                }
                if (!editor.readOnly &&
                    editor.entityId == null &&
                    editor.repairKind != "REWORK"
                ) {
                    MaintenanceAssetCombobox(
                        mode = editor.mode,
                        search = uiState.assetSearch,
                        assets = uiState.assetSearchResults,
                        searching = uiState.assetSearchBusy,
                        completedSearchQuery = uiState.assetSearchCompletedQuery,
                        failedSearchQuery = uiState.assetSearchFailedQuery,
                        enabled = !uiState.busy,
                        onSearchChange = onUpdateAssetSearch,
                        onSearch = onSearchAssets,
                        onAdd = onSelectAsset,
                    )
                } else if (!editor.readOnly) {
                    Text(
                        "Бытовка закреплена за сохранённым документом.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (editor.repairKind == "REWORK") {
            item {
                ManagerPanel {
                    Text("Доработка", style = MaterialTheme.typography.titleMedium)
                    if (editor.entityId == null) {
                        OutlinedTextField(
                            value = editor.reworkReason,
                            onValueChange = { value ->
                                onEdit { current ->
                                    current.copy(reworkReason = value.take(2_000))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Причина доработки") },
                            minLines = 2,
                            maxLines = 4,
                            readOnly = editor.readOnly,
                        )
                    } else {
                        Text(
                            "Причина сохранена в maintenance-service.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    editor.sourceRepairId?.let { sourceId ->
                        Text(
                            "Исходный ремонт выбран",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
            item {
                ManagerPanel {
                    Text("Данные осмотра", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = editor.dispatchDate,
                        onValueChange = { value ->
                            onEdit { current -> current.copy(dispatchDate = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Дата осмотра (ГГГГ-ММ-ДД)") },
                        singleLine = true,
                        readOnly = editor.readOnly,
                    )
                    OutlinedTextField(
                        value = editor.sourceParty,
                        onValueChange = { value ->
                            onEdit { current -> current.copy(sourceParty = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Контрагент") },
                        singleLine = true,
                        readOnly = editor.readOnly,
                    )
                    Text(
                        "Дата и контрагент подставляются из последнего возврата; их можно уточнить перед сохранением сметы.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            ManagerPanel {
                LogisticsTaskPriorityOptions(
                    editor = editor,
                    enabled = !editor.readOnly &&
                        !maintenanceDocumentAlreadySubmitted(editor) &&
                        !uiState.busy,
                    onEdit = onEdit,
                )
            }
        }
        if (!maintenanceDetailsAreValid(editor)) {
            item {
                Text(
                    maintenanceDetailsValidationMessage(editor),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (editor.selectedAsset != null) {
            item {
                Button(
                    onClick = onContinue,
                    enabled = maintenanceDetailsAreValid(editor) && !uiState.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Далее") }
            }
        }
    }
}

@Composable
private fun SelectedMaintenanceAsset(asset: RentalItemDto) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(asset.number, style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(asset.rentalType, asset.dimensions, asset.category).joinToString(" · ")
                    .ifBlank { "Паспорт не заполнен" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(label = asset.status)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaintenanceAssetCombobox(
    mode: MaintenanceEditorMode,
    search: String,
    assets: List<RentalItemDto>,
    searching: Boolean,
    completedSearchQuery: String?,
    failedSearchQuery: String?,
    enabled: Boolean,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAdd: (RentalItemDto) -> Unit,
) {
    var expanded by remember(mode) { mutableStateOf(false) }
    val normalizedSearch = search.trim()
    val visibleAssets = remember(assets, search) {
        if (search.isBlank()) {
            assets
        } else {
            assets.filter { asset -> asset.number.contains(search, ignoreCase = true) }
        }
    }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val feedbackPlacement = maintenanceSearchFeedbackPlacement(imeVisible)
    val feedback = when {
        !expanded -> null
        searching -> MaintenanceSearchFeedback(
            message = "Ищем бытовки…",
            warning = false,
        )

        failedSearchQuery == normalizedSearch -> MaintenanceSearchFeedback(
            message = "Не удалось выполнить поиск бытовок.",
            warning = true,
        )

        completedSearchQuery != normalizedSearch -> MaintenanceSearchFeedback(
            message = "Ищем бытовки…",
            warning = false,
        )

        visibleAssets.isEmpty() -> MaintenanceSearchFeedback(
            message = if (search.isBlank()) {
                "Нет доступных бытовок на выбранном складе."
            } else {
                "По этому номеру подходящих бытовок нет."
            },
            warning = true,
        )

        else -> null
    }

    LaunchedEffect(search, expanded) {
        if (!expanded || searching || !enabled) return@LaunchedEffect
        delay(if (search.isBlank()) 80 else 280)
        onSearch()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (feedback != null &&
            feedbackPlacement == MaintenanceSearchFeedbackPlacement.AboveField
        ) {
            MaintenanceAssetSearchFeedback(feedback)
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { nextExpanded ->
                if (enabled) expanded = nextExpanded
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { value ->
                    expanded = true
                    onSearchChange(value)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(
                        type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                        enabled = enabled,
                    )
                    .onFocusChanged { focus ->
                        if (focus.isFocused && enabled) expanded = true
                    },
                label = { Text("Номер бытовки") },
                supportingText = {
                    Text(
                        if (mode == MaintenanceEditorMode.ESTIMATE) {
                            "Доступны только бытовки после возврата."
                        } else {
                            "Арендованные и ожидающие осмотра бытовки исключены."
                        },
                    )
                },
                singleLine = true,
                enabled = enabled,
            )
            ExposedDropdownMenu(
                expanded = expanded && feedback == null && visibleAssets.isNotEmpty(),
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 288.dp),
            ) {
                visibleAssets.forEach { asset ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                asset.number,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onAdd(asset)
                        },
                    )
                }
            }
        }
        if (feedback != null &&
            feedbackPlacement == MaintenanceSearchFeedbackPlacement.BelowField
        ) {
            MaintenanceAssetSearchFeedback(feedback)
        }
    }
}

private data class MaintenanceSearchFeedback(
    val message: String,
    val warning: Boolean,
)

@Composable
private fun MaintenanceAssetSearchFeedback(feedback: MaintenanceSearchFeedback) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (feedback.warning) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        },
        contentColor = if (feedback.warning) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = feedback.message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun MaintenanceCatalogStep(
    editor: MaintenanceEditorState,
    uiState: ManagerUiState,
    onAddCatalogNodes: (
        List<CatalogNodeDto>,
        String,
        String,
        String?,
        List<String>,
        List<MediaReferenceDto>,
    ) -> Boolean,
    onToggleReworkCandidate: (ReworkCandidateDto) -> Unit = {},
    onRefreshCatalog: () -> Unit,
    onEdit: ((MaintenanceEditorState) -> MaintenanceEditorState) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember(editor.entityId, editor.mode) {
        mutableStateOf(MaintenanceCatalogMode.LINKED_SET)
    }
    var path by remember(editor.entityId, editor.mode) { mutableStateOf(emptyList<String>()) }
    var pendingWorkId by remember(editor.entityId, editor.mode) { mutableStateOf<String?>(null) }
    var pendingMaterialId by remember(editor.entityId, editor.mode) { mutableStateOf<String?>(null) }
    var page by remember(editor.entityId, editor.mode) { mutableIntStateOf(0) }
    var addContext by remember(editor.entityId, editor.mode) {
        mutableStateOf<MaintenanceCatalogAddContext?>(null)
    }
    var existingWorkContext by remember(editor.entityId, editor.mode) {
        mutableStateOf<MaintenanceCatalogExistingWorkContext?>(null)
    }
    var catalogMessage by remember(editor.entityId, editor.mode) { mutableStateOf<String?>(null) }
    var expandedCatalogLabel by remember(editor.entityId, editor.mode) {
        mutableStateOf<String?>(null)
    }
    var editingLineId by remember(editor.entityId, editor.mode) { mutableStateOf<String?>(null) }
    var customLineVisible by remember(editor.entityId, editor.mode) { mutableStateOf(false) }
    val catalogNodes = uiState.maintenanceCatalogNodes
    val catalog = remember(catalogNodes, uiState.maintenanceCatalogLinks) {
        maintenanceCatalogIndex(catalogNodes, uiState.maintenanceCatalogLinks)
    }
    val excludeFurniture = editor.mode == MaintenanceEditorMode.REPAIR
    val visibleNodes = remember(
        catalog,
        mode,
        path,
        pendingWorkId,
        pendingMaterialId,
        excludeFurniture,
    ) {
        maintenanceCatalogVisibleNodes(
            catalog = catalog,
            mode = mode,
            path = path,
            pendingWorkId = pendingWorkId,
            pendingMaterialId = pendingMaterialId,
            excludeFurniture = excludeFurniture,
        )
    }
    val pageCount = maintenanceCatalogPageCount(visibleNodes.size)
    val currentPage = page.coerceIn(0, pageCount - 1)
    val pagedVisibleNodes = remember(visibleNodes, currentPage) {
        maintenanceCatalogPage(visibleNodes, currentPage)
    }
    val canGoToPreviousPage = currentPage > 0
    val canGoToNextPage = currentPage < pageCount - 1
    val canContinue = editor.readOnly || maintenanceCanAdvance(editor, step = 3)

    LaunchedEffect(pageCount) {
        if (page != currentPage) page = currentPage
    }

    fun resetNavigation(nextMode: MaintenanceCatalogMode = mode) {
        mode = nextMode
        path = emptyList()
        pendingWorkId = null
        pendingMaterialId = null
        page = 0
        catalogMessage = null
    }

    fun openAdd(
        nodes: List<CatalogNodeDto>,
        quantityNode: CatalogNodeDto,
    ) {
        if (nodes.none(CatalogNodeDto::isOperationalMaintenanceCatalogNode)) {
            catalogMessage = "Эта позиция не настроена для добавления в смету или ремонт."
            return
        }
        addContext = MaintenanceCatalogAddContext(
            nodes = nodes.distinctBy(CatalogNodeDto::id),
            quantityNode = quantityNode,
        )
    }

    fun completeAdd(
        context: MaintenanceCatalogAddContext,
        quantity: String,
        comment: String,
        existingWorkLineId: String?,
        photoUris: List<String>,
        mediaReferences: List<MediaReferenceDto>,
    ) {
        if (!onAddCatalogNodes(
                context.nodes,
                quantity,
                comment,
                existingWorkLineId,
                photoUris,
                mediaReferences,
            )
        ) return
        addContext = null
        existingWorkContext = null
        resetNavigation()
        catalogMessage = "Позиция добавлена в выбранные строки."
    }

    fun navigateBackInsideCatalog() {
        when {
            pendingMaterialId != null -> pendingMaterialId = null
            pendingWorkId != null -> pendingWorkId = null
            path.isNotEmpty() -> path = path.dropLast(1)
        }
        page = 0
        catalogMessage = null
    }

    fun nodeHasMenu(node: CatalogNodeDto): Boolean = maintenanceCatalogNodesForUsage(
        nodes = maintenanceCatalogNavigationNodes(catalog, node.id),
        catalog = catalog,
        excludeFurniture = excludeFurniture,
    ).isNotEmpty()

    fun selectNode(node: CatalogNodeDto) {
        page = 0
        when {
            node.nodeType in maintenanceCatalogContainerTypes -> {
                path = path + node.id
                pendingWorkId = null
                pendingMaterialId = null
                catalogMessage = null
            }

            node.nodeType == "OPTION" && nodeHasMenu(node) -> {
                path = path + node.id
                pendingWorkId = null
                pendingMaterialId = null
                catalogMessage = null
            }

            node.nodeType == "OPTION" && !node.includeInEstimate -> {
                catalogMessage = "Эта опция служит для навигации и не добавляется в документ."
            }

            node.nodeType == "LOCATION" -> {
                val pendingWork = pendingWorkId?.let(catalog.nodesById::get)
                val pendingMaterial = pendingMaterialId?.let(catalog.nodesById::get)
                if (pendingWork == null || pendingMaterial == null) {
                    catalogMessage = "Расположение выбирается после связанного материала."
                } else {
                    openAdd(
                        nodes = listOf(pendingWork, pendingMaterial),
                        quantityNode = pendingMaterial,
                    )
                }
            }

            node.nodeType == "WORK" -> {
                val materials = maintenanceCatalogNodesForUsage(
                    catalog.dependencyRelatedNodesOf(node.id),
                    catalog,
                    excludeFurniture,
                ).filter { related -> related.nodeType == "MATERIAL" }
                when {
                    mode == MaintenanceCatalogMode.MATERIALS_ONLY && materials.isEmpty() -> {
                        catalogMessage = "Для этой работы не настроены материалы."
                    }

                    mode == MaintenanceCatalogMode.MATERIALS_ONLY ||
                        (mode == MaintenanceCatalogMode.LINKED_SET && materials.isNotEmpty()) -> {
                        pendingWorkId = node.id
                        pendingMaterialId = null
                        catalogMessage = null
                    }

                    else -> openAdd(
                        nodes = listOf(node),
                        quantityNode = node,
                    )
                }
            }

            node.nodeType == "MATERIAL" || node.nodeType == "OPTION" -> {
                val pendingWork = pendingWorkId?.let(catalog.nodesById::get)
                if (pendingWork != null && mode == MaintenanceCatalogMode.LINKED_SET) {
                    val locations = maintenanceCatalogNodesForUsage(
                        nodes = maintenanceCatalogNavigationNodes(catalog, node.id),
                        catalog = catalog,
                        excludeFurniture = excludeFurniture,
                    ).filter { candidate -> candidate.nodeType == "LOCATION" }
                    if (locations.isNotEmpty()) {
                        pendingMaterialId = node.id
                        catalogMessage = null
                    } else {
                        openAdd(
                            nodes = listOf(pendingWork, node),
                            quantityNode = node,
                        )
                    }
                } else {
                    val linkedWorks = if (mode == MaintenanceCatalogMode.LINKED_SET) {
                        maintenanceCatalogNodesForUsage(
                            catalog.dependencyRelatedNodesOf(node.id),
                            catalog,
                            excludeFurniture,
                        ).filter { related -> related.nodeType == "WORK" }
                    } else {
                        emptyList()
                    }
                    openAdd(
                        nodes = linkedWorks + node,
                        quantityNode = node,
                    )
                }
            }

            else -> catalogMessage = "Элемент нельзя добавить в документ."
        }
    }

    if (customLineVisible) {
        CustomMaintenanceLineSheet(
            editor = editor,
            catalogNodes = catalogNodes,
            catalogLinks = uiState.maintenanceCatalogLinks,
            liveRoutings = uiState.repairTaskBoards.maintenanceWorkRoutingOptions(),
            availablePhotos = editor.readyMedia
                .filterNot { reference ->
                    editor.lines.any { line ->
                        line.mediaReferences.any { it.mediaId == reference.mediaId }
                    }
                }
                .mapNotNull { reference ->
                    editor.readyPhotoUris[reference.mediaId]?.let { uri ->
                        MaintenanceWorkPhotoOption(reference, uri)
                    }
                },
            onDismiss = { customLineVisible = false },
            onAdd = { line ->
                onEdit { current ->
                    val lineMediaIds = line.mediaReferences.map(MediaReferenceDto::mediaId).toSet()
                    current.copy(
                        lines = current.lines + line,
                        readyMedia = current.readyMedia.filterNot { reference ->
                            reference.mediaId in lineMediaIds
                        },
                        coverPhotoKey = current.coverPhotoKey.takeUnless { key ->
                            key?.removePrefix("media:") in lineMediaIds
                        },
                    )
                }
                customLineVisible = false
            },
        )
    }
    editor.lines.firstOrNull { line -> line.id == editingLineId }?.let { line ->
        val occupiedMediaIds = editor.lines
            .asSequence()
            .filter { candidate -> candidate.id != line.id }
            .flatMap { candidate -> candidate.mediaReferences.asSequence() }
            .map(MediaReferenceDto::mediaId)
            .toSet()
        MaintenanceLineEditSheet(
            line = line,
            availablePhotos = (
                editor.readyMedia.filter { reference -> reference.mediaId !in occupiedMediaIds } +
                    line.mediaReferences
                )
                .distinctBy(MediaReferenceDto::mediaId)
                .mapNotNull { reference ->
                    editor.readyPhotoUris[reference.mediaId]?.let { uri ->
                        MaintenanceWorkPhotoOption(reference, uri)
                    }
                },
            onDismiss = { editingLineId = null },
            onSave = { updated ->
                onEdit { current -> current.replaceMaintenanceLine(line.id) { updated } }
                editingLineId = null
            },
        )
    }
    expandedCatalogLabel?.let { label ->
        MaintenanceCatalogLabelDialog(
            label = label,
            onDismiss = { expandedCatalogLabel = null },
        )
    }
    addContext?.let { context ->
        MaintenanceCatalogAddSheet(
            context = context,
            availablePhotos = editor.readyMedia
                .filterNot { reference ->
                    editor.lines.any { line ->
                        line.mediaReferences.any { it.mediaId == reference.mediaId }
                    }
                }
                .mapNotNull { reference ->
                    editor.readyPhotoUris[reference.mediaId]?.let { uri ->
                        MaintenanceWorkPhotoOption(reference, uri)
                    }
                },
            onDismiss = { addContext = null },
            onConfirm = { quantity, comment, photoUris, mediaReferences ->
                val existingWorks = maintenanceCatalogExistingWorkCandidates(
                    editor = editor,
                    nodes = context.nodes,
                )
                if (existingWorks.isEmpty()) {
                    completeAdd(
                        context,
                        quantity,
                        comment,
                        existingWorkLineId = null,
                        photoUris = photoUris,
                        mediaReferences = mediaReferences,
                    )
                } else {
                    addContext = null
                    existingWorkContext = MaintenanceCatalogExistingWorkContext(
                        addContext = context,
                        quantity = quantity,
                        comment = comment,
                        photoUris = photoUris,
                        mediaReferences = mediaReferences,
                        candidates = existingWorks,
                    )
                }
            },
        )
    }
    existingWorkContext?.let { context ->
        MaintenanceCatalogExistingWorkDialog(
            context = context,
            onDismiss = { existingWorkContext = null },
            onChooseExisting = { lineId ->
                completeAdd(
                    context.addContext,
                    context.quantity,
                    context.comment,
                    existingWorkLineId = lineId,
                    photoUris = context.photoUris,
                    mediaReferences = context.mediaReferences,
                )
            },
            onCreateNew = {
                completeAdd(
                    context.addContext,
                    context.quantity,
                    context.comment,
                    existingWorkLineId = null,
                    photoUris = context.photoUris,
                    mediaReferences = context.mediaReferences,
                )
            },
        )
    }

    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        MaintenanceCatalogModeSelector(
            mode = mode,
            enabled = !editor.readOnly && !uiState.busy,
            onSelect = { selectedMode -> resetNavigation(selectedMode) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Only this middle area scrolls. The catalog controls stay fixed at the bottom.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            if (uiState.maintenanceCatalogRefreshAvailable) {
                item {
                    Text(
                        "Активный каталог обновлён. Обновите список явно.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = onRefreshCatalog,
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Обновить каталог") }
                }
            }
            catalogMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!isEmptyMaintenanceOutcome(editor) && !canContinue) {
                item {
                    Text(
                        "Проверьте количество, цену и маршрут выбранных строк.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (editor.repairKind == "REWORK" && editor.reworkCandidates.isNotEmpty()) {
                item {
                    Text(
                        "Уже выполненные позиции",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(
                    editor.reworkCandidates,
                    key = ReworkCandidateDto::lineageRootLineId,
                ) { candidate ->
                    val selected = editor.lines.any { line ->
                        line.reworkDisposition == "REPEAT" &&
                            line.lineageRootLineId == candidate.lineageRootLineId
                    }
                    ReworkCandidateRow(
                        candidate = candidate,
                        selected = selected,
                        enabled = !editor.readOnly && !uiState.busy,
                        onToggle = { onToggleReworkCandidate(candidate) },
                    )
                }
                item {
                    Text(
                        "Новые позиции",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            items(editor.lines, key = MaintenanceLineEditorState::id) { line ->
                CompactMaintenanceLineRow(
                    line = line,
                    readOnly = editor.readOnly || uiState.busy,
                    onEdit = { editingLineId = line.id },
                    onRemove = {
                        onEdit { current ->
                            current.copy(
                                lines = current.lines.filterNot { currentLine ->
                                    currentLine.id == line.id
                                },
                            )
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        FilledTonalButton(
            onClick = { customLineVisible = true },
            enabled = !editor.readOnly && !uiState.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Добавить строку") }

        Spacer(modifier = Modifier.height(4.dp))
        MaintenanceCatalogBreadcrumbs(
            catalog = catalog,
            path = path,
            pendingWorkId = pendingWorkId,
            pendingMaterialId = pendingMaterialId,
            canGoBack = path.isNotEmpty() || pendingWorkId != null || pendingMaterialId != null,
            onNavigateTo = { pathLength ->
                path = path.take(pathLength)
                pendingWorkId = null
                pendingMaterialId = null
                page = 0
                catalogMessage = null
            },
            onBack = ::navigateBackInsideCatalog,
        )

        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(178.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                catalog.nodesById.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EmptyState(
                        title = "Каталог не загрузился",
                        description = "Повторите загрузку активного каталога RWMS.",
                    )
                    Button(
                        onClick = onRefreshCatalog,
                        enabled = !uiState.busy,
                    ) { Text("Повторить загрузку") }
                }

                visibleNodes.isEmpty() -> EmptyState(
                    title = "Позиции не найдены",
                    description = "Откройте другой раздел или проверьте связи активного каталога.",
                )

                else -> {
                    val gap = 8.dp
                    val rowHeight = 54.dp
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(178.dp)
                            .widthIn(max = 840.dp),
                        verticalArrangement = Arrangement.spacedBy(gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        userScrollEnabled = false,
                    ) {
                        gridItems(pagedVisibleNodes, key = CatalogNodeDto::id) { node ->
                            CompactCatalogNodeButton(
                                node = node,
                                enabled = !uiState.busy && !editor.readOnly,
                                onClick = { selectNode(node) },
                                onShowFullLabel = { expandedCatalogLabel = node.name },
                                modifier = Modifier.fillMaxWidth().height(rowHeight),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaintenanceCatalogPageButton(
                label = "←",
                contentDescription = "Предыдущая страница каталога",
                enabled = canGoToPreviousPage && !uiState.busy,
                onClick = { page = (currentPage - 1).coerceAtLeast(0) },
            )
            MaintenanceCatalogPageButton(
                label = "→",
                contentDescription = "Следующая страница каталога",
                enabled = canGoToNextPage && !uiState.busy,
                onClick = { page = (currentPage + 1).coerceAtMost(pageCount - 1) },
            )
            if (pageCount > 1) {
                Text(
                    "${currentPage + 1}/$pageCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onContinue,
                enabled = canContinue && !uiState.busy,
                modifier = Modifier.widthIn(min = 104.dp),
            ) { Text("Далее") }
        }
    }
}

@Composable
private fun CompactMaintenanceLineRow(
    line: MaintenanceLineEditorState,
    readOnly: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    line.reworkDisposition != null && line.lineType == "WORK" -> "РД"
                    line.reworkDisposition != null -> "МД"
                    line.lineType == "WORK" -> "Р"
                    else -> "М"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = buildString {
                    append(line.description)
                    if (line.lineType == "WORK") {
                        val photoCount = line.mediaReferences.size + line.photoUris.size
                        if (photoCount > 0) append(" · фото: $photoCount")
                    }
                },
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onEdit, enabled = !readOnly) { Text("Изменить") }
            TextButton(onClick = onRemove, enabled = !readOnly) { Text("Удалить") }
        }
        if (line.reworkDisposition == "REPEAT") {
            Text(
                "Переделать: ${line.repeatSourceDescription ?: line.description}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun ReworkCandidateRow(
    candidate: ReworkCandidateDto,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (candidate.line.lineType == "WORK") "Р" else "М",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp).weight(1f),
            ) {
                Text(
                    candidate.line.description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${candidate.line.quantity} ${candidate.line.unit ?: "ед."}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onToggle, enabled = enabled) {
                Text(if (selected) "Отменить" else "Переделать")
            }
        }
    }
}

@Composable
private fun CompactCatalogNodeButton(
    node: CatalogNodeDto,
    enabled: Boolean,
    onClick: () -> Unit,
    onShowFullLabel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var labelOverflow by remember(node.id) { mutableStateOf(false) }
    val configuredColor = catalogDisplayColorArgb(node.displayColor)?.let(::Color)
    val configuredContentColor = if (catalogDisplayColorNeedsLightContent(node.displayColor)) {
        Color.White
    } else {
        Color.Black
    }
    val interactionModifier = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(node.id, labelOverflow) {
            var longPressTriggered = false
            detectTapGestures(
                onPress = {
                    longPressTriggered = false
                    coroutineScope {
                        val holdTimer = launch {
                            delay(MAINTENANCE_CATALOG_LABEL_HOLD_MILLIS)
                            if (labelOverflow) {
                                longPressTriggered = true
                                onShowFullLabel()
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            holdTimer.cancel()
                        }
                    }
                },
                onTap = {
                    if (!longPressTriggered) onClick()
                },
            )
        }
    }
    Surface(
        modifier = modifier
            .then(interactionModifier)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = node.name
                if (enabled) {
                    onClick(label = "Выбрать ${node.name}") {
                        onClick()
                        true
                    }
                    if (labelOverflow) {
                        onLongClick(label = "Показать полное название") {
                            onShowFullLabel()
                            true
                        }
                    }
                }
            },
        shape = MaterialTheme.shapes.medium,
        color = when {
            !enabled -> MaterialTheme.colorScheme.surface
            configuredColor != null -> configuredColor
            else -> MaterialTheme.colorScheme.surface
        },
        contentColor = if (configuredColor != null && enabled) {
            configuredContentColor
        } else if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.55f else 0.25f),
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = node.name,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> labelOverflow = result.hasVisualOverflow },
            )
        }
    }
}

@Composable
private fun MaintenanceCatalogPageButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .width(48.dp)
        .semantics { this.contentDescription = contentDescription }
    if (enabled) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    } else {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = modifier,
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun MaintenanceCatalogLabelDialog(
    label: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaintenanceCatalogModeSelector(
    mode: MaintenanceCatalogMode,
    enabled: Boolean,
    onSelect: (MaintenanceCatalogMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { nextExpanded -> if (enabled) expanded = nextExpanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = mode.label,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().menuAnchor(
                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                enabled = enabled,
            ),
            label = { Text("Состав") },
            readOnly = true,
            enabled = enabled,
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MaintenanceCatalogMode.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onSelect(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MaintenanceCatalogBreadcrumbs(
    catalog: MaintenanceCatalogIndex,
    path: List<String>,
    pendingWorkId: String?,
    pendingMaterialId: String?,
    canGoBack: Boolean,
    onNavigateTo: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(onClick = { onNavigateTo(0) }) { Text("Каталог") }
        path.forEachIndexed { index, nodeId ->
            catalog.nodesById[nodeId]?.let { node ->
                Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onNavigateTo(index + 1) }) {
                    Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        pendingWorkId?.let(catalog.nodesById::get)?.let { node ->
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onBack) { Text(node.name, maxLines = 1) }
        }
        pendingMaterialId?.let(catalog.nodesById::get)?.let { node ->
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onBack) { Text(node.name, maxLines = 1) }
        }
        if (canGoBack) {
            TextButton(onClick = onBack) { Text("← Назад") }
        }
    }
}

private data class MaintenanceCatalogAddContext(
    val nodes: List<CatalogNodeDto>,
    val quantityNode: CatalogNodeDto,
)

private data class MaintenanceCatalogExistingWorkContext(
    val addContext: MaintenanceCatalogAddContext,
    val quantity: String,
    val comment: String,
    val photoUris: List<String>,
    val mediaReferences: List<MediaReferenceDto>,
    val candidates: List<MaintenanceLineEditorState>,
)

private data class MaintenanceWorkPhotoOption(
    val reference: MediaReferenceDto,
    val uri: String,
)

internal fun maintenanceCatalogSelectionHasWork(nodes: List<CatalogNodeDto>): Boolean =
    nodes.any { node -> node.nodeType == "WORK" }

internal fun maintenanceCatalogExistingWorkCandidates(
    editor: MaintenanceEditorState,
    nodes: List<CatalogNodeDto>,
): List<MaintenanceLineEditorState> {
    val workNodeIds = nodes
        .asSequence()
        .filter { node -> node.nodeType == "WORK" }
        .map(CatalogNodeDto::id)
        .toSet()
    return editor.lines.filter { line ->
        line.lineType == "WORK" && line.catalogNodeId in workNodeIds
    }
}

@Composable
private fun MaintenanceCatalogExistingWorkDialog(
    context: MaintenanceCatalogExistingWorkContext,
    onDismiss: () -> Unit,
    onChooseExisting: (String) -> Unit,
    onCreateNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Работа уже добавлена") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Выберите работу, к которой добавить количество, или создайте отдельную работу.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                context.candidates.forEachIndexed { index, line ->
                    OutlinedButton(
                        onClick = { onChooseExisting(line.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Добавить к работе ${index + 1}")
                            Text(
                                buildString {
                                    append(line.description)
                                    append(" · ")
                                    append(line.quantity)
                                    append(' ')
                                    append(line.unit.ifBlank { "ед." })
                                    line.comment.trim().takeIf(String::isNotEmpty)?.let { comment ->
                                        append(" · ")
                                        append(comment)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCreateNew) { Text("Создать новую работу") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaintenanceCatalogAddSheet(
    context: MaintenanceCatalogAddContext,
    availablePhotos: List<MaintenanceWorkPhotoOption>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, List<String>, List<MediaReferenceDto>) -> Unit,
) {
    var quantity by remember(context.quantityNode.id) { mutableStateOf("1") }
    var comment by remember(context.quantityNode.id) { mutableStateOf("") }
    var capturedPhotoUris by remember(context.quantityNode.id) {
        mutableStateOf(emptyList<String>())
    }
    var selectedMediaIds by remember(context.quantityNode.id) {
        mutableStateOf(emptySet<String>())
    }
    var cameraOpen by remember(context.quantityNode.id) { mutableStateOf(false) }
    var pickerOpen by remember(context.quantityNode.id) { mutableStateOf(false) }
    val workCount = context.nodes.count { node -> node.nodeType == "WORK" }
    val hasWork = workCount > 0
    val canAttachWorkPhotos = workCount == 1
    val validQuantity = maintenanceQuantity(quantity)?.let { it > BigDecimal.ZERO } == true
    val validComment = !hasWork || comment.trim().length <= 2_000
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.5f)
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        if (hasWork) {
                            "Добавить работу"
                        } else {
                            "Добавить материал"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                item {
                    Text(
                        context.nodes.joinToString(" + ") { node -> node.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Количество") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = quantity.isNotBlank() && !validQuantity,
                    )
                }
                if (hasWork) {
                    item {
                        OutlinedTextField(
                            value = comment,
                            onValueChange = { comment = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Комментарий") },
                            minLines = 2,
                            isError = !validComment,
                            supportingText = if (validComment) null else {
                                { Text("Комментарий не может быть длиннее 2000 символов") }
                            },
                        )
                    }
                    if (canAttachWorkPhotos) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { cameraOpen = true },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Добавить фото") }
                                OutlinedButton(
                                    onClick = { pickerOpen = true },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Выбрать из сделанных") }
                            }
                        }
                        item {
                            Text(
                                text = if (capturedPhotoUris.isEmpty() && selectedMediaIds.isEmpty()) {
                                    "Фото необязательны и относятся только к этой работе."
                                } else {
                                    "Выбрано фото: ${capturedPhotoUris.size + selectedMediaIds.size}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        item {
                            Text(
                                "Фото можно прикрепить после выбора одной конкретной работы.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            onConfirm(
                                quantity.trim(),
                                comment.trim().takeIf { hasWork }.orEmpty(),
                                capturedPhotoUris.takeIf { canAttachWorkPhotos }.orEmpty(),
                                availablePhotos
                                    .filter { option ->
                                        canAttachWorkPhotos &&
                                            option.reference.mediaId in selectedMediaIds
                                    }
                                    .map(MaintenanceWorkPhotoOption::reference),
                            )
                        },
                        enabled = validQuantity && validComment,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Далее") }
                }
            }
        }
    }

    if (cameraOpen) {
        Dialog(
            onDismissRequest = { cameraOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ManagerPhotoCaptureScreen(
                    title = "Фото работы",
                    photoUris = capturedPhotoUris,
                    onPhotoCaptured = { uri ->
                        capturedPhotoUris = (capturedPhotoUris + uri).distinct()
                    },
                    onRemovePhotoUri = { uri ->
                        capturedPhotoUris = capturedPhotoUris - uri
                    },
                    onBack = { cameraOpen = false },
                )
            }
        }
    }
    if (pickerOpen) {
        MaintenanceWorkPhotoPickerDialog(
            availablePhotos = availablePhotos,
            selectedMediaIds = selectedMediaIds,
            onSelectedMediaIdsChange = { selectedMediaIds = it },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun MaintenanceWorkPhotoPickerDialog(
    availablePhotos: List<MaintenanceWorkPhotoOption>,
    selectedMediaIds: Set<String>,
    onSelectedMediaIdsChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбрать фото работы") },
        text = {
            if (availablePhotos.isEmpty()) {
                Text("Свободных загруженных фотографий пока нет.")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(availablePhotos, key = { it.reference.mediaId }) { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ManagerPhotoPreview(
                                photoUri = option.uri,
                                modifier = Modifier.width(96.dp).aspectRatio(4f / 3f),
                            )
                            Checkbox(
                                checked = option.reference.mediaId in selectedMediaIds,
                                onCheckedChange = { checked ->
                                    onSelectedMediaIdsChange(
                                        if (checked) {
                                            selectedMediaIds + option.reference.mediaId
                                        } else {
                                            selectedMediaIds - option.reference.mediaId
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Выбрать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

internal enum class MaintenanceCatalogMode(val label: String) {
    LINKED_SET("Работы + материалы"),
    WORKS_ONLY("Только работы"),
    MATERIALS_ONLY("Только материалы"),
}

internal data class MaintenanceCatalogIndex(
    val nodesById: Map<String, CatalogNodeDto>,
    val operationalMenuNodes: List<CatalogNodeDto>,
    private val childrenByParentId: Map<String, List<CatalogNodeDto>>,
    private val dependencyNodesBySourceId: Map<String, List<CatalogNodeDto>>,
    private val followUpNodesBySourceId: Map<String, List<CatalogNodeDto>>,
    private val dependencyRelatedNodesById: Map<String, List<CatalogNodeDto>>,
    private val furnitureNodeIds: Set<String>,
) {
    fun childrenOf(nodeId: String): List<CatalogNodeDto> = childrenByParentId[nodeId].orEmpty()
    fun dependencyNodesOf(nodeId: String): List<CatalogNodeDto> =
        dependencyNodesBySourceId[nodeId].orEmpty()

    fun followUpNodesOf(nodeId: String): List<CatalogNodeDto> =
        followUpNodesBySourceId[nodeId].orEmpty()

    fun dependencyRelatedNodesOf(nodeId: String): List<CatalogNodeDto> =
        dependencyRelatedNodesById[nodeId].orEmpty()

    fun isFurnitureNode(nodeId: String): Boolean = nodeId in furnitureNodeIds
}

internal fun maintenanceCatalogIndex(
    nodes: List<CatalogNodeDto>,
    links: List<CatalogLinkDto>,
): MaintenanceCatalogIndex {
    val allNodesById = nodes.associateBy(CatalogNodeDto::id)
    val activeNodes = nodes.filter(CatalogNodeDto::active)
    val nodesById = activeNodes.associateBy(CatalogNodeDto::id)
    val furnitureNodeIds = activeNodes
        .asSequence()
        .filter { node -> node.belongsToFurnitureTree(allNodesById) }
        .map(CatalogNodeDto::id)
        .toSet()
    fun sortedNodes(values: Collection<CatalogNodeDto>): List<CatalogNodeDto> =
        values.distinctBy(CatalogNodeDto::id).sortedWith(catalogNodeOrder)

    val childrenByParent = activeNodes
        .filter { node -> node.parentNodeId in nodesById }
        .groupBy { node -> requireNotNull(node.parentNodeId) }
        .mapValues { (_, children) -> sortedNodes(children) }
    val activeLinks = links.filter { link ->
        link.fromNodeId in nodesById && link.toNodeId in nodesById
    }
    val dependencyBySource = activeLinks
        .filter { link -> link.linkType == "DEPENDENCY" }
        .groupBy(CatalogLinkDto::fromNodeId)
        .mapValues { (_, groupedLinks) ->
            groupedLinks.sortedWith(catalogLinkOrder)
                .mapNotNull { link -> nodesById[link.toNodeId] }
                .let(::sortedNodes)
        }
    val followUpBySource = activeLinks
        .filter { link -> link.linkType == "FOLLOW_UP" }
        .groupBy(CatalogLinkDto::fromNodeId)
        .mapValues { (_, groupedLinks) ->
            groupedLinks.sortedWith(catalogLinkOrder)
                .mapNotNull { link -> nodesById[link.toNodeId] }
                .let(::sortedNodes)
        }
    val relatedIds = mutableMapOf<String, MutableSet<String>>()
    activeLinks.filter { link -> link.linkType == "DEPENDENCY" }.forEach { link ->
        relatedIds.getOrPut(link.fromNodeId, ::mutableSetOf).add(link.toNodeId)
        relatedIds.getOrPut(link.toNodeId, ::mutableSetOf).add(link.fromNodeId)
    }
    val relatedByNode = relatedIds.mapValues { (_, ids) ->
        sortedNodes(ids.mapNotNull(nodesById::get))
    }
    val roots = activeNodes
        .filter { node -> node.nodeType == "CATEGORY" && node.parentNodeId == null }
        .let(::sortedNodes)
    val mainMenu = activeNodes.filter(CatalogNodeDto::showInMainMenu).let(::sortedNodes)
    val operationalMenu = mainMenu.ifEmpty {
        roots.ifEmpty {
            activeNodes.filter { node -> childrenByParent.containsKey(node.id) }.let(::sortedNodes)
        }
    }.ifEmpty {
        activeNodes.filter(CatalogNodeDto::isOperationalMaintenanceCatalogNode).let(::sortedNodes)
    }
    return MaintenanceCatalogIndex(
        nodesById = nodesById,
        operationalMenuNodes = operationalMenu,
        childrenByParentId = childrenByParent,
        dependencyNodesBySourceId = dependencyBySource,
        followUpNodesBySourceId = followUpBySource,
        dependencyRelatedNodesById = relatedByNode,
        furnitureNodeIds = furnitureNodeIds,
    )
}

internal fun maintenanceCatalogVisibleNodes(
    catalog: MaintenanceCatalogIndex,
    mode: MaintenanceCatalogMode,
    path: List<String> = emptyList(),
    pendingWorkId: String? = null,
    pendingMaterialId: String? = null,
    excludeFurniture: Boolean = false,
): List<CatalogNodeDto> {
    val candidates = when {
        pendingMaterialId != null -> maintenanceCatalogNavigationNodes(catalog, pendingMaterialId)

        pendingWorkId != null -> catalog.dependencyRelatedNodesOf(pendingWorkId)
        else -> path.lastOrNull()?.let(catalog.nodesById::get)?.let { node ->
            maintenanceCatalogNavigationNodes(catalog, node.id)
        } ?: catalog.operationalMenuNodes
    }
    return maintenanceCatalogNodesForUsage(candidates, catalog, excludeFurniture)
        .filter { node ->
            when {
                pendingMaterialId != null -> node.nodeType == "LOCATION"
                pendingWorkId != null -> node.nodeType == "MATERIAL"
                mode == MaintenanceCatalogMode.WORKS_ONLY -> node.nodeType != "MATERIAL"
                mode == MaintenanceCatalogMode.MATERIALS_ONLY -> {
                    node.nodeType != "WORK" ||
                        catalog.dependencyRelatedNodesOf(node.id).any { related ->
                            related.nodeType == "MATERIAL"
                        }
                }

                else -> true
            }
        }
        .distinctBy(CatalogNodeDto::id)
        .sortedWith(catalogNodeOrder)
}

/**
 * The catalog editor stores all nodes of a visual branch below the same parent so the canvas can
 * move them together. Estimators navigate by outgoing arrows instead: a node with arrows exposes
 * only their targets, while plain branches retain their parent-child fallback.
 */
internal fun maintenanceCatalogNavigationNodes(
    catalog: MaintenanceCatalogIndex,
    nodeId: String,
): List<CatalogNodeDto> {
    val linked = (catalog.followUpNodesOf(nodeId) + catalog.dependencyNodesOf(nodeId))
        .distinctBy(CatalogNodeDto::id)
    return linked.ifEmpty { catalog.childrenOf(nodeId) }
}

internal fun maintenanceCatalogNodesForUsage(
    nodes: List<CatalogNodeDto>,
    catalog: MaintenanceCatalogIndex,
    excludeFurniture: Boolean,
): List<CatalogNodeDto> = nodes.filter { node ->
    val furniture = catalog.isFurnitureNode(node.id)
    !(
        (excludeFurniture && furniture) ||
            (furniture && node.nodeType == "MATERIAL" && node.furnitureEquipment == null)
        )
}

private fun CatalogNodeDto.belongsToFurnitureTree(
    allNodesById: Map<String, CatalogNodeDto>,
): Boolean {
    val visited = mutableSetOf<String>()
    var current: CatalogNodeDto? = this
    while (current != null && visited.add(current.id)) {
        if (current.furnitureCategory) return true
        current = current.parentNodeId?.let(allNodesById::get)
    }
    return false
}

private fun CatalogNodeDto.isOperationalMaintenanceCatalogNode(): Boolean =
    active && includeInEstimate && nodeType in maintenanceSelectableCatalogTypes

private val maintenanceCatalogContainerTypes = setOf("CATEGORY", "SUBCATEGORY")
private val catalogLinkOrder = compareBy<CatalogLinkDto> { link -> link.sortOrder }
    .thenBy(CatalogLinkDto::id)

private val catalogNodeOrder = compareBy<CatalogNodeDto> { node -> node.name.lowercase() }
    .thenBy(CatalogNodeDto::id)

private data class CustomMaintenanceLineDraft(
    val lineType: String = "WORK",
    val description: String = "",
    val comment: String = "",
    val durationMinutes: String = "30",
    val quantity: String = "1",
    val unit: String = "",
    val unitPrice: String = "0.00",
    val routing: RoutingSnapshotDto? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomMaintenanceLineSheet(
    editor: MaintenanceEditorState,
    catalogNodes: List<CatalogNodeDto>,
    catalogLinks: List<CatalogLinkDto>,
    liveRoutings: List<RoutingSnapshotDto>,
    availablePhotos: List<MaintenanceWorkPhotoOption>,
    onDismiss: () -> Unit,
    onAdd: (MaintenanceLineEditorState) -> Unit,
) {
    var draft by remember(editor.entityId, editor.lines.size) {
        mutableStateOf(CustomMaintenanceLineDraft())
    }
    var capturedPhotoUris by remember(editor.entityId, editor.lines.size) {
        mutableStateOf(emptyList<String>())
    }
    var selectedMediaIds by remember(editor.entityId, editor.lines.size) {
        mutableStateOf(emptySet<String>())
    }
    var cameraOpen by remember(editor.entityId, editor.lines.size) { mutableStateOf(false) }
    var pickerOpen by remember(editor.entityId, editor.lines.size) { mutableStateOf(false) }
    val stageRoutings = (editor.stages
        .filter { stage -> stage.kind == "REPAIR_WORK" }
        .map { stage -> stage.routing } + editor.lines
        .filter { line -> line.lineType == "WORK" }
        .mapNotNull { line ->
            line.customRouting ?: line.catalogSnapshot?.routing ?: line.catalogNodeId?.let { nodeId ->
                maintenanceCatalogRouting(nodeId, catalogNodes, catalogLinks)
            }
        })
        .filter(RoutingSnapshotDto::isUsableMaintenanceRouting)
        .distinctBy(RoutingSnapshotDto::routingIdentity)
    val availableRoutings = (liveRoutings + stageRoutings + catalogNodes
        .filter { node -> node.active }
        .mapNotNull { node -> maintenanceCatalogRouting(node.id, catalogNodes, catalogLinks) })
        .filter(RoutingSnapshotDto::isUsableMaintenanceRouting)
        .distinctBy(RoutingSnapshotDto::routingIdentity)
    val routingChoices = availableRoutings
    val duration = draft.durationMinutes.toIntOrNull()
    val canAdd = draft.description.isNotBlank() &&
        draft.unit.isNotBlank() &&
        draft.routing != null &&
        maintenanceQuantity(draft.quantity)?.let { it > BigDecimal.ZERO } == true &&
        maintenancePrice(draft.unitPrice) != null &&
        (draft.lineType == "MATERIAL" || duration in 1..525_600)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.5f)
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Text("Пользовательская строка", style = MaterialTheme.typography.titleLarge) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.lineType == "WORK",
                            onClick = {
                                draft = draft.copy(lineType = "WORK", routing = draft.routing)
                            },
                            label = { Text("Работа") },
                        )
                        FilterChip(
                            selected = draft.lineType == "MATERIAL",
                            onClick = {
                                draft = draft.copy(
                                    lineType = "MATERIAL",
                                    durationMinutes = "0",
                                    comment = "",
                                    routing = draft.routing ?: stageRoutings.firstOrNull()
                                        ?: availableRoutings.firstOrNull(),
                                )
                                capturedPhotoUris = emptyList()
                                selectedMediaIds = emptySet()
                            },
                            label = { Text("Материал") },
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { value -> draft = draft.copy(description = value) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Наименование") },
                        singleLine = true,
                    )
                }
                if (draft.lineType == "WORK") {
                    item {
                        OutlinedTextField(
                            value = draft.comment,
                            onValueChange = { value -> draft = draft.copy(comment = value) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Комментарий") },
                            minLines = 2,
                        )
                    }
                    item {
                        Text("Фото работы", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { cameraOpen = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("Добавить фото") }
                            OutlinedButton(
                                onClick = { pickerOpen = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("Выбрать из сделанных") }
                        }
                    }
                    item {
                        Text(
                            if (capturedPhotoUris.isEmpty() && selectedMediaIds.isEmpty()) {
                                "Фото необязательны и относятся только к этой работе."
                            } else {
                                "Выбрано фото: ${capturedPhotoUris.size + selectedMediaIds.size}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = draft.durationMinutes,
                            onValueChange = { value -> draft = draft.copy(durationMinutes = value) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Длительность, мин.") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                item {
                    Text("Очередь или этап работы", style = MaterialTheme.typography.labelLarge)
                    if (routingChoices.isEmpty()) {
                        Text(
                            "В активной доске нет доступной очереди работ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            routingChoices.forEach { routing ->
                                FilterChip(
                                    selected = draft.routing?.routingIdentity() == routing.routingIdentity(),
                                    onClick = { draft = draft.copy(routing = routing) },
                                    label = { Text(routing.routingLabel()) },
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = draft.quantity,
                        onValueChange = { value -> draft = draft.copy(quantity = value) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Количество") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                item {
                    OutlinedTextField(
                        value = draft.unit,
                        onValueChange = { value -> draft = draft.copy(unit = value) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Единица") },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = draft.unitPrice,
                        onValueChange = { value -> draft = draft.copy(unitPrice = value) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Цена за единицу, ₽") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                item {
                    val preview = maintenanceLineTotal(
                        MaintenanceLineEditorState(
                            id = "preview",
                            catalogNodeId = null,
                            description = draft.description,
                            lineType = draft.lineType,
                            unit = draft.unit,
                            quantity = draft.quantity,
                            unitPrice = draft.unitPrice,
                            normativeMinutes = if (draft.lineType == "MATERIAL") 0 else duration ?: 0,
                            comment = draft.comment.takeIf { draft.lineType == "WORK" }.orEmpty(),
                            customRouting = draft.routing,
                        ),
                    )
                    Text(
                        if (preview == null) "Итог появится после проверки количества и цены." else {
                            "Итого: ${formatMaintenanceMoney(preview)}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    Button(
                        onClick = {
                            onAdd(
                                MaintenanceLineEditorState(
                                    id = UUID.randomUUID().toString(),
                                    catalogNodeId = null,
                                    description = draft.description.trim(),
                                    lineType = draft.lineType,
                                    unit = draft.unit.trim(),
                                    quantity = draft.quantity.trim(),
                                    unitPrice = draft.unitPrice.trim(),
                                    normativeMinutes = if (draft.lineType == "MATERIAL") {
                                        0
                                    } else {
                                        requireNotNull(duration)
                                    },
                                    comment = draft.comment.trim().takeIf {
                                        draft.lineType == "WORK"
                                    }.orEmpty(),
                                    customRouting = draft.routing,
                                    photoUris = capturedPhotoUris.takeIf {
                                        draft.lineType == "WORK"
                                    }.orEmpty(),
                                    mediaReferences = availablePhotos
                                        .filter { option ->
                                            option.reference.mediaId in selectedMediaIds
                                        }
                                        .map(MaintenanceWorkPhotoOption::reference),
                                ),
                            )
                        },
                        enabled = canAdd,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Добавить строку") }
                }
            }
        }
    }
    if (cameraOpen) {
        Dialog(
            onDismissRequest = { cameraOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ManagerPhotoCaptureScreen(
                    title = "Фото работы",
                    photoUris = capturedPhotoUris,
                    onPhotoCaptured = { uri ->
                        capturedPhotoUris = (capturedPhotoUris + uri).distinct()
                    },
                    onRemovePhotoUri = { uri ->
                        capturedPhotoUris = capturedPhotoUris - uri
                    },
                    onBack = { cameraOpen = false },
                )
            }
        }
    }
    if (pickerOpen) {
        MaintenanceWorkPhotoPickerDialog(
            availablePhotos = availablePhotos,
            selectedMediaIds = selectedMediaIds,
            onSelectedMediaIdsChange = { selectedMediaIds = it },
            onDismiss = { pickerOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaintenanceLineEditSheet(
    line: MaintenanceLineEditorState,
    availablePhotos: List<MaintenanceWorkPhotoOption>,
    onDismiss: () -> Unit,
    onSave: (MaintenanceLineEditorState) -> Unit,
) {
    var quantity by remember(line.id) { mutableStateOf(line.quantity) }
    var comment by remember(line.id) { mutableStateOf(line.comment) }
    var capturedPhotoUris by remember(line.id) { mutableStateOf(line.photoUris) }
    var selectedMediaIds by remember(line.id) {
        mutableStateOf(line.mediaReferences.map(MediaReferenceDto::mediaId).toSet())
    }
    var cameraOpen by remember(line.id) { mutableStateOf(false) }
    var pickerOpen by remember(line.id) { mutableStateOf(false) }
    val canSave = maintenanceQuantity(quantity)?.let { it > BigDecimal.ZERO } == true &&
        (line.lineType != "WORK" || comment.length <= 2_000)
    val selectedMedia = (
        line.mediaReferences.filter { reference -> reference.mediaId in selectedMediaIds } +
            availablePhotos
                .filter { option -> option.reference.mediaId in selectedMediaIds }
                .map(MaintenanceWorkPhotoOption::reference)
        ).distinctBy(MediaReferenceDto::mediaId)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.5f)
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Text(line.description, style = MaterialTheme.typography.titleLarge) }
                item { Text(catalogTypeLabel(line.lineType), style = MaterialTheme.typography.labelLarge) }
                item {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Количество") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                if (line.lineType == "WORK") {
                    item {
                        OutlinedTextField(
                            value = comment,
                            onValueChange = { comment = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Комментарий") },
                            minLines = 2,
                        )
                    }
                    item {
                        Text("Фото работы", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { cameraOpen = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("Добавить фото") }
                            OutlinedButton(
                                onClick = { pickerOpen = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("Выбрать из сделанных") }
                        }
                    }
                    if (capturedPhotoUris.isEmpty() && selectedMedia.isEmpty()) {
                        item {
                            Text(
                                "Фото необязательны и относятся только к этой работе.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        capturedPhotoUris.forEach { uri ->
                            item(key = "local:$uri") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    ManagerPhotoPreview(
                                        photoUri = uri,
                                        modifier = Modifier.width(96.dp).aspectRatio(4f / 3f),
                                    )
                                    Text("Новое фото", modifier = Modifier.weight(1f))
                                    TextButton(
                                        onClick = { capturedPhotoUris = capturedPhotoUris - uri },
                                    ) { Text("Убрать") }
                                }
                            }
                        }
                        availablePhotos
                            .filter { option -> option.reference.mediaId in selectedMediaIds }
                            .forEach { option ->
                                item(key = "media:${option.reference.mediaId}") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        ManagerPhotoPreview(
                                            photoUri = option.uri,
                                            modifier = Modifier.width(96.dp).aspectRatio(4f / 3f),
                                        )
                                        Text("Загруженное фото", modifier = Modifier.weight(1f))
                                        TextButton(
                                            onClick = {
                                                selectedMediaIds = selectedMediaIds -
                                                    option.reference.mediaId
                                            },
                                        ) { Text("Убрать") }
                                    }
                                }
                            }
                    }
                }
                item {
                    Button(
                        onClick = {
                            onSave(
                                line.copy(
                                    quantity = quantity.trim(),
                                    comment = comment.trim().takeIf {
                                        line.lineType == "WORK"
                                    }.orEmpty(),
                                    photoUris = capturedPhotoUris.takeIf {
                                        line.lineType == "WORK"
                                    }.orEmpty(),
                                    mediaReferences = selectedMedia.takeIf {
                                        line.lineType == "WORK"
                                    }.orEmpty(),
                                ),
                            )
                        },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить строку") }
                }
            }
        }
    }
    if (cameraOpen) {
        Dialog(
            onDismissRequest = { cameraOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ManagerPhotoCaptureScreen(
                    title = "Фото работы",
                    photoUris = capturedPhotoUris,
                    onPhotoCaptured = { uri ->
                        capturedPhotoUris = (capturedPhotoUris + uri).distinct()
                    },
                    onRemovePhotoUri = { uri ->
                        capturedPhotoUris = capturedPhotoUris - uri
                    },
                    onBack = { cameraOpen = false },
                )
            }
        }
    }
    if (pickerOpen) {
        MaintenanceWorkPhotoPickerDialog(
            availablePhotos = availablePhotos,
            selectedMediaIds = selectedMediaIds,
            onSelectedMediaIdsChange = { selectedMediaIds = it },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun MaintenancePhotosStep(
    editor: MaintenanceEditorState,
    onOpenPhotos: () -> Unit,
    onAddPhoto: (String) -> Unit,
    onSelectCover: (String) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onContinue: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            copyManagerPhotoToAppCache(context, uri)?.let(onAddPhoto)
        }
    }
    val hasPhotos = editor.photoUris.isNotEmpty() || editor.readyMedia.isNotEmpty()
    val photos = editor.readyMedia.map { media ->
        MaintenancePhotoGridItem(
            key = maintenanceReadyPhotoKey(media.mediaId),
            uri = editor.readyPhotoUris[media.mediaId],
        )
    } + editor.photoUris.map { uri ->
        MaintenancePhotoGridItem(
            key = maintenanceLocalPhotoKey(uri),
            uri = uri,
        )
    }
    val galleryPhotoUris = photos.mapNotNull(MaintenancePhotoGridItem::uri)
    var galleryPhotoUri by remember(editor.entityId, editor.mode) { mutableStateOf<String?>(null) }
    val policy = maintenancePhotoStepPolicy(editor)
    galleryPhotoUri?.let { initialPhoto ->
        val initialIndex = galleryPhotoUris.indexOf(initialPhoto)
        if (initialIndex >= 0) {
            ManagerPhotoGalleryDialog(
                photoUris = galleryPhotoUris,
                initialIndex = initialIndex,
                title = "Фото состояния",
                onDismiss = { galleryPhotoUri = null },
            )
        }
    }
    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ManagerPanel {
                    Text("Фото состояния", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Выберите титульное фото",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!editor.readOnly) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = onOpenPhotos,
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
                    if (hasPhotos) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            photos.chunked(2).forEach { rowPhotos ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowPhotos.forEach { photo ->
                                        MaintenancePhotoGridCard(
                                            photo = photo,
                                            selected = editor.coverPhotoKey == photo.key,
                                            enabled = !editor.readOnly && !busy,
                                            onSelect = { onSelectCover(photo.key) },
                                            onPreview = photo.uri?.let { uri ->
                                                { galleryPhotoUri = uri }
                                            },
                                            onRemove = if (editor.readOnly) {
                                                null
                                            } else {
                                                { onRemovePhoto(photo.key) }
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (rowPhotos.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            "Фото ещё не добавлены.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!hasPhotos && maintenanceRequiresPhotos(editor)) {
                item {
                    Text(
                        "Добавьте хотя бы одну фотографию, чтобы перейти к каталогу.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (!maintenanceHasCoverPhoto(editor)) {
                item {
                    Text(
                        "Выберите титульную фотографию.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (policy.showNext) {
            Button(
                onClick = onContinue,
                enabled = policy.enabled && !busy,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Далее")
            }
        }
    }
}

private data class MaintenancePhotoGridItem(
    val key: String,
    val uri: String?,
)

@Composable
private fun MaintenancePhotoGridCard(
    photo: MaintenancePhotoGridItem,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onPreview: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val previewInteractionModifier = Modifier
        .fillMaxWidth()
        .then(
            if (enabled || onPreview != null) {
                Modifier.pointerInput(photo.key, enabled, onPreview) {
                    detectTapGestures(
                        onTap = { if (enabled) onSelect() },
                        onLongPress = { onPreview?.invoke() },
                    )
                }
            } else {
                Modifier
            },
        )
    Surface(
        modifier = modifier
            .aspectRatio(4f / 3f)
            .semantics {
                this.selected = selected
                if (enabled || onPreview != null) {
                    role = Role.Button
                }
                if (enabled) {
                    onClick(label = "Выбрать титульное фото") {
                        onSelect()
                        true
                    }
                }
                if (onPreview != null) {
                    onLongClick(label = "Открыть полноэкранный просмотр") {
                        onPreview()
                        true
                    }
                }
            },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        tonalElevation = 0.dp,
    ) {
        Column {
            val previewModifier = previewInteractionModifier.weight(1f)
            if (photo.uri != null) {
                ManagerPhotoPreview(
                    photo.uri,
                    previewModifier,
                )
            } else {
                Box(
                    modifier = previewModifier,
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Фото не загрузилось",
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onRemove != null) {
                TextButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Удалить") }
            }
    }
}
}

@Composable
private fun MaintenanceReviewStep(
    editor: MaintenanceEditorState,
    onEdit: ((MaintenanceEditorState) -> MaintenanceEditorState) -> Unit,
    canSubmit: Boolean,
    busy: Boolean,
    onSaveDraft: (() -> Unit) -> Unit,
    onSubmit: (() -> Unit) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = maintenanceTotal(editor.lines)
    val emptyEstimate = isEmptyMaintenanceEstimate(editor)
    val emptyOutcome = isEmptyMaintenanceOutcome(editor)
    val alreadySubmitted = maintenanceDocumentAlreadySubmitted(editor)
    val repairCount = editor.lines.count { line -> line.lineType == "WORK" }
    val materialCount = editor.lines.count { line -> line.lineType == "MATERIAL" }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ManagerPanel {
                Text("Итог", style = MaterialTheme.typography.titleMedium)
                Text("Бытовка: ${editor.selectedAsset?.number ?: "не выбрана"}")
                if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                    Text("Осмотр: ${editor.dispatchDate.ifBlank { "не указан" }}")
                    Text("От кого: ${editor.sourceParty.ifBlank { "не указано" }}")
                }
                Text("Ремонты: $repairCount единиц")
                Text("Материалы: $materialCount единиц")
                Text("Фото: ${editor.readyMedia.size + editor.photoUris.size}")
                Text(
                    if (total == null) "Итог: требуется проверить строки" else "Итог: ${formatMaintenanceMoney(total)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (!emptyOutcome) {
            item {
                ManagerPanel {
                    RepairMovementLogisticsOptions(
                        editor = editor,
                        enabled = !editor.readOnly && !alreadySubmitted,
                        onEdit = onEdit,
                    )
                    Text(
                        "Приоритет ремонта: ${editor.priority}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (editor.movementToRepair) {
                            "Выбранный приоритет передаётся ремонту и входящему заданию " +
                                "логистики; после доставки сервер назначит задаче ремонтной " +
                                "очереди приоритет 1."
                        } else {
                            "Без перемещения на ремонт выбранный приоритет применяется напрямую " +
                                "к ремонту."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            ManagerPanel {
                if (editor.readOnly) {
                    Text(
                        "Работа уже начата или документ закрыт. Изменения больше недоступны.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
                } else if (alreadySubmitted) {
                    Text(
                        "Работы и материалы можно изменить, пока задание не взято в работу.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onSaveDraft(onDone) },
                        enabled = canSubmit && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить изменения") }
                    if (!canSubmit) {
                        Text(
                            maintenanceSubmitValidationMessage(editor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        if (emptyEstimate) {
                            "Пустая смета завершит осмотр, переведёт бытовку в статус «Свободная» и не создаст ремонт."
                        } else if (emptyOutcome) {
                            "Пустой ремонт переведёт бытовку в статус «Свободная»."
                        } else if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                            "Завершение сметы создаст связанный ремонт в сервисе."
                        } else if (editor.repairKind == "REWORK") {
                            "Доработка будет сохранена отдельным ремонтом и поставлена в очередь."
                        } else {
                            "Прямой ремонт не создаёт смету и будет поставлен в ремонт сразу."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onSaveDraft(onDone) },
                        enabled = canSubmit && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить черновик") }
                    FilledTonalButton(
                        onClick = { onSubmit(onDone) },
                        enabled = canSubmit && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (emptyOutcome) {
                                "Завершить и освободить"
                            } else if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                                "Завершить и создать ремонт"
                            } else if (editor.repairKind == "REWORK") {
                                "Поставить доработку"
                            } else {
                                "Поставить в ремонт"
                            },
                        )
                    }
                    if (!canSubmit) {
                        Text(
                            maintenanceSubmitValidationMessage(editor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

internal fun maintenanceWizardTitle(step: Int): String = when (maintenanceWizardStep(step)) {
    1 -> "Бытовка и основные данные"
    2 -> "Фотографии"
    3 -> "Каталог работ"
    else -> "Проверка и действия"
}

@Composable
private fun RowWithPill(title: String, pill: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StatusPill(
            label = pill,
            emphasis = if (pill == "DRAFT" || pill == "Черновик") {
                StatusPillEmphasis.Neutral
            } else {
                StatusPillEmphasis.Positive
            },
        )
    }
}

private val maintenanceSelectableCatalogTypes = setOf("WORK", "MATERIAL", "OPTION")

private fun RoutingSnapshotDto.routingIdentity(): String =
    "${queueId.trim()}:${queueName.trim()}:${queueType.trim()}"

private fun RoutingSnapshotDto.isUsableMaintenanceRouting(): Boolean =
    queueId.isNotBlank() && queueName.isNotBlank() && queueType.isNotBlank()

private fun maintenanceCatalogRouting(
    nodeId: String,
    nodes: List<CatalogNodeDto>,
    links: List<CatalogLinkDto>,
): RoutingSnapshotDto? {
    val activeNodesById = nodes.filter(CatalogNodeDto::active).associateBy(CatalogNodeDto::id)
    val incomingParents = links
        .filter { link ->
            link.fromNodeId in activeNodesById && link.toNodeId in activeNodesById
        }
        .groupBy(CatalogLinkDto::toNodeId)
        .mapValues { (_, nodeLinks) ->
            nodeLinks.sortedWith(
                compareBy<CatalogLinkDto> { it.sortOrder }
                    .thenBy { link -> activeNodesById[link.fromNodeId]?.name.orEmpty() }
                    .thenBy(CatalogLinkDto::id),
            ).mapNotNull { link -> activeNodesById[link.fromNodeId] }
        }
    var current = activeNodesById[nodeId]
    val visited = mutableSetOf<String>()
    while (current != null && visited.add(current.id)) {
        current.routing?.takeIf(RoutingSnapshotDto::isUsableMaintenanceRouting)?.let { return it }
        current = if (current.parentNodeId != null) {
            activeNodesById[current.parentNodeId]
        } else {
            incomingParents[current.id]?.firstOrNull { parent -> parent.id !in visited }
        }
    }
    return null
}

private fun MaintenanceEditorState.replaceMaintenanceLine(
    lineId: String,
    transform: (MaintenanceLineEditorState) -> MaintenanceLineEditorState,
): MaintenanceEditorState {
    val updatedLines = lines.map { line ->
        if (line.id == lineId) transform(line) else line
    }
    val workMediaIds = updatedLines
        .asSequence()
        .filter { line -> line.lineType == "WORK" }
        .flatMap { line -> line.mediaReferences.asSequence() }
        .map(MediaReferenceDto::mediaId)
        .toSet()
    return copy(
        lines = updatedLines,
        readyMedia = readyMedia.filterNot { reference -> reference.mediaId in workMediaIds },
        coverPhotoKey = coverPhotoKey.takeUnless { key ->
            key?.removePrefix("media:") in workMediaIds
        },
    )
}

internal fun maintenanceCanAdvance(editor: MaintenanceEditorState, step: Int): Boolean = when (step) {
    1 -> maintenanceDetailsAreValid(editor)
    2 -> maintenancePhotoStepPolicy(editor).enabled
    3 -> isEmptyMaintenanceOutcome(editor) ||
        editor.lines.all(::maintenanceLineIsValid)
    4 -> isEmptyMaintenanceOutcome(editor) ||
        (editor.lines.isNotEmpty() && editor.lines.all(::maintenanceLineIsValid))
    else -> true
}

internal fun maintenanceCanSubmit(editor: MaintenanceEditorState): Boolean {
    val emptyOutcome = isEmptyMaintenanceOutcome(editor)
    val photoStepValid = if (!maintenanceHasPhotos(editor)) {
        !maintenanceRequiresPhotos(editor)
    } else {
        maintenanceHasCoverPhoto(editor)
    }
    return maintenanceDetailsAreValid(editor) &&
        photoStepValid &&
        (emptyOutcome ||
            (editor.lines.isNotEmpty() &&
                editor.lines.all(::maintenanceLineIsValid))) &&
        editor.logisticsTaskPriorityValidationError() == null &&
        editor.logisticsPlanningValidationError() == null
}

private fun maintenanceDetailsAreValid(editor: MaintenanceEditorState): Boolean =
    editor.selectedAsset != null &&
        editor.logisticsTaskPriorityValidationError() == null &&
        (editor.mode == MaintenanceEditorMode.REPAIR || isMaintenanceDate(editor.dispatchDate)) &&
        (editor.repairKind != "REWORK" ||
            editor.entityId != null ||
            editor.reworkReason.trim().isNotEmpty())

internal data class MaintenancePhotoStepPolicy(
    val showNext: Boolean,
    val targetStep: Int,
    val enabled: Boolean,
)

internal fun maintenancePhotoStepPolicy(
    editor: MaintenanceEditorState,
): MaintenancePhotoStepPolicy = MaintenancePhotoStepPolicy(
    showNext = true,
    targetStep = 3,
    enabled = editor.readOnly ||
        (!maintenanceHasPhotos(editor) && !maintenanceRequiresPhotos(editor)) ||
        (maintenanceHasPhotos(editor) && maintenanceHasCoverPhoto(editor)),
)

internal fun maintenanceDetailsValidationMessage(editor: MaintenanceEditorState): String = when {
    editor.selectedAsset == null -> "Выберите бытовку."
    editor.logisticsTaskPriorityValidationError() != null ->
        requireNotNull(editor.logisticsTaskPriorityValidationError())
    editor.mode == MaintenanceEditorMode.ESTIMATE && !isMaintenanceDate(editor.dispatchDate) ->
        "Укажите дату осмотра в формате ГГГГ-ММ-ДД."
    editor.repairKind == "REWORK" &&
        editor.entityId == null &&
        editor.reworkReason.trim().isEmpty() -> "Укажите причину доработки."
    else -> "Проверьте основные данные."
}

private fun maintenanceSubmitValidationMessage(editor: MaintenanceEditorState): String = when {
    !maintenanceDetailsAreValid(editor) -> maintenanceDetailsValidationMessage(editor)
    maintenanceRequiresPhotos(editor) && !maintenanceHasPhotos(editor) ->
        "Добавьте хотя бы одну фотографию."
    maintenanceHasPhotos(editor) && !maintenanceHasCoverPhoto(editor) ->
        "Выберите титульную фотографию."
    !isEmptyMaintenanceOutcome(editor) && editor.lines.any { !maintenanceLineIsValid(it) } ->
        "Проверьте количество и цену в строках."
    editor.logisticsTaskPriorityValidationError() != null ->
        requireNotNull(editor.logisticsTaskPriorityValidationError())
    editor.logisticsPlanningValidationError() != null ->
        requireNotNull(editor.logisticsPlanningValidationError())
    else -> "Проверьте данные перед сохранением."
}

internal fun maintenanceLineIsValid(line: MaintenanceLineEditorState): Boolean =
    line.lineType in setOf("WORK", "MATERIAL") &&
        maintenanceQuantity(line.quantity)?.let { it > BigDecimal.ZERO } == true &&
        maintenancePrice(line.unitPrice) != null &&
        (line.lineType != "MATERIAL" || line.normativeMinutes == 0) &&
        (line.catalogNodeId != null ||
            (line.unit.isNotBlank() &&
                line.customRouting != null &&
                (line.lineType == "MATERIAL" || line.normativeMinutes in 1..525_600)))

private fun maintenanceLineTotal(line: MaintenanceLineEditorState): BigDecimal? {
    val quantity = maintenanceQuantity(line.quantity) ?: return null
    val price = maintenancePrice(line.unitPrice) ?: return null
    return quantity.multiply(price)
}

internal fun maintenanceTotal(lines: List<MaintenanceLineEditorState>): BigDecimal? {
    if (lines.isEmpty()) return BigDecimal.ZERO
    return lines.fold(BigDecimal.ZERO) { total, line ->
        val lineTotal = maintenanceLineTotal(line) ?: return null
        total.add(lineTotal)
    }
}

private fun maintenanceQuantity(value: String): BigDecimal? {
    if (!quantityPattern.matches(value.trim())) return null
    return value.trim().replace(',', '.').toBigDecimalOrNull()
}

private fun maintenancePrice(value: String): BigDecimal? {
    if (!pricePattern.matches(value.trim())) return null
    return value.trim().replace(',', '.').toBigDecimalOrNull()
}

private fun isMaintenanceDate(value: String): Boolean = runCatching {
    LocalDate.parse(value.trim())
}.isSuccess

internal fun formatMaintenanceMoney(value: BigDecimal): String =
    "${value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')} ₽"

private fun catalogTypeLabel(value: String): String = when (value) {
    "WORK" -> "Работа"
    "MATERIAL" -> "Материал"
    else -> value
}

private fun estimateLifecycleLabel(value: String): String = when (value) {
    "DRAFT" -> "Черновик"
    "IN_PROGRESS" -> "В работе"
    "COMPLETED" -> "Завершена"
    else -> value
}

private fun repairExecutionStateLabel(value: String): String = when (value) {
    "DRAFT" -> "Черновик"
    "QUEUED" -> "Ожидает"
    "IN_PROGRESS" -> "В работе"
    "COMPLETED" -> "Завершён"
    "CANCELLED" -> "Отменён"
    else -> value
}

private val quantityPattern = Regex("^(?:0|[1-9][0-9]*)(?:[,.][0-9]{1,3})?$")
private val pricePattern = Regex("^(?:0|[1-9][0-9]*)(?:[,.][0-9]{1,2})?$")
