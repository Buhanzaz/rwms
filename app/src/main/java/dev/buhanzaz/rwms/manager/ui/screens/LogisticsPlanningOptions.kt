package dev.buhanzaz.rwms.manager.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.manager.ui.LOGISTICS_PLANNING_MODE_AUTO
import dev.buhanzaz.rwms.manager.ui.LOGISTICS_PLANNING_MODE_FIXED_DATE
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState
import dev.buhanzaz.rwms.manager.ui.logisticsPlanningValidationError
import dev.buhanzaz.rwms.manager.ui.withForceCapitalRepair
import dev.buhanzaz.rwms.manager.ui.withLogisticsPlanningMode
import dev.buhanzaz.rwms.manager.ui.withLogisticsScheduledDate
import dev.buhanzaz.rwms.manager.ui.withMovementToRepair
import java.time.LocalDate

/**
 * Priority belongs to the repair and to both logistics legs selected by movement to repair. The
 * server assigns repair-board priority 1 once inbound driver delivery finishes.
 */
@Composable
internal fun LogisticsTaskPriorityOptions(
    editor: MaintenanceEditorState,
    enabled: Boolean,
    onEdit: ((MaintenanceEditorState) -> MaintenanceEditorState) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Приоритет ремонта", style = MaterialTheme.typography.titleMedium)
        Text(
            "Выберите значение от 1 до 5. При входящем перемещении это же значение " +
                "передаётся ремонту и заданию логистики; после завершения доставки сервер " +
                "назначает задаче ремонтной очереди приоритет 1.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Без перемещения на ремонт выбранный приоритет применяется напрямую к ремонту. " +
                "С перемещением после выполнения ремонта автоматически создаётся задание " +
                "на перемещение с ремонта с этим же приоритетом.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (1..5).forEach { priority ->
                FilterChip(
                    selected = editor.priority == priority,
                    onClick = { onEdit { current -> current.copy(priority = priority) } },
                    enabled = enabled,
                    label = { Text(priority.toString()) },
                )
            }
        }
    }
}

/**
 * Captures the manager's explicit capital-repair choice. Catalog WORK policy remains an
 * independent server-side reason to classify the repair as capital.
 */
@Composable
internal fun ForceCapitalRepairOption(
    editor: MaintenanceEditorState,
    enabled: Boolean,
    onEdit: ((MaintenanceEditorState) -> MaintenanceEditorState) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = editor.forceCapitalRepair,
            onCheckedChange = { forceCapitalRepair ->
                onEdit { current -> current.withForceCapitalRepair(forceCapitalRepair) }
            },
            enabled = enabled,
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                "Направить на капитальный ремонт",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Сервер направит работу в существующий цикл капитального ремонта. " +
                    "Каталог также может потребовать его независимо от этого выбора.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The maintenance API owns the inbound logistics request. This component only captures its
 * planning; the server owns repair-board task creation and its post-delivery priority.
 */
@Composable
internal fun RepairMovementLogisticsOptions(
    editor: MaintenanceEditorState,
    enabled: Boolean,
    onEdit: ((MaintenanceEditorState) -> MaintenanceEditorState) -> Unit,
) {
    val context = LocalContext.current
    val movementSelected = editor.movementToRepair
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = movementSelected,
            onCheckedChange = { required ->
                onEdit { current -> current.withMovementToRepair(required) }
            },
            enabled = enabled,
        )
        Text(
            "Создать перемещение на ремонт",
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (!movementSelected) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "После завершения входящей доставки сервер назначит задаче ремонтной очереди " +
                "приоритет 1, а после выполнения ремонта автоматически создаст перемещение " +
                "с ремонта.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Добавление в очередь", style = MaterialTheme.typography.titleSmall)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = editor.logisticsPlanningMode == LOGISTICS_PLANNING_MODE_AUTO,
                onClick = {
                    onEdit { current ->
                        current.withLogisticsPlanningMode(LOGISTICS_PLANNING_MODE_AUTO)
                    }
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Автоматически добавить в очередь") },
            )
            FilterChip(
                selected = editor.logisticsPlanningMode == LOGISTICS_PLANNING_MODE_FIXED_DATE,
                onClick = {
                    onEdit { current ->
                        current.withLogisticsPlanningMode(LOGISTICS_PLANNING_MODE_FIXED_DATE)
                    }
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Выбрать конкретную дату") },
            )
        }
        if (editor.logisticsPlanningMode == LOGISTICS_PLANNING_MODE_FIXED_DATE) {
            OutlinedButton(
                onClick = {
                    val initial = editor.logisticsScheduledDate
                        ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
                        ?: LocalDate.now()
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            val date = LocalDate.of(year, month + 1, dayOfMonth).toString()
                            onEdit { current -> current.withLogisticsScheduledDate(date) }
                        },
                        initial.year,
                        initial.monthValue - 1,
                        initial.dayOfMonth,
                    ).show()
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    editor.logisticsScheduledDate
                        ?.takeIf(String::isNotBlank)
                        ?.let { date -> "Дата перемещения: $date" }
                        ?: "Выбрать дату перемещения",
                )
            }
        }
        editor.logisticsPlanningValidationError()?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
