package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerKpiPaletteDto
import dev.buhanzaz.rwms.worker.core.ui.SyncStatusBanner
import dev.buhanzaz.rwms.worker.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.worker.core.ui.WorkerKpiColorRange
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay
import dev.buhanzaz.rwms.worker.core.ui.workerKpiTimeColor

/** Renders the worker task board from the locally synchronized projection. */
@Composable
fun TasksScreen(
    userId: String,
    onTask: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId) { viewModel.bind(userId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WorkerScreenScaffold(
        title = "Работы",
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::syncNow) {
                Icon(Icons.Filled.Refresh, contentDescription = "Синхронизировать")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SyncStatusBanner(
                online = state.online,
                stage = state.progress?.stage,
                completed = state.progress?.completedUnits ?: 0,
                total = state.progress?.totalUnits ?: 0,
                message = state.progress?.message,
            )
            Text(
                when {
                    state.groups.isNotEmpty() -> "Группы: ${state.groups.joinToString { it.name }}"
                    state.categories.any { it.groupIds().isEmpty() } -> "Доступны личные задания"
                    else -> "Группы не назначены руководителем"
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.session?.operationalAvailability == "DISABLED") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (state.session?.operationalAvailability == "DISABLED") {
                Text(
                    "Рабочий временно недоступен — новые задания взять нельзя",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.conflicts.isNotEmpty()) {
                Text(
                    "Есть конфликты синхронизации: ${state.conflicts.size}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val columns = buildWorkBoardColumns(
                groups = state.groups,
                categories = state.categories,
                tasks = state.tasks,
                assignments = state.assignments,
            )
            if (columns.isEmpty()) {
                Text(
                    "Нет назначенных работ. Обновите данные после изменения доски задач.",
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                WorkBoardColumns(
                    columns = columns,
                    kpiPalette = state.kpiPalette,
                    onTask = onTask,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Lays out independently collapsible worker-role panels for the available width. */
@Composable
internal fun WorkBoardColumns(
    columns: List<WorkBoardColumn>,
    kpiPalette: WorkerKpiPaletteDto?,
    onTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val showTwoColumns = maxWidth >= TWO_COLUMN_MIN_WIDTH && columns.size > 1
        if (showTwoColumns) {
            val columnWidth = (maxWidth - (BOARD_HORIZONTAL_PADDING * 2) - BOARD_GAP) / 2
            LazyRow(
                modifier = Modifier.fillMaxSize().testTag("work-board-wide"),
                contentPadding = PaddingValues(horizontal = BOARD_HORIZONTAL_PADDING),
                horizontalArrangement = Arrangement.spacedBy(BOARD_GAP),
            ) {
                items(columns, key = WorkBoardColumn::id) { column ->
                    WorkBoardColumnPane(
                        column = column,
                        kpiPalette = kpiPalette,
                        onTask = onTask,
                        independentlyScrollable = true,
                        modifier = Modifier.width(columnWidth).fillParentMaxHeight()
                            .testTag("work-column-${column.id}"),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("work-board-narrow"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(columns, key = WorkBoardColumn::id) { column ->
                    WorkBoardColumnPane(
                        column = column,
                        kpiPalette = kpiPalette,
                        onTask = onTask,
                        independentlyScrollable = false,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                            .testTag("work-column-${column.id}"),
                    )
                }
            }
        }
    }
}

/** Renders one ordered task queue with its empty and loading states. */
@Composable
internal fun TaskQueueList(
    sections: List<TaskQueueSection>,
    onTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(sections, key = TaskQueueSection::queueId) { section ->
            TaskQueueSectionPanel(
                section = section,
                kpiPalette = null,
                onTask = onTask,
                presentationKey = section.queueId,
            )
        }
    }
}

/**
 * Renders one worker-role panel. A personal panel uses the authorized category
 * name because the local projection does not provide its qualification name.
 */
@Composable
private fun WorkBoardColumnPane(
    column: WorkBoardColumn,
    kpiPalette: WorkerKpiPaletteDto?,
    onTask: (String) -> Unit,
    independentlyScrollable: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(column.id) { mutableStateOf(true) }
    Card(modifier = modifier.animateContentSize()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .testTag("work-column-toggle-${column.id}")
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = { expanded = it },
                    )
                    .semantics {
                        stateDescription = if (expanded) "Развернуто" else "Свернуто"
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(column.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        column.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    column.sections.sumOf { it.tasks.size }.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть роль" else "Развернуть роль",
                )
            }
            if (expanded && independentlyScrollable) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(column.sections, key = TaskQueueSection::queueId) { section ->
                        TaskQueueSectionPanel(
                            section = section,
                            kpiPalette = kpiPalette,
                            onTask = onTask,
                            presentationKey = "${column.id}-${section.queueId}",
                        )
                    }
                }
            } else if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    column.sections.forEach { section ->
                        TaskQueueSectionPanel(
                            section = section,
                            kpiPalette = kpiPalette,
                            onTask = onTask,
                            presentationKey = "${column.id}-${section.queueId}",
                        )
                    }
                }
            }
        }
    }
}

/** Renders a collapsible server-authorized queue without changing task membership. */
@Composable
private fun TaskQueueSectionPanel(
    section: TaskQueueSection,
    kpiPalette: WorkerKpiPaletteDto?,
    onTask: (String) -> Unit,
    presentationKey: String,
) {
    var expanded by rememberSaveable(presentationKey) { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .testTag("queue-section-$presentationKey")
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .testTag("queue-section-toggle-$presentationKey")
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = { expanded = it },
                    )
                    .semantics {
                        stateDescription = if (expanded) "Развернуто" else "Свернуто"
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    section.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    section.tasks.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть очередь" else "Развернуть очередь",
                )
            }
            if (expanded && section.tasks.isEmpty()) {
                Text(
                    "В этой очереди пока нет заданий",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                section.tasks.forEach { task ->
                    TaskRow(
                        task = task,
                        kpiPalette = kpiPalette,
                        onOpen = { onTask(task.entryId) },
                        presentationKey = "$presentationKey-${task.entryId}",
                    )
                }
            }
        }
    }
}

/** Renders one expandable task summary and preserves navigation to photo-rich details. */
@Composable
private fun TaskRow(
    task: WorkerTaskEntity,
    kpiPalette: WorkerKpiPaletteDto?,
    onOpen: () -> Unit,
    presentationKey: String,
) {
    var expanded by rememberSaveable(presentationKey) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("task-card-$presentationKey")
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .testTag("task-card-toggle-$presentationKey")
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = { expanded = it },
                    )
                    .semantics {
                        stateDescription = if (expanded) "Развернуто" else "Свернуто"
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    task.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TaskStatusChip(task.status)
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть задание" else "Развернуть задание",
                )
            }
            taskCabinCaption(task.unitNumber)?.let { caption -> Text(caption) }
            Text("Дата: ${task.scheduledDate}", style = MaterialTheme.typography.bodyMedium)
            val timer = queueTaskTimerPresentation(task)
            val elapsed = timer?.elapsed ?: queueTaskElapsedLabel(task)
            elapsed?.let {
                Text(
                    "Время работы: $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                task.taskText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                task.deadlineAt?.let {
                    Text("Срок: $it", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "${task.categoryName} · очередь ${task.queuePosition + 1} · " +
                        "фото ${task.readyEvidenceCount}/${task.resultPhotoMinCount}",
                    style = MaterialTheme.typography.labelMedium,
                )
                timer?.let {
                    val kpiColor = workerKpiTimeColor(
                        remainingPercent = task.timerRemainingPercent,
                        ranges = kpiPalette?.ranges.orEmpty().map { range ->
                            WorkerKpiColorRange(
                                range.fromPercent,
                                range.toPercent,
                                range.color,
                            )
                        },
                        overdueColor = kpiPalette?.overdueColor,
                    )
                    Text(
                        listOfNotNull(
                            it.state,
                            it.remaining?.let { remaining -> "осталось $remaining" },
                            it.percent,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = kpiColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (kpiColor == null) FontWeight.Normal else FontWeight.Bold,
                    )
                }
            }
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().testTag("task-open-${task.entryId}"),
            ) {
                Text("Открыть задание")
            }
        }
    }
}

/**
 * Uses a plural caption for the grouped shipment summary supplied by logistics
 * while keeping individual cabin numbers in the familiar singular form.
 */
private fun taskCabinCaption(unitNumber: String?): String? {
    val unit = cabinNumberForDisplay(unitNumber) ?: return null
    val groupedCabinCount = GROUPED_CABIN_SUMMARY.matchEntire(unit)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it > 1 }
    return groupedCabinCount?.let { "Бытовки: $it" } ?: "Бытовка: $unit"
}

private val TWO_COLUMN_MIN_WIDTH = 720.dp
private val BOARD_GAP = 12.dp
private val BOARD_HORIZONTAL_PADDING = 12.dp
private val GROUPED_CABIN_SUMMARY = Regex(
    pattern = "^(\\d+)\\s+бытов(?:ка|ки|ок)$",
    option = RegexOption.IGNORE_CASE,
)
