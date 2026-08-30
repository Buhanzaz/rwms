package dev.buhanzaz.rwms.driver.feature.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yandex.mapkit.map.MapWindow
import com.yandex.mapkit.mapview.MapView
import dev.buhanzaz.rwms.driver.core.network.DriverVehicleInspectionItemDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import dev.buhanzaz.rwms.driver.core.ui.DriverScreenScaffold
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Server-driven root gate for Driver Up. Existing task navigation is rendered only when the
 * backend returns SHOW_TASKS or when the rollout feature is disabled.
 */
@Composable
fun DriverShiftHost(
    userId: String,
    displayName: String,
    onCapturePhoto: (ShiftPhotoCaptureRequest) -> Unit,
    activeTasks: @Composable (shiftLifecycleEnabled: Boolean) -> Unit,
    viewModel: DriverShiftViewModel = hiltViewModel(),
) {
    LaunchedEffect(userId) { viewModel.bind(userId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = state.today

    when {
        state.loading -> DriverShiftLoadingScreen()
        today == null -> DriverShiftUnavailableScreen(
            state = state,
            message = "Не удалось восстановить состояние смены",
            onRefresh = viewModel::refresh,
        )
        !today.enabled || today.nextRequiredAction == "SHOW_TASKS" -> activeTasks(today.enabled)
        today.nextRequiredAction == "SHIFT_NOT_AVAILABLE" -> DriverShiftUnavailableScreen(
            state = state,
            message = today.nextAvailableAt?.let { "Новая смена будет доступна ${formatDateTime(it)}" }
                ?: "На текущую рабочую дату смена пока не назначена",
            onRefresh = viewModel::refresh,
        )
        today.nextRequiredAction == "SHOW_DAILY_BRIEFING" -> DailyBriefingScreen(
            state = state,
            today = today,
            displayName = displayName,
            onLoadTraffic = viewModel::loadTraffic,
            onAttachTrafficMap = viewModel::attachTrafficMap,
            onDetachTrafficMap = viewModel::detachTrafficMap,
            onContinue = viewModel::confirmBriefing,
            onClearError = viewModel::clearError,
        )
        today.nextRequiredAction == "COMPLETE_MEDICAL_CHECK" -> MedicalCheckScreen(
            state = state,
            today = today,
            onConfirm = viewModel::confirmMedicalCheck,
            onClearError = viewModel::clearError,
        )
        today.nextRequiredAction == "COMPLETE_VEHICLE_INSPECTION" -> VehicleInspectionScreen(
            state = state,
            today = today,
            onItemResult = viewModel::updateInspectionItem,
            onComplete = viewModel::completeInspection,
            onCapturePhoto = onCapturePhoto,
            onClearError = viewModel::clearError,
        )
        today.nextRequiredAction == "START_SHIFT" -> ReadyToStartScreen(
            state = state,
            today = today,
            onStart = viewModel::startShift,
            onClearError = viewModel::clearError,
        )
        today.nextRequiredAction == "START_SHIFT_CLOSING" -> TasksCompletedScreen(
            state = state,
            today = today,
            onContinue = viewModel::startClosing,
            onClearError = viewModel::clearError,
        )
        today.nextRequiredAction == "CONFIRM_WAREHOUSE_RETURN" -> WarehouseReturnScreen(
            state = state,
            today = today,
            onConfirm = viewModel::confirmWarehouseReturn,
            onClearError = viewModel::clearError,
        )
        today.nextRequiredAction == "COMPLETE_END_OF_SHIFT_REPORT" -> ClosingFlowScreen(
            state = state,
            today = today,
            onEnsureDraft = viewModel::ensureClosingDraft,
            onUpdateDraft = viewModel::updateClosingDraft,
            onSubmit = viewModel::submitClosingReport,
            onCapturePhoto = onCapturePhoto,
            onClearError = viewModel::clearError,
        )
        today.nextRequiredAction == "CLOSE_SHIFT" -> CloseShiftScreen(
            state = state,
            today = today,
            onClose = viewModel::closeShift,
            onCapturePhoto = onCapturePhoto,
            onClearError = viewModel::clearError,
        )
        today.nextRequiredAction == "SHIFT_CLOSED" -> ShiftClosedScreen(state, today, viewModel::clearError)
        else -> DriverShiftUnavailableScreen(
            state = state,
            message = "RWMS вернул неизвестный следующий шаг: ${today.nextRequiredAction}",
            onRefresh = viewModel::refresh,
        )
    }
}

@Composable
private fun DriverShiftLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Загружаем смену…", modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun DriverShiftUnavailableScreen(
    state: DriverShiftUiState,
    message: String,
    onRefresh: () -> Unit,
) {
    ShiftListScaffold("Driver Up", state, onClearError = {}) {
        item {
            HeroIcon(Icons.Filled.LocalShipping)
            Text(
                message,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { PrimaryAction("Обновить", onRefresh, leadingIcon = Icons.Filled.Refresh) }
    }
}

@Composable
private fun DailyBriefingScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    displayName: String,
    onLoadTraffic: (Double?, Double?) -> Unit,
    onAttachTrafficMap: (MapWindow) -> Unit,
    onDetachTrafficMap: (MapWindow) -> Unit,
    onContinue: () -> Unit,
    onClearError: () -> Unit,
) {
    val warehouse = today.warehouse
    val briefing = today.briefing
    val weather = briefing?.weather
    val driverName = today.shift?.driverName?.takeIf(String::isNotBlank) ?: displayName
    val greeting = remember(today.shift?.id) {
        greetingAt(Instant.now(), today.shift?.timeZone ?: warehouse?.timeZone)
    }
    LaunchedEffect(warehouse?.latitude, warehouse?.longitude) {
        onLoadTraffic(warehouse?.latitude, warehouse?.longitude)
    }
    ShiftListScaffold("Добро пожаловать", state, onClearError) {
        item {
            HeroIcon(Icons.Filled.LocalShipping)
            Text(
                "$greeting, ${driverName.firstName()}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Хорошей смены",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        item {
            BriefingCard(Icons.Filled.Cloud, "Погода · ${briefing?.locationName ?: warehouse?.city.orEmpty()}") {
                if (weather?.available == true) {
                    Text(
                        "Сейчас ${formatTemperature(weather.currentTempC)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Ощущается как ${formatTemperature(weather.feelsLikeC)}")
                    Text("Днём ${formatTemperatureRange(weather.minTempC, weather.maxTempC)}")
                    weather.condition?.takeIf(String::isNotBlank)?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                    weather.windGustMetersPerSecond?.let { gust ->
                        Text("Порывы ветра до ${formatDecimal(gust)} м/с")
                    }
                } else {
                    Text("Данные временно недоступны", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                weather?.attribution?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (!weather?.hazards.isNullOrEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                "Внимание",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                        weather?.hazards.orEmpty().forEach { hazard ->
                            Text(hazard.title, fontWeight = FontWeight.SemiBold)
                            Text(hazard.description)
                        }
                    }
                }
            }
        }
        item {
            BriefingCard(Icons.Filled.Traffic, "Дорожная обстановка") {
                when {
                    state.trafficLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Получаем данные о пробках…", modifier = Modifier.padding(start = 12.dp))
                    }
                    state.traffic?.available == true -> Text(
                        "Пробки: ${state.traffic.level} баллов",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    else -> Text("Данные о пробках временно недоступны")
                }
                if (warehouse?.latitude != null && warehouse.longitude != null) {
                    TrafficMap(
                        onAttach = onAttachTrafficMap,
                        onDetach = onDetachTrafficMap,
                    )
                }
            }
        }
        item {
            PrimaryAction(
                label = if (ACTION_BRIEFING_SEEN in state.pendingActions) "Ожидает синхронизации" else "Продолжить",
                onClick = onContinue,
                enabled = !state.submitting && ACTION_BRIEFING_SEEN !in state.pendingActions,
            )
        }
    }
}

/**
 * Hosts the documented MapView/MapWindow path needed by MapKit's traffic layer while preserving
 * Driver Up's API 23 device support. The view is visible, noninteractive, and lifecycle-balanced.
 */
@Composable
private fun TrafficMap(
    onAttach: (MapWindow) -> Unit,
    onDetach: (MapWindow) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) { MapView(context).apply { setNoninteractive(true) } }
    var laidOut by remember(mapView) { mutableStateOf(false) }

    DisposableEffect(mapView, lifecycleOwner) {
        var started = false
        fun start() {
            if (!started) {
                mapView.onStart()
                started = true
            }
        }
        fun stop() {
            if (started) {
                mapView.onStop()
                started = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }

    DisposableEffect(mapView, laidOut) {
        if (laidOut) onAttach(mapView.mapWindow)
        onDispose {
            if (laidOut) onDetach(mapView.mapWindow)
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp)
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .onSizeChanged { size -> laidOut = size.width > 0 && size.height > 0 },
    )
}

@Composable
private fun MedicalCheckScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    onConfirm: () -> Unit,
    onClearError: () -> Unit,
) {
    ShiftListScaffold("Подготовка к смене", state, onClearError) {
        item { StepHeader("Шаг 1 из 2", "Предрейсовый медосмотр") }
        item {
            StatusCard(
                icon = Icons.Filled.HealthAndSafety,
                title = "Предрейсовый медосмотр",
                body = "Перед началом смены необходимо пройти медицинский осмотр.",
            )
        }
        today.shift?.medicalCheck?.let { medical ->
            item { SuccessCard("Медосмотр пройден", formatTime(medical.completedAt)) }
        }
        item {
            PrimaryAction(
                label = if (ACTION_MEDICAL_CHECK in state.pendingActions) {
                    "Сохранено · ожидает синхронизации"
                } else {
                    "Подтверждаю, что медосмотр пройден"
                },
                onClick = onConfirm,
                enabled = !state.submitting && ACTION_MEDICAL_CHECK !in state.pendingActions,
            )
        }
        item {
            Text(
                "Тестовый режим self-confirmation. Сервер сохранит водителя и своё время подтверждения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VehicleInspectionScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    onItemResult: (String, String, String?, String?) -> Unit,
    onComplete: () -> Unit,
    onCapturePhoto: (ShiftPhotoCaptureRequest) -> Unit,
    onClearError: () -> Unit,
) {
    val shift = requireNotNull(today.shift)
    val vehicle = requireNotNull(today.vehicle)
    val inspection = requireNotNull(today.inspection)
    var inspectionStarted by rememberSaveable(inspection.id) {
        mutableStateOf(inspection.checkedRequired > 0)
    }
    var defectItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var defectId by rememberSaveable { mutableStateOf<String?>(null) }
    var defectDescription by rememberSaveable { mutableStateOf("") }
    val defectItem = inspection.items.firstOrNull { it.id == defectItemId }

    ShiftListScaffold("Осмотр автомобиля", state, onClearError) {
        item { StepHeader("Шаг 2 из 2", "Осмотр автомобиля") }
        item { VehicleCard(today) }
        if (!inspectionStarted) {
            item {
                PrimaryAction(
                    "Начать осмотр",
                    onClick = { inspectionStarted = true },
                    leadingIcon = Icons.AutoMirrored.Filled.FactCheck,
                )
            }
        } else {
            item {
                Text(
                    "${inspection.checkedRequired} из ${inspection.totalRequired} проверено",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                LinearProgressIndicator(
                    progress = {
                        if (inspection.totalRequired == 0) 0f
                        else inspection.checkedRequired.toFloat() / inspection.totalRequired.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            inspection.items.groupBy { it.section }.forEach { (section, sectionItems) ->
                item { Text(section, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(sectionItems, key = { it.id }) { item ->
                    InspectionItemCard(
                        item = item,
                        photoCount = today.photos.count { it.inspectionItemId == item.id },
                        enabled = !state.submitting,
                        onOk = { onItemResult(item.id, "OK", null, null) },
                        onDefect = {
                            defectItemId = item.id
                            defectId = item.defect?.id ?: UUID.randomUUID().toString()
                            defectDescription = item.defect?.description.orEmpty()
                        },
                        onPhoto = item.defect?.let { defect ->
                            {
                                onCapturePhoto(
                                    ShiftPhotoCaptureRequest(
                                        shiftId = shift.id,
                                        expectedVersion = requireNotNull(state.today?.shift).version,
                                        role = "INSPECTION_DEFECT",
                                        defectId = defect.id,
                                        inspectionItemId = item.id,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            if (inspection.blockingDefectCount > 0) {
                item {
                    ErrorCard(
                        "Автомобиль не готов к смене",
                        "Обнаружена блокирующая неисправность. Обычный запуск смены недоступен до решения ответственным.",
                    )
                }
            }
            item {
                PrimaryAction(
                    label = if (ACTION_INSPECTION_COMPLETE in state.pendingActions) {
                        "Осмотр ожидает синхронизации"
                    } else {
                        "Подтвердить осмотр"
                    },
                    onClick = onComplete,
                    enabled = inspection.checkedRequired == inspection.totalRequired &&
                        inspection.blockingDefectCount == 0 &&
                        ACTION_INSPECTION_COMPLETE !in state.pendingActions &&
                        !state.submitting,
                )
            }
        }
    }

    if (defectItem != null && defectId != null) {
        AlertDialog(
            onDismissRequest = { defectItemId = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Что обнаружено?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(defectItem.label, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = defectDescription,
                        onValueChange = { defectDescription = it.take(2_000) },
                        label = { Text("Описание неисправности") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Сначала сохраните неисправность. После этого кнопка фото появится в карточке проверки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onItemResult(defectItem.id, "DEFECT", defectDescription, defectId)
                        defectItemId = null
                    },
                    enabled = defectDescription.isNotBlank(),
                ) { Text("Сохранить неисправность") }
            },
            dismissButton = { TextButton(onClick = { defectItemId = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun InspectionItemCard(
    item: DriverVehicleInspectionItemDto,
    photoCount: Int,
    enabled: Boolean,
    onOk: () -> Unit,
    onDefect: () -> Unit,
    onPhoto: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            item.defect?.let { defect ->
                Text(defect.description, color = MaterialTheme.colorScheme.error)
                if (photoCount > 0) Text("Фото: $photoCount", style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onOk,
                    enabled = enabled && item.state != "DEFECT",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (item.state == "OK") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (item.state == "OK") MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Исправно") }
                Button(
                    onClick = onDefect,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (item.state == "DEFECT") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.errorContainer,
                        contentColor = if (item.state == "DEFECT") MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Есть проблема") }
            }
            if (onPhoto != null) {
                TextButton(onClick = onPhoto, modifier = Modifier.align(Alignment.End)) { Text("Добавить фото") }
            }
        }
    }
}

@Composable
private fun ReadyToStartScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    onStart: () -> Unit,
    onClearError: () -> Unit,
) {
    val shift = requireNotNull(today.shift)
    val vehicle = requireNotNull(today.vehicle)
    ShiftListScaffold("Всё готово", state, onClearError) {
        item { HeroIcon(Icons.Filled.CheckCircle) }
        item { SuccessRow("Медосмотр", "Пройден · ${formatTime(shift.medicalCheck?.completedAt)}") }
        item { SuccessRow("${vehicle.name} ${vehicle.registrationNumber}", "Осмотрен · ${formatTime(shift.vehicleInspectionCompletedAt)}") }
        vehicle.trailer?.let { trailer ->
            item { SuccessRow("Прицеп ${trailer.registrationNumber}", "Осмотрен · ${formatTime(shift.vehicleInspectionCompletedAt)}") }
        }
        item {
            PrimaryAction(
                label = if (ACTION_START in state.pendingActions) "Запуск ожидает подтверждения RWMS" else "НАЧАТЬ СМЕНУ",
                onClick = onStart,
                enabled = !state.submitting && ACTION_START !in state.pendingActions,
            )
        }
    }
}

@Composable
private fun TasksCompletedScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    onContinue: () -> Unit,
    onClearError: () -> Unit,
) {
    val summary = today.taskSummary
    ShiftListScaffold("Сегодняшние ходки завершены", state, onClearError) {
        item { HeroIcon(Icons.Filled.CheckCircle) }
        item {
            Text(
                "Все сегодняшние ходки выполнены",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Metric("${summary?.tripCount ?: 0}", tripsLabel(summary?.tripCount ?: 0))
                    Metric(formatRouteDistance(summary?.routeDistanceMeters ?: 0L), "по маршрутам")
                }
            }
        }
        item {
            Text(
                "Смена ещё не закрыта. Вернитесь на склад и выполните финальную проверку автомобиля.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            PrimaryAction(
                "ПЕРЕЙТИ К ЗАВЕРШЕНИЮ СМЕНЫ",
                onContinue,
                enabled = !state.submitting && ACTION_CLOSING_START !in state.pendingActions,
            )
        }
    }
}

@Composable
private fun WarehouseReturnScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    onConfirm: () -> Unit,
    onClearError: () -> Unit,
) {
    ShiftListScaffold("Завершение смены", state, onClearError) {
        item { StepHeader("Шаг 1", "Вернулись на склад?") }
        item {
            StatusCard(
                Icons.Filled.LocalShipping,
                today.warehouse?.name ?: "Склад",
                today.warehouse?.address ?: today.warehouse?.city.orEmpty(),
            )
        }
        item {
            PrimaryAction(
                label = if (ACTION_WAREHOUSE_RETURN in state.pendingActions) {
                    "Возвращение ожидает подтверждения"
                } else {
                    "Да, вернулся на склад"
                },
                onClick = onConfirm,
                enabled = !state.submitting && ACTION_WAREHOUSE_RETURN !in state.pendingActions,
            )
        }
    }
}

@Composable
private fun CloseShiftScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    onClose: () -> Unit,
    onCapturePhoto: (ShiftPhotoCaptureRequest) -> Unit,
    onClearError: () -> Unit,
) {
    var confirmationOpen by rememberSaveable { mutableStateOf(false) }
    val shift = requireNotNull(today.shift)
    val report = requireNotNull(today.closingReport)
    val closingDefectId = report.defectId.takeIf { report.vehicleCondition == "DEFECT_REPORTED" }
    val closingDefectPhotos = today.photos.filter { photo ->
        photo.role == "END_SHIFT_DEFECT" && photo.defectId == closingDefectId
    }
    val readyDefectPhotoCount = closingDefectPhotos.count { it.state == "READY" }
    val requiredPhotoMissing = closingDefectId != null && closingDefectPhotos.isEmpty()
    val requiredPhotoNotReady = closingDefectId != null && readyDefectPhotoCount == 0
    val photoNeedsReview = closingDefectPhotos.any { it.state == "REVIEW_REQUIRED" }
    val photoReservationPending = ACTION_PHOTO_RESERVATION in state.pendingActions
    ShiftListScaffold("Завершение смены", state, onClearError) {
        item { SuccessRow("Вернулись на склад", formatTime(today.shift?.returnedToWarehouseAt)) }
        item {
            SuccessRow(
                "Автомобиль",
                if (report.vehicleCondition == "NO_NEW_DEFECTS") "Неисправностей не обнаружено" else "Неисправность зарегистрирована",
            )
        }
        item { SummaryValue("Пробег", "${formatLong(report.endOdometer)} км") }
        item { SummaryValue("За смену", report.odometerDistance?.let { "${formatLong(it)} км" } ?: "—") }
        item { SummaryValue("Топливо", "${report.fuelLevelPercent}%") }
        item { SummaryValue("Фото", today.photos.size.toString()) }
        if (closingDefectId != null) {
            item {
                if (requiredPhotoMissing) {
                    ErrorCard(
                        "Нужно фото неисправности",
                        "Добавьте фотографию, чтобы завершить смену.",
                    )
                } else if (photoNeedsReview && readyDefectPhotoCount == 0) {
                    ErrorCard(
                        "Фото требует повторной съёмки",
                        "Сервер не смог обработать фотографию. Добавьте новое фото.",
                    )
                } else if (requiredPhotoNotReady) {
                    StatusCard(
                        Icons.Filled.Sync,
                        "Фото обрабатывается",
                        "Закрытие станет доступно после подтверждения фотографии сервером.",
                    )
                } else {
                    SuccessCard("Фото неисправности готово", "$readyDefectPhotoCount шт.")
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        onCapturePhoto(
                            ShiftPhotoCaptureRequest(
                                shiftId = shift.id,
                                expectedVersion = shift.version,
                                role = "END_SHIFT_DEFECT",
                                defectId = closingDefectId,
                            ),
                        )
                    },
                    enabled = !state.submitting && !photoReservationPending,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(if (requiredPhotoMissing) "Добавить обязательное фото" else "Добавить ещё фото")
                }
            }
        }
        item {
            PrimaryAction(
                label = when {
                    requiredPhotoMissing -> "СНАЧАЛА ДОБАВЬТЕ ФОТО"
                    photoReservationPending -> "ФОТО ОЖИДАЕТ RWMS"
                    requiredPhotoNotReady -> "ФОТО ОБРАБАТЫВАЕТСЯ"
                    ACTION_CLOSE in state.pendingActions -> "Закрытие ожидает подтверждения RWMS"
                    else -> "ЗАКРЫТЬ СМЕНУ"
                },
                onClick = { confirmationOpen = true },
                enabled = !state.submitting &&
                    !requiredPhotoMissing &&
                    !requiredPhotoNotReady &&
                    !photoReservationPending &&
                    ACTION_CLOSE !in state.pendingActions,
            )
        }
    }
    if (confirmationOpen) {
        AlertDialog(
            onDismissRequest = { confirmationOpen = false },
            title = { Text("Закрыть смену?") },
            text = { Text("После закрытия изменить данные можно будет только через предусмотренный административный процесс.") },
            confirmButton = {
                Button(onClick = { confirmationOpen = false; onClose() }) { Text("Закрыть смену") }
            },
            dismissButton = { TextButton(onClick = { confirmationOpen = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ShiftClosedScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    onClearError: () -> Unit,
) {
    val summary = today.taskSummary
    val report = today.closingReport
    ShiftListScaffold("Смена завершена", state, onClearError) {
        item { HeroIcon(Icons.Filled.CheckCircle) }
        item {
            Text(
                "Хорошего вечера!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { SummaryValue("Сегодня", "${summary?.tripCount ?: 0} ${tripsLabel(summary?.tripCount ?: 0)}") }
        item { SummaryValue("Пробег", report?.odometerDistance?.let { "${formatLong(it)} км" } ?: "—") }
        item { SummaryValue("Смена закрыта", formatTime(today.shift?.closedAt)) }
        item {
            Text(
                "Смена на сегодня завершена",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ShiftListScaffold(
    title: String,
    state: DriverShiftUiState,
    onClearError: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    DriverScreenScaffold(title = title) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.online || state.pendingCount > 0 || state.syncStage != null && state.syncStage != "IDLE") {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Sync, contentDescription = null)
                            Text(
                                when {
                                    state.pendingCount > 0 -> "Сохранено на устройстве. Ожидает синхронизации."
                                    !state.online -> "Нет связи с RWMS. Доступно сохранённое состояние."
                                    else -> state.syncMessage ?: "Синхронизация"
                                },
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
            state.error?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, modifier = Modifier.weight(1f))
                            IconButton(onClick = onClearError) { Text("×", style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }
            content()
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun HeroIcon(icon: ImageVector) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(92.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun StepHeader(step: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(step, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BriefingCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
internal fun StatusCard(icon: ImageVector, title: String, body: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
internal fun VehicleCard(today: TodayDriverShiftDto) {
    val vehicle = requireNotNull(today.vehicle)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(vehicle.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
            }
            Text(vehicle.registrationNumber, style = MaterialTheme.typography.headlineSmall)
            vehicle.trailer?.let {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Прицеп", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${it.name} · ${it.registrationNumber}", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(58.dp)) {
        leadingIcon?.let {
            Icon(it, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SuccessCard(title: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail)
            }
        }
    }
}

@Composable
internal fun ErrorCard(title: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text(detail)
        }
    }
}

@Composable
private fun SuccessRow(title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SummaryValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun greetingAt(now: Instant, zoneId: String?): String {
    val zone = runCatching { ZoneId.of(zoneId ?: "Europe/Moscow") }.getOrDefault(ZoneId.of("Europe/Moscow"))
    return when (now.atZone(zone).hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        else -> "Добрый вечер"
    }
}

private fun String.firstName(): String = trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { "водитель" }

private fun formatTemperature(value: Double?): String = value?.roundToInt()?.let { temperature ->
    if (temperature > 0) "+$temperature°" else "$temperature°"
} ?: "—"

private fun formatTemperatureRange(min: Double?, max: Double?): String =
    if (min == null || max == null) "—" else "${formatTemperature(min)}…${formatTemperature(max)}"

private fun formatDecimal(value: Double): String = if (value % 1.0 == 0.0) value.roundToInt().toString() else "%.1f".format(value)

internal fun formatTime(value: String?): String = value?.let {
    runCatching { OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
} ?: "—"

private fun formatDateTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("dd.MM · HH:mm"))
}.getOrDefault(value)

internal fun formatLong(value: Long): String = "%,d".format(value).replace(',', ' ')

/** Formats the exact server distance in metres as a compact driver-facing kilometre value. */
internal fun formatRouteDistance(meters: Long): String =
    "${formatDecimal(meters.coerceAtLeast(0L) / 1_000.0)} км"

internal fun tripsLabel(count: Int): String = when {
    count % 100 in 11..14 -> "ходок"
    count % 10 == 1 -> "ходка"
    count % 10 in 2..4 -> "ходки"
    else -> "ходок"
}
