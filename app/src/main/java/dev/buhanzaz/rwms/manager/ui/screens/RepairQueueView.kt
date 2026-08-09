package dev.buhanzaz.rwms.manager.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import dev.buhanzaz.rwms.manager.ui.RepairQueueItem
import dev.buhanzaz.rwms.manager.ui.createRepairQueueItems
import dev.buhanzaz.rwms.manager.ui.normalizedRepairQueueTargetIndex
import dev.buhanzaz.rwms.manager.ui.reconcileRepairQueueDateColumnOrder
import dev.buhanzaz.rwms.manager.ui.repairQueueDateAtHorizontalPosition
import dev.buhanzaz.rwms.manager.ui.RepairQueueDateColumnRange
import dev.buhanzaz.rwms.manager.ui.repairQueueDates
import dev.buhanzaz.rwms.manager.ui.repairQueueEndTargetIndex
import dev.buhanzaz.rwms.manager.ui.repairQueueEntryCanMove
import dev.buhanzaz.rwms.manager.ui.repairQueueLineGroups
import dev.buhanzaz.rwms.manager.ui.swapRepairQueueDateColumns
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class ActiveRepairDrag(
    val item: RepairQueueItem,
    val offset: Offset = Offset.Zero,
)

private data class ActiveRepairColumnDrag(
    val date: String,
    val offset: Offset = Offset.Zero,
)

