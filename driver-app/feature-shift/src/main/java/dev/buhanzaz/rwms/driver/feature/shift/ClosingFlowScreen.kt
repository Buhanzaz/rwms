package dev.buhanzaz.rwms.driver.feature.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.driver.core.database.DriverShiftDraftEntity
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import java.util.UUID
import kotlin.math.roundToInt

/** Renders and persists the multi-step end-of-shift report until server submission succeeds. */
@Composable
internal fun ClosingFlowScreen(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    onEnsureDraft: () -> Unit,
    onUpdateDraft: ((DriverShiftDraftEntity) -> DriverShiftDraftEntity) -> Unit,
    onSubmit: (Boolean) -> Unit,
    onCapturePhoto: (ShiftPhotoCaptureRequest) -> Unit,
    onClearError: () -> Unit,
) {
    val shift = requireNotNull(today.shift)
    LaunchedEffect(shift.id) { onEnsureDraft() }
    val draft = state.draft

    if (draft == null) {
        ShiftListScaffold("Завершение смены", state, onClearError) {
            item { Text("Восстанавливаем отчёт…") }
        }
    } else {
        when (draft.step) {
            "VEHICLE" -> ClosingVehicleStep(state, today, draft, onUpdateDraft, onClearError)
            "ODOMETER" -> ClosingOdometerStep(state, today, draft, onUpdateDraft, onClearError)
            "FUEL" -> ClosingFuelStep(state, draft, onUpdateDraft, onClearError)
            "PHOTO" -> ClosingPhotoStep(state, today, draft, onUpdateDraft, onCapturePhoto, onClearError)
            else -> ClosingSummaryStep(state, today, draft, onUpdateDraft, onSubmit, onClearError)
        }
    }

    if (state.requiresOdometerConfirmation) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Проверьте пробег") },
            text = { Text("Пробег заметно вырос относительно начала смены. Подтвердите, что значение введено верно.") },
            confirmButton = { Button(onClick = { onSubmit(true) }) { Text("Значение верно") } },
            dismissButton = {
                TextButton(onClick = { onUpdateDraft { it.copy(step = "ODOMETER") } }) { Text("Исправить") }
            },
        )
    }
}

