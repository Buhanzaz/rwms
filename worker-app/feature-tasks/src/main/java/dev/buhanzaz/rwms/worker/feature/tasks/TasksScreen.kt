package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerKpiPaletteDto
import dev.buhanzaz.rwms.worker.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerKpiColorRange
import dev.buhanzaz.rwms.worker.core.ui.WorkerStoreNavy
import dev.buhanzaz.rwms.worker.core.ui.isInterwarehouseTransferTask
import dev.buhanzaz.rwms.worker.core.ui.workerKpiTimeColor
import dev.buhanzaz.rwms.worker.core.ui.workerRepairComplexityLabel
import dev.buhanzaz.rwms.worker.core.ui.workerTaskStageOrdinal

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
    val problemColor = workerTaskProblemColor(kpiPalette?.problemColor)

    Card(
        modifier = modifier.testTag("single-task-card-${task.entryId}"),
        colors = if (task.hasProblem) {
            CardDefaults.cardColors(containerColor = problemColor)
        } else {
            translucentCardColors()
        },
        border = if (task.hasProblem) BorderStroke(2.dp, problemColor) else translucentCardBorder(),
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
            if (task.hasProblem) {
                Text(
                    text = if (task.incomplete) {
                        "Незавершено: ${task.completedWorkPercent}% работ выполнено"
                    } else {
                        "В задании отмечена проблема"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("task-problem-${task.entryId}"),
                )
            }
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

internal fun workerTaskProblemColor(value: String?): Color = runCatching {
    Color(android.graphics.Color.parseColor(value ?: "#FF3B30"))
}.getOrDefault(Color(0xFFFF3B30))

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

internal const val CURRENT_TASK_TITLE = "Моё задание"
private val TASK_CARD_MAX_WIDTH = 620.dp
private val SLINGER_DIALOG_MAX_WIDTH = 560.dp
private const val SYNC_PROGRESS_FRESHNESS_MILLIS = 60_000L
private const val WAITING_FOR_EVIDENCE_STAGE = "WAITING_FOR_EVIDENCE"
private val ACTIVE_SYNC_STAGES = setOf("CONTEXT", "COMMANDS", "EVIDENCE", "UPLOAD")
