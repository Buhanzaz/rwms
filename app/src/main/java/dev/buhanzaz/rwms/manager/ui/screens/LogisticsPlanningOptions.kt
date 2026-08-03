package dev.buhanzaz.rwms.manager.ui.screens

import android.app.DatePickerDialog
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
import dev.buhanzaz.rwms.manager.ui.withLogisticsPlanningMode
import dev.buhanzaz.rwms.manager.ui.withLogisticsScheduledDate
import dev.buhanzaz.rwms.manager.ui.withMovementToRepair
import java.time.LocalDate

/**
 * The maintenance API owns the logistics request. This component deliberately knows nothing
 * about task-board queues: the selected warehouse resolves the movement through logistics.
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
            "После доставки бытовка появится в очереди ремонтных заданий с системным специальным приоритетом.",
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
