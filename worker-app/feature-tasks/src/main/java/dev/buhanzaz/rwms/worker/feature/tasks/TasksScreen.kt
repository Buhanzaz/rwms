package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.buhanzaz.rwms.worker.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.worker.core.ui.WorkerKpiColorRange
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay
import dev.buhanzaz.rwms.worker.core.ui.workerRepairComplexityLabel
import dev.buhanzaz.rwms.worker.core.ui.workerKpiTimeColor
import dev.buhanzaz.rwms.worker.core.ui.workerTaskStageOrdinal
import dev.buhanzaz.rwms.worker.core.ui.workerTaskStageLabel
import kotlinx.coroutines.delay

/** Renders the worker task board from the locally synchronized projection. */
@Composable
fun TasksScreen(
    userId: String,
    onTask: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    profileMonogram: String? = null,
    onProfile: (() -> Unit)? = null,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId) { viewModel.bind(userId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hasFreshSyncProgress = rememberFreshSyncProgress(
        stage = state.progress?.stage,
        updatedAtEpochMillis = state.progress?.updatedAtEpochMillis,
    )
    val refreshInProgress = state.online && hasFreshSyncProgress
    val refreshRotation = remember { Animatable(0f) }
    LaunchedEffect(refreshInProgress) {
        if (!refreshInProgress) {
            refreshRotation.snapTo(0f)
        } else {
            while (true) {
                refreshRotation.snapTo(0f)
                refreshRotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(
                        durationMillis = REFRESH_ROTATION_DURATION_MILLIS,
                        easing = LinearEasing,
                    ),
                )
            }
        }
    }
    WorkerScreenScaffold(
        title = TASK_BOARD_TITLE,
        onBack = onBack,
        onMenu = onMenu,
        profileMonogram = profileMonogram,
        onProfile = onProfile,
        actions = {
            IconButton(
                onClick = viewModel::syncNow,
                modifier = Modifier.testTag("task-board-refresh"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = if (refreshInProgress) {
                        "Синхронизация выполняется"
                    } else {
                        "Синхронизировать"
                    },
                    modifier = Modifier.graphicsLayer { rotationZ = refreshRotation.value },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            taskBoardSyncNotice(
                online = state.online,
                stage = state.progress?.stage,
                message = state.progress?.message,
            )?.let { notice ->
                Text(
                    notice,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    column.name,
                    modifier = Modifier.weight(1f).testTag("work-column-title-${column.id}"),
                    style = MaterialTheme.typography.titleLarge,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть раздел" else "Развернуть раздел",
                    modifier = Modifier.size(36.dp).testTag("work-column-chevron-${column.id}"),
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
                    taskBoardQueueLabel(section.name),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
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
    val isShadow = task.entryType != REAL_ENTRY_TYPE
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("task-card-$presentationKey")
            .alpha(if (isShadow) SHADOW_TASK_ALPHA else 1f)
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    taskCardTitle(task.unitNumber),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TaskStatusChip(task.status)
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть задание" else "Развернуть задание",
                )
            }
            val timer = queueTaskTimerPresentation(task)
            val complexity = workerRepairComplexityLabel(task.title)
            val kpiColor = workerKpiTimeColor(
                remainingPercent = task.timerRemainingPercent,
                ranges = kpiPalette?.ranges.orEmpty().map { range ->
                    WorkerKpiColorRange(range.fromPercent, range.toPercent, range.color)
                },
                overdueColor = kpiPalette?.overdueColor,
            )
            if (isShadow) {
                Text(
                    "Теневая задача · ожидает предыдущего этапа",
                    modifier = Modifier.testTag("task-shadow-${task.entryId}"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.status == "WAITING") {
                Text(
                    "Выделенное время: ${allocatedQueueDurationLabel(task.plannedDurationMinutes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Время работы: ${timer?.elapsed ?: queueTaskElapsedLabel(task)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "KPI: ${timer?.percent ?: "—"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = kpiColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (kpiColor == null) FontWeight.Normal else FontWeight.Bold,
                    )
                }
            }
            complexity?.let {
                Text(it, style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                Text(
                    "Этап ${workerTaskStageOrdinal(task.routeIndex, task.routeStepCount, " из ")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Приоритет ${task.priority}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                task.deadlineAt?.let {
                    Text("Срок: $it", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Загружено фото: ${task.readyEvidenceCount}",
                    style = MaterialTheme.typography.labelMedium,
                )
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

/** Returns the only task-card heading that may represent a cabin-owned task. */
internal fun taskCardTitle(unitNumber: String?): String =
    cabinNumberForDisplay(unitNumber) ?: "Задание"

/** Replaces the backend's technical maintenance queue label in the worker UI. */
internal fun taskBoardQueueLabel(name: String): String =
    workerTaskStageLabel(name) ?: name

/** Formats the server-issued budget without treating it as elapsed work. */
internal fun allocatedQueueDurationLabel(minutes: Int?): String = minutes
    ?.coerceAtLeast(0)
    ?.let { value -> "%d:%02d:00".format(value / 60, value % 60) }
    ?: "—"

/**
 * Reports whether a durable progress row is both an active stage and recent
 * enough to prove that synchronization is still running.
 */
internal fun shouldAnimateTaskBoardRefresh(
    stage: String?,
    updatedAtEpochMillis: Long?,
    nowEpochMillis: Long,
): Boolean {
    if (stage !in ACTIVE_SYNC_STAGES || updatedAtEpochMillis == null) return false
    val ageMillis = nowEpochMillis - updatedAtEpochMillis
    return ageMillis in 0..SYNC_PROGRESS_FRESHNESS_MILLIS
}

/** Keeps durable offline or blocked-sync truth without exposing progress chatter. */
internal fun taskBoardSyncNotice(online: Boolean, stage: String?, message: String?): String? =
    when {
        !online -> "Нет связи с RWMS"
        stage == WAITING_FOR_EVIDENCE_STAGE ->
            message?.trim()?.takeIf(String::isNotEmpty) ?: "Синхронизация требует внимания"
        else -> null
    }

@Composable
private fun rememberFreshSyncProgress(stage: String?, updatedAtEpochMillis: Long?): Boolean {
    var nowEpochMillis by remember(stage, updatedAtEpochMillis) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(stage, updatedAtEpochMillis) {
        nowEpochMillis = System.currentTimeMillis()
        val expiresAt = updatedAtEpochMillis?.plus(SYNC_PROGRESS_FRESHNESS_MILLIS)
            ?: return@LaunchedEffect
        val remainingMillis = expiresAt - nowEpochMillis
        if (remainingMillis >= 0) {
            delay(remainingMillis + 1)
            nowEpochMillis = System.currentTimeMillis()
        }
    }
    return shouldAnimateTaskBoardRefresh(stage, updatedAtEpochMillis, nowEpochMillis)
}

internal const val TASK_BOARD_TITLE = "Доска задач"
private val TWO_COLUMN_MIN_WIDTH = 720.dp
private val BOARD_GAP = 12.dp
private val BOARD_HORIZONTAL_PADDING = 12.dp
private const val REFRESH_ROTATION_DURATION_MILLIS = 900
private const val SYNC_PROGRESS_FRESHNESS_MILLIS = 60_000L
private const val WAITING_FOR_EVIDENCE_STAGE = "WAITING_FOR_EVIDENCE"
private val ACTIVE_SYNC_STAGES = setOf("CONTEXT", "COMMANDS", "EVIDENCE", "UPLOAD")
private const val REAL_ENTRY_TYPE = "REAL"
private const val SHADOW_TASK_ALPHA = 0.62f
