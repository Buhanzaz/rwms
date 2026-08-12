package dev.buhanzaz.rwms.driver.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import dev.buhanzaz.rwms.driver.core.network.DriverKpiPaletteDto
import dev.buhanzaz.rwms.driver.core.ui.DriverScreenScaffold
import dev.buhanzaz.rwms.driver.core.ui.SyncStatusBanner
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Renders the driver's personal logistics tasks for one selected calendar date. */
@Composable
fun LogisticsScreen(
    userId: String,
    onTask: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId) { viewModel.bind(userId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var selectedDateText by rememberSaveable(userId) { mutableStateOf(today.toString()) }
    val selectedDate = runCatching { LocalDate.parse(selectedDateText) }.getOrDefault(today)
    val tasks = buildLogisticsTasksForDate(
        categories = state.categories,
        tasks = state.tasks,
        scheduledDate = selectedDate.toString(),
    )

    DriverScreenScaffold(
        title = "Логистика",
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
            LogisticsDateCarousel(
                today = today,
                selectedDate = selectedDate,
                onDateSelected = { selectedDateText = it.toString() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.conflicts.isNotEmpty()) {
                Text(
                    "Есть конфликты синхронизации: ${state.conflicts.size}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LogisticsTaskList(
                selectedDate = selectedDate,
                tasks = tasks,
                kpiPalette = state.kpiPalette,
                onTask = onTask,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Displays a centered, horizontally scrollable calendar. The month heading
 * stays in place and follows the date nearest the center after a scroll ends.
 */
@Composable
internal fun LogisticsDateCarousel(
    today: LocalDate,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedDateState by rememberUpdatedState(selectedDate)
    val onDateSelectedState by rememberUpdatedState(onDateSelected)

    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = logisticsMonthLabel(selectedDate),
            modifier = Modifier.fillMaxWidth().testTag("logistics-month"),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val neighborsBeforeCenter =
                ((maxWidth.value / LOGISTICS_DATE_ITEM_STRIDE.value) / 2f)
                    .toInt()
                    .coerceAtLeast(1)
            val selectedDateIndex = logisticsDateIndex(today, selectedDate)
                ?: LOGISTICS_DATE_CENTER_INDEX
            val initialFirstVisibleIndex =
                (selectedDateIndex - neighborsBeforeCenter).coerceAtLeast(0)
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialFirstVisibleIndex,
            )
            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(listState, today) {
                snapshotFlow { listState.isScrollInProgress }
                    .distinctUntilChanged()
                    .filter { scrolling -> !scrolling }
                    .collect {
                        val layout = listState.layoutInfo
                        val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                        val centered = layout.visibleItemsInfo.minByOrNull { item ->
                            abs(item.offset + item.size / 2 - viewportCenter)
                        } ?: return@collect
                        val centeredDate = logisticsDateAt(today, centered.index)
                        if (centeredDate != selectedDateState) {
                            onDateSelectedState(centeredDate)
                        }
                    }
            }

            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().testTag("logistics-date-carousel"),
                contentPadding = PaddingValues(horizontal = LOGISTICS_DATE_EDGE_PADDING),
                horizontalArrangement = Arrangement.spacedBy(LOGISTICS_DATE_GAP),
            ) {
                items(
                    count = LOGISTICS_DATE_ITEM_COUNT,
                    key = { index -> index },
                ) { index ->
                    val date = logisticsDateAt(today, index)
                    LogisticsDateChip(
                        date = date,
                        selected = date == selectedDate,
                        onClick = {
                            onDateSelected(date)
                            coroutineScope.launch {
                                listState.animateScrollToItem(
                                    index = (index - neighborsBeforeCenter).coerceAtLeast(0),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Renders one selectable weekday/day item inside the logistics date carousel. */
@Composable
private fun LogisticsDateChip(
    date: LocalDate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.width(LOGISTICS_DATE_ITEM_WIDTH)
            .testTag("logistics-date-${date}"),
        label = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(logisticsWeekdayLabel(date), style = MaterialTheme.typography.labelMedium)
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/** Renders the ordered cards selected by the logistics date surface. */
@Composable
internal fun LogisticsTaskList(
    selectedDate: LocalDate,
    tasks: List<DriverTaskEntity>,
    kpiPalette: DriverKpiPaletteDto?,
    onTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Заданий нет", style = MaterialTheme.typography.titleLarge)
                Text(
                    "На ${logisticsFullDateLabel(selectedDate)} нет отгрузок или возвратов.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.testTag("logistics-task-list"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "logistics-date-heading") {
            Text(
                "${logisticsFullDateLabel(selectedDate)} · заданий: ${tasks.size}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(tasks, key = { task -> task.entryId }) { task ->
            TaskRow(
                task = task,
                kpiPalette = kpiPalette,
                onOpen = { onTask(task.entryId) },
                presentationKey = "logistics-${task.entryId}",
                initiallyExpanded = true,
                showScheduledDate = false,
            )
        }
    }
}

/** Maps one stable carousel index to a device-local calendar date. */
internal fun logisticsDateAt(today: LocalDate, index: Int): LocalDate =
    today.plusDays((index - LOGISTICS_DATE_CENTER_INDEX).toLong())

/** Returns the matching index, or null when the date is over ten years from today. */
internal fun logisticsDateIndex(today: LocalDate, date: LocalDate): Int? {
    val offset = ChronoUnit.DAYS.between(today, date)
    if (offset !in -LOGISTICS_DATE_RADIUS_DAYS..LOGISTICS_DATE_RADIUS_DAYS) return null
    return LOGISTICS_DATE_CENTER_INDEX + offset.toInt()
}

/** Formats the fixed month heading in Russian. */
internal fun logisticsMonthLabel(date: LocalDate): String =
    "${RUSSIAN_MONTH_NAMES[date.monthValue - 1]} ${date.year}"

/** Formats the short weekday inside a carousel chip in Russian. */
internal fun logisticsWeekdayLabel(date: LocalDate): String =
    date.format(LOGISTICS_WEEKDAY_FORMATTER).removeSuffix(".")

/** Formats a selected date for the logistics card-list heading and empty state. */
internal fun logisticsFullDateLabel(date: LocalDate): String =
    date.format(LOGISTICS_FULL_DATE_FORMATTER)

private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru-RU")
private val RUSSIAN_MONTH_NAMES = listOf(
    "Январь",
    "Февраль",
    "Март",
    "Апрель",
    "Май",
    "Июнь",
    "Июль",
    "Август",
    "Сентябрь",
    "Октябрь",
    "Ноябрь",
    "Декабрь",
)
private val LOGISTICS_WEEKDAY_FORMATTER = DateTimeFormatter.ofPattern("EE", RUSSIAN_LOCALE)
private val LOGISTICS_FULL_DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN_LOCALE)
private const val LOGISTICS_DATE_RADIUS_DAYS = 3_650L
private const val LOGISTICS_DATE_ITEM_COUNT = 7_301
private const val LOGISTICS_DATE_CENTER_INDEX = LOGISTICS_DATE_ITEM_COUNT / 2
private val LOGISTICS_DATE_ITEM_WIDTH = 68.dp
private val LOGISTICS_DATE_GAP = 8.dp
private val LOGISTICS_DATE_ITEM_STRIDE = LOGISTICS_DATE_ITEM_WIDTH + LOGISTICS_DATE_GAP
private val LOGISTICS_DATE_EDGE_PADDING = 12.dp