/** Renders the manager repair queue, including its date columns and fenced drag commands. */
@Composable
fun RepairQueueView(
    boards: List<TaskBoardSnapshotDto>,
    repairs: List<RepairDto>,
    assetLabels: Map<String, String>,
    busy: Boolean,
    onMove: (RepairQueueItem, String, Int) -> Unit,
    onSwapDateColumns: (String, String, () -> Unit) -> Unit,
    onOpenRepair: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val queueItems = remember(boards, repairs) {
        createRepairQueueItems(boards, repairs)
    }
    val dates = remember(boards, queueItems) { repairQueueDates(boards, queueItems) }
    var orderedDates by remember { mutableStateOf(emptyList<String>()) }
    val displayedDates = remember(dates, orderedDates) {
        reconcileRepairQueueDateColumnOrder(orderedDates, dates)
    }
    LaunchedEffect(dates) {
        orderedDates = reconcileRepairQueueDateColumnOrder(orderedDates, dates)
    }
    val itemsByDate = remember(queueItems) {
        queueItems
            .groupBy(RepairQueueItem::date)
            .mapValues { (_, items) ->
                items.sortedWith(
                    compareBy<RepairQueueItem>(RepairQueueItem::date)
                        .thenBy { item -> item.entry.queuePosition }
                        .thenBy(RepairQueueItem::boardOrder)
                        .thenBy { item -> item.repair.id },
                )
            }
    }
    val columnBounds = remember { mutableStateMapOf<String, Rect>() }
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }
    var queueRootBounds by remember { mutableStateOf<Rect?>(null) }
    var activeDrag by remember { mutableStateOf<ActiveRepairDrag?>(null) }
    var activeColumnDrag by remember { mutableStateOf<ActiveRepairColumnDrag?>(null) }
    var infoItem by remember { mutableStateOf<RepairQueueItem?>(null) }
    var emptyDropItem by remember { mutableStateOf<RepairQueueItem?>(null) }

    fun moveItem(
        item: RepairQueueItem,
        targetDate: String,
        proposedIndex: Int,
    ) {
        if (busy || !repairQueueEntryCanMove(item.entry)) return
        val targetItems = itemsByDate[targetDate].orEmpty()
        val sourceIndex = targetItems.indexOfFirst { candidate -> candidate.id == item.id }
        if (item.date == targetDate &&
            (sourceIndex == proposedIndex || sourceIndex + 1 == proposedIndex)
        ) {
            return
        }
        val targetIndex = normalizedRepairQueueTargetIndex(
            targetItems = targetItems,
            activeItem = item,
            proposedIndex = proposedIndex,
            targetDate = targetDate,
        )
        onMove(item, targetDate, targetIndex)
    }

    fun finishDrag() {
        val drag = activeDrag ?: return
        activeDrag = null
        if (busy || !repairQueueEntryCanMove(drag.item.entry)) return
        val sourceBounds = cardBounds[drag.item.id] ?: return
        val center = sourceBounds.center + drag.offset
        val targetDate = repairQueueDateAtHorizontalPosition(
            displayedDates.mapNotNull { date ->
                columnBounds[date]?.let { bounds ->
                    RepairQueueDateColumnRange(
                        date = date,
                        left = bounds.left,
                        right = bounds.right,
                    )
                }
            },
            center.x,
        )
        if (targetDate == null) {
            emptyDropItem = drag.item
            return
        }
        val targetItems = itemsByDate[targetDate].orEmpty()
        val proposedIndex = targetItems.indexOfFirst { candidate ->
            candidate.id != drag.item.id &&
                center.y < (cardBounds[candidate.id]?.center?.y ?: Float.MAX_VALUE)
        }.let { index -> if (index < 0) targetItems.size else index }
        moveItem(drag.item, targetDate, proposedIndex)
    }

    fun finishColumnDrag() {
        val drag = activeColumnDrag ?: return
        activeColumnDrag = null
        if (busy) return
        val sourceBounds = columnBounds[drag.date] ?: return
        val remainingDates = displayedDates.filterNot { date -> date == drag.date }
        if (remainingDates.none { date -> columnBounds[date] != null }) return

        val center = sourceBounds.center + drag.offset
        val targetIndex = remainingDates.indexOfFirst { date ->
            columnBounds[date]?.let { bounds -> center.x < bounds.center.x } ?: false
        }.let { index -> if (index < 0) remainingDates.size else index }
        val targetDate = when {
            targetIndex >= remainingDates.size -> remainingDates.lastOrNull()
            else -> remainingDates.getOrNull(targetIndex)
        } ?: return
        if (targetDate == drag.date) return
        onSwapDateColumns(drag.date, targetDate) {
            orderedDates = swapRepairQueueDateColumns(
                dates = displayedDates,
                firstDate = drag.date,
                secondDate = targetDate,
            )
        }
    }

    fun moveEmptyDropItem(item: RepairQueueItem, targetDate: String) {
        if (busy || !repairQueueEntryCanMove(item.entry)) return
        val targetItems = itemsByDate[targetDate].orEmpty()
        val targetIndex = repairQueueEndTargetIndex(
            targetItems = targetItems,
            activeItem = item,
        )
        val sourceIndex = targetItems.indexOfFirst { candidate -> candidate.id == item.id }
        if (item.date == targetDate && sourceIndex == targetIndex) return
        onMove(item, targetDate, targetIndex)
    }

    if (displayedDates.isEmpty()) {
        EmptyState(
            title = "Очереди пока пусты",
            description = "После постановки ремонта в очередь здесь появятся даты и задания.",
            modifier = modifier.fillMaxSize(),
        )
    } else {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    queueRootBounds = coordinates.boundsInRoot()
                },
        ) {
            val columnWidth = if (maxWidth >= 840.dp) 300.dp else 216.dp
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(displayedDates, key = { date -> date }) { date ->
                    val dateItems = itemsByDate[date].orEmpty()
                    val columnDragging = activeColumnDrag?.date == date
                    Surface(
                        modifier = Modifier
                            .animateItem()
                            .width(columnWidth)
                            .fillMaxHeight()
                            .graphicsLayer {
                                alpha = if (columnDragging) 0.35f else 1f
                            }
                            .onGloballyPositioned { coordinates ->
                                columnBounds[date] = coordinates.boundsInRoot()
                            },
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RepairQueueDateColumnHeader(
                                date = date,
                                itemCount = dateItems.size,
                                enabled = !busy && activeDrag == null,
                                dragging = columnDragging,
                                onDragStart = {
                                    if (activeDrag == null) {
                                        activeColumnDrag = ActiveRepairColumnDrag(date)
                                    }
                                },
                                onDrag = { delta ->
                                    activeColumnDrag = activeColumnDrag?.let { current ->
                                        if (current.date == date) {
                                            current.copy(offset = current.offset + delta)
                                        } else {
                                            current
                                        }
                                    }
                                },
                                onDragEnd = ::finishColumnDrag,
                                onDragCancel = { activeColumnDrag = null },
                            )
                            if (dateItems.isEmpty()) {
                                Text(
                                    "Заданий нет",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(dateItems, key = RepairQueueItem::id) { item ->
                                        val dragging = activeDrag?.item?.id == item.id
                                        RepairQueueCard(
                                            item = item,
                                            cabinNumber = item.entry.unitNumber
                                                ?: assetLabels[item.repair.rentalItemId]
                                                ?: "без номера",
                                            enabled = !busy && activeColumnDrag == null,
                                            dragging = dragging,
                                            onBounds = { bounds -> cardBounds[item.id] = bounds },
                                            onDragStart = {
                                                if (activeColumnDrag == null &&
                                                    repairQueueEntryCanMove(item.entry)
                                                ) {
                                                    activeDrag = ActiveRepairDrag(item)
                                                }
                                            },
                                            onDrag = { delta ->
                                                activeDrag = activeDrag?.let { current ->
                                                    if (current.item.id == item.id) {
                                                        current.copy(offset = current.offset + delta)
                                                    } else {
                                                        current
                                                    }
                                                }
                                            },
                                            onDragEnd = ::finishDrag,
                                            onDragCancel = { activeDrag = null },
                                            onInfo = { infoItem = item },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val columnDrag = activeColumnDrag
            val sourceColumnBounds = columnDrag?.let { columnBounds[it.date] }
            val rootBounds = queueRootBounds
            if (columnDrag != null && sourceColumnBounds != null && rootBounds != null) {
                RepairQueueDateColumnDragOverlay(
                    date = columnDrag.date,
                    itemCount = itemsByDate[columnDrag.date].orEmpty().size,
                    modifier = Modifier
                        .width(columnWidth)
                        .offset {
                            IntOffset(
                                x = (
                                    sourceColumnBounds.left -
                                        rootBounds.left +
                                        columnDrag.offset.x
                                    ).roundToInt(),
                                y = (
                                    sourceColumnBounds.top -
                                        rootBounds.top +
                                        columnDrag.offset.y
                                    ).roundToInt(),
                            )
                        }
                        .zIndex(90f),
                )
            }
            val drag = activeDrag
            val sourceBounds = drag?.let { cardBounds[it.item.id] }
            if (drag != null && sourceBounds != null && rootBounds != null) {
                RepairQueueDragOverlay(
                    item = drag.item,
                    cabinNumber = drag.item.entry.unitNumber
                        ?: assetLabels[drag.item.repair.rentalItemId]
                        ?: "без номера",
                    modifier = Modifier
                        .width(columnWidth - 20.dp)
                        .offset {
                            IntOffset(
                                x = (
                                    sourceBounds.left -
                                        rootBounds.left +
                                        drag.offset.x
                                    ).roundToInt(),
                                y = (
                                    sourceBounds.top -
                                        rootBounds.top +
                                        drag.offset.y
                                    ).roundToInt(),
                            )
                        }
                        .zIndex(100f),
                )
            }
        }
    }

    infoItem?.let { item ->
        Dialog(
            onDismissRequest = { infoItem = null },
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
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Задание бытовки ${
                            item.entry.unitNumber
                                ?: assetLabels[item.repair.rentalItemId]
                                ?: "без номера"
                        }",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    QueueInfoRow("Задание", item.entry.taskText ?: item.entry.title)
                    QueueInfoRow("Дата", repairQueueDateLabel(item.date))
                    QueueInfoRow("Приоритет", item.entry.priority.toString())
                    QueueInfoRow("Статус", repairQueueStatusLabel(item.entry.status))
                    QueueInfoRow("Позиция", (item.entry.queuePosition + 1).toString())
                    QueueInfoRow("Очередь", item.queue.queueName)
                    QueueInfoRow(
                        "Тип",
                        if (item.repair.kind == "REWORK") "Доработка" else "Ремонт",
                    )
                    val lineGroups = item.stage.repairQueueLineGroups()
                    QueueInfoPills("Работы", lineGroups.works)
                    QueueInfoPills("Материалы", lineGroups.materials)
                    Button(
                        onClick = {
                            infoItem = null
                            onOpenRepair(item.repair.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Открыть ремонт") }
                }
            }
        }
    }

    emptyDropItem?.let { item ->
        RepairQueueEmptyDropDialog(
            item = item,
            dates = displayedDates,
            busy = busy,
            onDismiss = { emptyDropItem = null },
            onConfirm = { targetDate ->
                emptyDropItem = null
                moveEmptyDropItem(item, targetDate)
            },
        )
    }
}

@Composable
private fun RepairQueueDateColumnHeader(
    date: String,
    itemCount: Int,
    enabled: Boolean,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val movable = enabled
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    repairQueueDateLabel(date),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    itemCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "Удерживайте ↔, чтобы изменить порядок дат",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = "Переместить колонку ${repairQueueDateLabel(date)}"
                }
                .pointerInput(date, movable) {
                    if (!movable) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                },
            shape = CircleShape,
            color = if (dragging) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("↔", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun RepairQueueDateColumnDragOverlay(
    date: String,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    repairQueueDateLabel(date),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Перемещение даты",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                itemCount.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RepairQueueEmptyDropDialog(
    item: RepairQueueItem,
    dates: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    var existingDate by remember(item.id) { mutableStateOf("") }
    var newDate by remember(item.id) { mutableStateOf("") }
    val targetDate = newDate.ifBlank { existingDate }
    val validTargetDate = runCatching { LocalDate.parse(targetDate) }.isSuccess

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
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Выберите дату перемещения",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Карточка «${item.entry.taskText ?: item.entry.title}» " +
                        "перенесена в свободную область. Выберите существующую дату " +
                        "или новую дату в календаре — для неё будет создана колонка.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Дата из очереди",
                    style = MaterialTheme.typography.titleSmall,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 184.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(dates, key = { date -> date }) { date ->
                        if (existingDate == date) {
                            Button(
                                onClick = {
                                    existingDate = date
                                    newDate = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                            ) {
                                Text("✓ ${repairQueueDateLabel(date)}")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    existingDate = date
                                    newDate = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                            ) {
                                Text(repairQueueDateLabel(date))
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val initial = runCatching {
                            LocalDate.parse(newDate.ifBlank { LocalDate.now().toString() })
                        }.getOrElse { LocalDate.now() }
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                newDate = LocalDate.of(year, month + 1, dayOfMonth).toString()
                                existingDate = ""
                            },
                            initial.year,
                            initial.monthValue - 1,
                            initial.dayOfMonth,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(
                        newDate.takeIf(String::isNotBlank)
                            ?.let(::repairQueueDateLabel)
                            ?: "Выбрать новую дату",
                    )
                }
                Text(
                    "Новая дата создаст отдельную колонку после сохранения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss, enabled = !busy) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = { onConfirm(targetDate) },
                        enabled = !busy && validTargetDate,
                    ) {
                        Text("Переместить")
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairQueueCard(
    item: RepairQueueItem,
    cabinNumber: String,
    enabled: Boolean,
    dragging: Boolean,
    onBounds: (Rect) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onInfo: () -> Unit,
) {
    val movable = enabled && repairQueueEntryCanMove(item.entry)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates -> onBounds(coordinates.boundsInRoot()) }
            .zIndex(if (dragging) 10f else 0f)
            .graphicsLayer {
                alpha = if (dragging) 0f else 1f
            }
            .pointerInput(item.id, movable) {
                if (!movable) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            },
        shape = MaterialTheme.shapes.small,
        color = when (item.entry.status) {
            "IN_PROGRESS" -> MaterialTheme.colorScheme.primaryContainer
            "PAUSED" -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (dragging) 6.dp else 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    item.entry.taskText ?: item.entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Text(
                    "Бытовка $cabinNumber · P${item.entry.priority}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    repairQueueStatusLabel(item.entry.status) +
                        if (movable) " · удерживайте для переноса" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (movable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Surface(
                onClick = onInfo,
                enabled = enabled,
                modifier = Modifier.size(48.dp),
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
private fun RepairQueueDragOverlay(
    item: RepairQueueItem,
    cabinNumber: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                item.entry.taskText ?: item.entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            Text(
                "Бытовка $cabinNumber · P${item.entry.priority}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private fun repairQueueDateLabel(value: String): String = runCatching {
    LocalDate.parse(value).format(
        DateTimeFormatter.ofPattern("EEE, dd MMMM", Locale.forLanguageTag("ru-RU")),
    )
}.getOrDefault(value)

private fun repairQueueStatusLabel(value: String): String = when (value) {
    "WAITING" -> "Ожидает"
    "IN_PROGRESS" -> "В работе"
    "PAUSED" -> "На паузе"
    "DONE" -> "Завершено"
    "CANCELLED" -> "Отменено"
    else -> value
}
