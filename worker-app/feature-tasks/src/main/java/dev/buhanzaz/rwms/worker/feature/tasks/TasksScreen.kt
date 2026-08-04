package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
                    LazyColumn(
                        modifier = Modifier.width(columnWidth).fillParentMaxHeight()
                            .testTag("work-column-${column.id}"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        workBoardColumnItems(column, kpiPalette, onTask)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("work-board-narrow"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                columns.forEach { column ->
                    workBoardColumnItems(column, kpiPalette, onTask)
                }
            }
        }
    }
}

@Composable
internal fun TaskQueueList(
    sections: List<TaskQueueSection>,
    onTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        workBoardColumnItems(
            WorkBoardColumn("queues", "Работы", personal = false, sections = sections),
            kpiPalette = null,
            onTask = onTask,
            showColumnHeader = false,
        )
    }
}

private fun LazyListScope.workBoardColumnItems(
    column: WorkBoardColumn,
    kpiPalette: WorkerKpiPaletteDto?,
    onTask: (String) -> Unit,
    showColumnHeader: Boolean = true,
) {
    val keyPrefix = "${column.id}-"
    if (showColumnHeader) {
        item(key = "${keyPrefix}header") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(column.name, style = MaterialTheme.typography.titleLarge)
                if (column.personal) {
                    Text(
                        "Квалификационные и логистические задания без группы",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    column.sections.forEach { section ->
        item(key = "${keyPrefix}category-${section.queueId}") {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(section.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (section.queuePurpose == "LOGISTICS_DRIVER") {
                    Text(
                        "Логистика",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    section.tasks.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (section.tasks.isEmpty()) {
            item(key = "${keyPrefix}category-${section.queueId}-empty") {
                Text(
                    "В этой очереди пока нет заданий",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(section.tasks, key = { "$keyPrefix${it.localId}" }) { task ->
            TaskRow(task, kpiPalette, onClick = { onTask(task.entryId) })
        }
    }
}

@Composable
private fun TaskRow(
    task: WorkerTaskEntity,
    kpiPalette: WorkerKpiPaletteDto?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TaskStatusChip(task.status)
            }
            cabinNumberForDisplay(task.unitNumber)?.let { Text("Бытовка: $it") }
            task.taskText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "${task.categoryName} · очередь ${task.queuePosition + 1} · фото ${task.readyEvidenceCount}/${task.resultPhotoMinCount}",
                style = MaterialTheme.typography.labelMedium,
            )
            queueTaskTimerPresentation(task)?.let { timer ->
                val kpiColor = workerKpiTimeColor(
                    remainingPercent = task.timerRemainingPercent,
                    ranges = kpiPalette?.ranges.orEmpty().map {
                        WorkerKpiColorRange(it.fromPercent, it.toPercent, it.color)
                    },
                    overdueColor = kpiPalette?.overdueColor,
                )
                Text(
                    listOfNotNull(
                        timer.state,
                        timer.remaining?.let { "осталось $it" },
                        timer.percent,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = kpiColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (kpiColor == null) FontWeight.Normal else FontWeight.Bold,
                )
            }
        }
    }
}

private val TWO_COLUMN_MIN_WIDTH = 720.dp
private val BOARD_GAP = 12.dp
private val BOARD_HORIZONTAL_PADDING = 12.dp