@Composable
private fun ClosingVehicleStep(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    draft: DriverShiftDraftEntity,
    onUpdateDraft: ((DriverShiftDraftEntity) -> DriverShiftDraftEntity) -> Unit,
    onClearError: () -> Unit,
) {
    ShiftListScaffold("Состояние автомобиля", state, onClearError) {
        item { StepHeader("Завершение смены · 2 из 5", "Во время смены появились новые неисправности или повреждения?") }
        item { VehicleCard(today) }
        item {
            SelectionButton(
                label = "Нет, всё в порядке",
                selected = draft.vehicleCondition == "NO_NEW_DEFECTS",
                onClick = {
                    onUpdateDraft {
                        it.copy(
                            vehicleCondition = "NO_NEW_DEFECTS",
                            defectId = null,
                            defectDescription = "",
                        )
                    }
                },
            )
        }
        item {
            SelectionButton(
                label = "Да, есть проблема",
                selected = draft.vehicleCondition == "DEFECT_REPORTED",
                error = true,
                onClick = {
                    onUpdateDraft {
                        it.copy(
                            vehicleCondition = "DEFECT_REPORTED",
                            defectId = it.defectId ?: UUID.randomUUID().toString(),
                        )
                    }
                },
            )
        }
        if (draft.vehicleCondition == "DEFECT_REPORTED") {
            item {
                OutlinedTextField(
                    value = draft.defectDescription,
                    onValueChange = { value -> onUpdateDraft { it.copy(defectDescription = value.take(2_000)) } },
                    label = { Text("Что обнаружено?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "После сохранения отчёта добавьте обязательное фото к зарегистрированной неисправности.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            val ready = draft.vehicleCondition == "NO_NEW_DEFECTS" ||
                draft.vehicleCondition == "DEFECT_REPORTED" &&
                draft.defectDescription.isNotBlank()
            PrimaryAction(
                "Продолжить",
                onClick = { onUpdateDraft { it.copy(step = "ODOMETER") } },
                enabled = ready,
            )
        }
    }
}

@Composable
private fun ClosingOdometerStep(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    draft: DriverShiftDraftEntity,
    onUpdateDraft: ((DriverShiftDraftEntity) -> DriverShiftDraftEntity) -> Unit,
    onClearError: () -> Unit,
) {
    val start = today.vehicle?.startOdometer
    val end = draft.endOdometerText.toLongOrNull()
    val decreasing = start != null && end != null && end < start
    val suspicious = start != null && end != null &&
        end - start > today.suspiciousOdometerJumpKm
    ShiftListScaffold("Пробег", state, onClearError) {
        item { StepHeader("Завершение смены · 3 из 5", "Введите текущий пробег автомобиля") }
        start?.let { item { Text("Пробег в начале смены: ${formatLong(it)} км") } }
        item {
            OutlinedTextField(
                value = draft.endOdometerText,
                onValueChange = { value ->
                    onUpdateDraft { it.copy(endOdometerText = value.filter(Char::isDigit).take(12)) }
                },
                label = { Text("Текущий пробег, км") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = decreasing,
                supportingText = {
                    when {
                        decreasing -> Text("Пробег не может быть меньше предыдущего")
                        suspicious -> Text("Большой скачок — перед отправкой потребуется подтверждение")
                    }
                },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Speed, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onUpdateDraft { it.copy(step = "VEHICLE") } },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Назад") }
                Button(
                    onClick = { onUpdateDraft { it.copy(step = "FUEL") } },
                    enabled = end != null && !decreasing,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Продолжить") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClosingFuelStep(
    state: DriverShiftUiState,
    draft: DriverShiftDraftEntity,
    onUpdateDraft: ((DriverShiftDraftEntity) -> DriverShiftDraftEntity) -> Unit,
    onClearError: () -> Unit,
) {
    val fuel = draft.fuelLevelPercent ?: 50
    ShiftListScaffold("Уровень топлива", state, onClearError) {
        item { StepHeader("Завершение смены · 4 из 5", "Сколько топлива осталось?") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Icon(Icons.Filled.LocalGasStation, contentDescription = null)
                    Text("$fuel%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Slider(
                        value = fuel.toFloat(),
                        onValueChange = { value -> onUpdateDraft { it.copy(fuelLevelPercent = value.roundToInt()) } },
                        valueRange = 0f..100f,
                        steps = 99,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Пусто", 25 to "1/4", 50 to "1/2", 75 to "3/4", 100 to "Полный")
                            .forEach { (value, label) ->
                                OutlinedButton(onClick = { onUpdateDraft { it.copy(fuelLevelPercent = value) } }) {
                                    Text(label)
                                }
                            }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onUpdateDraft { it.copy(step = "ODOMETER") } },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Назад") }
                Button(
                    onClick = { onUpdateDraft { it.copy(step = "PHOTO", fuelLevelPercent = fuel) } },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Продолжить") }
            }
        }
    }
}

@Composable
private fun ClosingPhotoStep(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    draft: DriverShiftDraftEntity,
    onUpdateDraft: ((DriverShiftDraftEntity) -> DriverShiftDraftEntity) -> Unit,
    onCapturePhoto: (ShiftPhotoCaptureRequest) -> Unit,
    onClearError: () -> Unit,
) {
    val shift = requireNotNull(today.shift)
    val overviewCount = today.photos.count { it.role == "VEHICLE_OVERVIEW" }
    ShiftListScaffold("Фото автомобиля", state, onClearError) {
        item { StepHeader("Завершение смены · 5 из 5", "Фото после смены") }
        item {
            StatusCard(
                Icons.Filled.PhotoCamera,
                "Фото автомобиля",
                "В обычной смене фото необязательно. Если вы указали новую неисправность, после регистрации отчёта приложение попросит обязательное фото.",
            )
        }
        item {
            OutlinedButton(
                onClick = {
                    onCapturePhoto(
                        ShiftPhotoCaptureRequest(
                            shiftId = shift.id,
                            expectedVersion = shift.version,
                            role = "VEHICLE_OVERVIEW",
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (overviewCount == 0) "Добавить фото" else "Добавить ещё фото") }
        }
        if (overviewCount > 0) item { Text("Обзорных фото: $overviewCount") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onUpdateDraft { it.copy(step = "FUEL") } },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Назад") }
                Button(
                    onClick = { onUpdateDraft { it.copy(step = "SUMMARY") } },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("К итогу") }
            }
        }
    }
}

@Composable
private fun ClosingSummaryStep(
    state: DriverShiftUiState,
    today: TodayDriverShiftDto,
    draft: DriverShiftDraftEntity,
    onUpdateDraft: ((DriverShiftDraftEntity) -> DriverShiftDraftEntity) -> Unit,
    onSubmit: (Boolean) -> Unit,
    onClearError: () -> Unit,
) {
    val end = draft.endOdometerText.toLongOrNull()
    val distance = end?.let { value -> today.vehicle?.startOdometer?.let { start -> value - start } }
    val photoCount = today.photos.size
    ShiftListScaffold("Завершение смены", state, onClearError) {
        item { StepHeader("Проверьте данные", "Итог смены") }
        item { SummaryValue("Вернулись на склад", formatTime(today.shift?.returnedToWarehouseAt)) }
        item {
            SummaryValue(
                "Автомобиль",
                if (draft.vehicleCondition == "NO_NEW_DEFECTS") "Всё в порядке" else "Есть неисправность",
            )
        }
        item { SummaryValue("Пробег", end?.let { "${formatLong(it)} км" } ?: "—") }
        item { SummaryValue("За смену", distance?.let { "${formatLong(it)} км" } ?: "—") }
        item { SummaryValue("Топливо", draft.fuelLevelPercent?.let { "$it%" } ?: "—") }
        item { SummaryValue("Фото", photoCount.toString()) }
        if (draft.vehicleCondition == "DEFECT_REPORTED") {
            item { ErrorCard("Новая неисправность", draft.defectDescription) }
        }
        item {
            OutlinedButton(
                onClick = { onUpdateDraft { it.copy(step = "PHOTO") } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Изменить") }
        }
        item {
            PrimaryAction(
                label = if (ACTION_CLOSING_REPORT in state.pendingActions) {
                    "Отчёт сохранён · ожидает RWMS"
                } else {
                    "СОХРАНИТЬ ОТЧЁТ"
                },
                onClick = { onSubmit(false) },
                enabled = !state.submitting && ACTION_CLOSING_REPORT !in state.pendingActions,
            )
        }
    }
}

@Composable
private fun SelectionButton(
    label: String,
    selected: Boolean,
    error: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = if (selected && error) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (selected) Text("✓", color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
