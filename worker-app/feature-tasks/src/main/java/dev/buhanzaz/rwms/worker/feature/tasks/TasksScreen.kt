package dev.buhanzaz.rwms.worker.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.ui.SyncStatusBanner
import dev.buhanzaz.rwms.worker.core.ui.TaskStatusChip
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay

@Composable
fun TasksScreen(
    userId: String,
    onTask: (String) -> Unit,
    onProfile: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId) { viewModel.bind(userId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WorkerScreenScaffold(
        title = state.session?.displayName ?: "Мои задания",
        actions = {
            IconButton(onClick = viewModel::syncNow) {
                Icon(Icons.Filled.Refresh, contentDescription = "Синхронизировать")
            }
            IconButton(onClick = onProfile) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "Профиль")
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
                state.session?.currentGroupName?.let { "Текущая группа: $it" }
                    ?: "Текущая группа не выбрана руководителем",
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
                    "Группа временно недоступна — новые задания взять нельзя",
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
            val sections = buildTaskQueueSections(state.categories, state.tasks)
            if (sections.isEmpty()) {
                Text(
                    "Нет назначенных очередей. Обновите данные после изменения настроек доски.",
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                TaskQueueList(
                    sections = sections,
                    onTask = onTask,
                    modifier = Modifier.fillMaxSize(),
                )
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
        sections.forEach { section ->
            item(key = "category-${section.queueId}") {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
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
                }
            }
            if (section.tasks.isEmpty()) {
                item(key = "category-${section.queueId}-empty") {
                    Text(
                        "В этой очереди пока нет заданий",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(section.tasks, key = { it.localId }) { task ->
                TaskRow(task = task, onClick = { onTask(task.entryId) })
            }
        }
    }
}

@Composable
private fun TaskRow(task: WorkerTaskEntity, onClick: () -> Unit) {
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
                Text(
                    listOfNotNull(
                        timer.state,
                        timer.remaining?.let { "осталось $it" },
                        timer.percent,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
