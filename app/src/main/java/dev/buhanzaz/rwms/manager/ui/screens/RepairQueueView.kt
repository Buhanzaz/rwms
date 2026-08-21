package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import dev.buhanzaz.rwms.manager.ui.RepairQueueItem
import dev.buhanzaz.rwms.manager.ui.RepairQueueSection
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.createRepairQueueItems
import dev.buhanzaz.rwms.manager.ui.repairQueueLineGroups
import dev.buhanzaz.rwms.manager.ui.repairQueueSections

/**
 * Renders the aggregate ordinary repair board as a compact, vertically scrolling queue list.
 * Dates, drag gestures, and client-owned reorder commands are intentionally absent.
 */
@Composable
fun RepairQueueView(
    board: TaskBoardSnapshotDto?,
    repairs: List<RepairDto>,
    assetLabels: Map<String, String>,
    busy: Boolean,
    onOpenRepair: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val queueItems = remember(board, repairs) { createRepairQueueItems(board, repairs) }
    val sections = remember(board, queueItems) { repairQueueSections(board, queueItems) }
    var infoItem by remember { mutableStateOf<RepairQueueItem?>(null) }

    if (sections.isEmpty()) {
        EmptyState(
            title = "Очереди пока пусты",
            description = "После постановки обычного ремонта здесь появятся задания по очередям.",
            modifier = modifier.fillMaxSize(),
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val horizontalPadding = if (maxWidth >= 600.dp) 24.dp else 12.dp
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxSize()
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                sections.forEach { section ->
                    item(key = "queue:${section.queue.queueId}") {
                        RepairQueueSectionHeader(section)
                    }
                    items(section.items, key = RepairQueueItem::id) { item ->
                        RepairQueueCard(
                            item = item,
                            cabinNumber = item.entry.unitNumber
                                ?: assetLabels[item.repair.rentalItemId]
                                ?: "без номера",
                            enabled = !busy,
                            onInfo = { infoItem = item },
                        )
                    }
                }
            }
        }
    }

    infoItem?.let { item ->
        RepairQueueInfoDialog(
            queueItem = item,
            cabinNumber = item.entry.unitNumber
                ?: assetLabels[item.repair.rentalItemId]
                ?: "без номера",
            enabled = !busy,
            onDismiss = { infoItem = null },
            onOpenRepair = {
                infoItem = null
                onOpenRepair(item.repair.id)
            },
        )
    }
}

@Composable
private fun RepairQueueSectionHeader(section: RepairQueueSection) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(section.queue.queueName, style = MaterialTheme.typography.titleMedium)
            Text(
                repairQueueTypeLabel(section.queue.queueType),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                section.items.size.toString(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun RepairQueueCard(
    item: RepairQueueItem,
    cabinNumber: String,
    enabled: Boolean,
    onInfo: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = MaterialTheme.shapes.medium,
        color = when (item.entry.status) {
            "IN_PROGRESS" -> MaterialTheme.colorScheme.primaryContainer
            "PAUSED" -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.entry.taskText ?: item.entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Text(
                    "Бытовка $cabinNumber · P${item.entry.priority}" +
                        if (item.entry.pinned) " · закреплено" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    repairQueueStatusLabel(item.entry.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Surface(
                onClick = onInfo,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = "Подробнее о задании бытовки $cabinNumber"
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("i", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun RepairQueueInfoDialog(
    queueItem: RepairQueueItem,
    cabinNumber: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onOpenRepair: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Задание бытовки $cabinNumber",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                item {
                    QueueInfoRow(
                        "Задание",
                        queueItem.entry.taskText ?: queueItem.entry.title,
                    )
                }
                item { QueueInfoRow("Приоритет", queueItem.entry.priority.toString()) }
                item { QueueInfoRow("Статус", repairQueueStatusLabel(queueItem.entry.status)) }
                item { QueueInfoRow("Позиция", (queueItem.entry.queuePosition + 1).toString()) }
                item { QueueInfoRow("Очередь", queueItem.queue.queueName) }
                item {
                    QueueInfoRow(
                        "Тип",
                        if (queueItem.repair.kind == "REWORK") "Доработка" else "Ремонт",
                    )
                }
                val lineGroups = queueItem.stage.repairQueueLineGroups()
                item { QueueInfoPills("Работы", lineGroups.works) }
                item { QueueInfoPills("Материалы", lineGroups.materials) }
                item {
                    Button(
                        onClick = onOpenRepair,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                    ) {
                        Text("Открыть ремонт")
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun QueueInfoPills(label: String, values: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (values.isEmpty()) {
            Text(
                "Не указаны",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            values.forEach { value ->
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        value,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun repairQueueStatusLabel(value: String): String = when (value) {
    "WAITING" -> "Ожидает"
    "IN_PROGRESS" -> "В работе"
    "PAUSED" -> "На паузе"
    "DONE" -> "Завершено"
    "CANCELLED" -> "Отменено"
    else -> value
}

private fun repairQueueTypeLabel(value: String): String = when (value) {
    "REPAIR" -> "Работы"
    "HOLDING" -> "Ожидание"
    "MOVEMENT" -> "Перемещение"
    "FURNITURE_MOVEMENT" -> "Перемещение мебели"
    else -> value
}
