package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerKpiPaletteDto
import dev.buhanzaz.rwms.worker.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerKpiColorRange
import dev.buhanzaz.rwms.worker.core.ui.WorkerOutlinedButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.WorkerStoreNavy
import dev.buhanzaz.rwms.worker.core.ui.isInterwarehouseTransferTask
import dev.buhanzaz.rwms.worker.core.ui.workerKpiTimeColor
import dev.buhanzaz.rwms.worker.core.ui.workerRepairComplexityLabel
import dev.buhanzaz.rwms.worker.core.ui.workerTaskStageOrdinal
import kotlinx.coroutines.delay

/** Renders exactly one server-authorized current or next task. */
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
    val task = selectCurrentWorkerTask(
        userId = userId,
        currentGroupId = state.session?.currentGroupId,
        categories = state.categories,
        tasks = state.tasks,
        assignments = state.assignments,
    )

    WorkerScreenScaffold(
        title = CURRENT_TASK_TITLE,
        onBack = onBack,
        onMenu = onMenu,
        profileMonogram = profileMonogram,
        onProfile = onProfile,
        actions = {
            IconButton(
                onClick = viewModel::syncNow,
                modifier = Modifier.testTag("current-task-refresh"),
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("single-task-screen"),
            contentPadding = PaddingValues(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            taskSyncNotice(
                online = state.online,
                stage = state.progress?.stage,
                message = state.progress?.message,
            )?.let { notice ->
                item(key = "sync-notice") {
                    NoticeCard(notice, error = true)
                }
            }
            if (state.session?.operationalAvailability == "DISABLED") {
                item(key = "availability-notice") {
                    NoticeCard("Рабочий временно недоступен — новое задание взять нельзя", error = true)
                }
            }
            if (state.conflicts.isNotEmpty()) {
                item(key = "conflict-notice") {
                    Card(
                        modifier = Modifier.widthIn(max = TASK_CARD_MAX_WIDTH).fillMaxWidth(),
                        colors = translucentCardColors(),
                        border = translucentCardBorder(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Есть конфликты синхронизации: ${state.conflicts.size}",
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                state.conflicts.first().message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            WorkerOutlinedButton(
                                onClick = viewModel::acknowledgeConflicts,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("current-task-conflict-acknowledge"),
                            ) {
                                Text("Принять состояние RWMS и обновить")
                            }
                        }
                    }
                }
            }
            item(key = task?.entryId ?: "empty-task") {
                if (task == null) {
                    EmptyTaskCard()
                } else {
                    SingleTaskCard(
                        task = task,
                        kpiPalette = state.kpiPalette,
                        onOpen = { onTask(task.entryId) },
                        modifier = Modifier.widthIn(max = TASK_CARD_MAX_WIDTH).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Non-dismissible priority surface shown above any current WorkerApp route. */
@Composable
fun SlingerTaskInterruptionDialog(
    task: WorkerTaskEntity,
    currentTaskVisible: Boolean,
    onTake: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = SLINGER_DIALOG_MAX_WIDTH)
                    .fillMaxWidth()
                    .testTag("slinger-task-dialog"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                ),
                border = translucentCardBorder(),
                elevation = CardDefaults.cardElevation(defaultElevation = 18.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        color = WorkerStoreNavy.copy(alpha = 0.88f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "Задание стропальщика",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "Приоритетное задание",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = taskCardTitle(task.unitNumber),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = taskQueueLabel(task.categoryName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TaskStatusChip(task.status)
                        Text(
                            text = "Приоритет ${task.priority}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        text = if (currentTaskVisible) {
                            "После принятия текущее задание будет приостановлено для всей бригады и автоматически продолжится после этой работы."
                        } else {
                            "После принятия это задание станет текущим до завершения работы стропальщика."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    WorkerButton(
                        onClick = onTake,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("slinger-task-take"),
                    ) {
                        Text(
                            text = "Взять задание",
                            fontSize = MaterialTheme.typography.titleMedium.fontSize,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** The one lightly translucent task card used by the worker home screen. */
@Composable
internal fun SingleTaskCard(
    task: WorkerTaskEntity,
    kpiPalette: WorkerKpiPaletteDto?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timer = queueTaskTimerPresentation(task)
    val complexity = workerRepairComplexityLabel(task.title)
    val kpiColor = workerKpiTimeColor(
        remainingPercent = task.timerRemainingPercent,
        ranges = kpiPalette?.ranges.orEmpty().map { range ->
            WorkerKpiColorRange(range.fromPercent, range.toPercent, range.color)
        },
        overdueColor = kpiPalette?.overdueColor,
    )
    val isTransfer = isInterwarehouseTransferTask(task.title, task.taskText)

    Card(
        modifier = modifier.testTag("single-task-card-${task.entryId}"),
        colors = translucentCardColors(),
        border = translucentCardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = taskCardTitle(task.unitNumber),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TaskStatusChip(task.status)
            }
            Text(
                text = taskQueueLabel(task.categoryName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isTransfer) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag("single-task-transfer-${task.entryId}"),
                ) {
                    Text(
                        text = "Межскладское перемещение",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (task.status == "WAITING") {
                Text(
                    text = "Выделенное время: ${allocatedQueueDurationLabel(task.plannedDurationMinutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Время работы: ${timer?.elapsed ?: queueTaskElapsedLabel(task)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "KPI: ${timer?.percent ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = kpiColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (kpiColor == null) FontWeight.Normal else FontWeight.Bold,
                    )
                }
            }
            complexity?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "Этап ${workerTaskStageOrdinal(task.routeStepIndex, task.routeStepCount, " из ")}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Приоритет ${task.priority}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Фото: ${task.readyEvidenceCount}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            WorkerButton(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("task-open-${task.entryId}"),
            ) {
                Text(
                    text = taskOpenActionLabel(task.status),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun EmptyTaskCard() {
    Card(
        modifier = Modifier
            .widthIn(max = TASK_CARD_MAX_WIDTH)
            .fillMaxWidth()
            .testTag("single-task-empty"),
        colors = translucentCardColors(),
        border = translucentCardBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Сейчас нет задания",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Новое задание появится здесь автоматически",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeCard(message: String, error: Boolean) {
    Card(
        modifier = Modifier.widthIn(max = TASK_CARD_MAX_WIDTH).fillMaxWidth(),
        colors = translucentCardColors(),
        border = translucentCardBorder(),
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun taskOpenActionLabel(status: String): String = when (status) {
    "WAITING" -> "Открыть и взять"
    "PAUSED" -> "Открыть задание"
    else -> "Продолжить выполнение"
}

@Composable
private fun translucentCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
)

@Composable
private fun translucentCardBorder() = BorderStroke(
    width = 1.dp,
    color = Color.White.copy(alpha = 0.58f),
)

/** Reports whether a durable progress row proves that synchronization is still running. */
internal fun shouldAnimateTaskRefresh(
    stage: String?,
    updatedAtEpochMillis: Long?,
    nowEpochMillis: Long,
): Boolean {
    if (stage !in ACTIVE_SYNC_STAGES || updatedAtEpochMillis == null) return false
    val ageMillis = nowEpochMillis - updatedAtEpochMillis
    return ageMillis in 0..SYNC_PROGRESS_FRESHNESS_MILLIS
}

/** Keeps durable offline or blocked-sync truth without exposing progress chatter. */
internal fun taskSyncNotice(online: Boolean, stage: String?, message: String?): String? =
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
    return shouldAnimateTaskRefresh(stage, updatedAtEpochMillis, nowEpochMillis)
}

internal const val CURRENT_TASK_TITLE = "Моё задание"
private val TASK_CARD_MAX_WIDTH = 620.dp
private val SLINGER_DIALOG_MAX_WIDTH = 560.dp
private const val REFRESH_ROTATION_DURATION_MILLIS = 900
private const val SYNC_PROGRESS_FRESHNESS_MILLIS = 60_000L
private const val WAITING_FOR_EVIDENCE_STAGE = "WAITING_FOR_EVIDENCE"
private val ACTIVE_SYNC_STAGES = setOf("CONTEXT", "COMMANDS", "EVIDENCE", "UPLOAD")
