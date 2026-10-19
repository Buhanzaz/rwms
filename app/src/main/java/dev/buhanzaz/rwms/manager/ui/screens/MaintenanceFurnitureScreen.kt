package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.ui.MaintenanceFurnitureCompletionAction
import dev.buhanzaz.rwms.manager.ui.MaintenanceFurnitureEditorState
import dev.buhanzaz.rwms.manager.ui.hasObservedFurniture
import dev.buhanzaz.rwms.manager.ui.maintenanceFurnitureCompositionValidationError
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerPanel
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold

/**
 * Full-screen final-composition editor shared by estimate and repair work.
 * It deliberately exposes no stock controls: logistics calculates the physical delta from
 * the submitted complete total.
 */
@Composable
fun MaintenanceFurnitureScreen(
    editor: MaintenanceFurnitureEditorState?,
    busy: Boolean,
    onBack: () -> Unit,
    onQuantityChange: (equipmentId: String, quantity: String) -> Unit,
    onComplete: (MaintenanceFurnitureCompletionAction) -> Unit,
) {
    if (editor == null) {
        ManagerScreenScaffold(title = "Наполнение бытовки", onBack = onBack) { padding ->
            EmptyState(
                title = "Наполнение не загружено",
                description = "Вернитесь к смете или ремонту и откройте наполнение бытовки снова.",
                actionLabel = "Назад",
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    val compositionError = editor.maintenanceFurnitureCompositionValidationError()
    var completionChoiceOpen by remember(editor.rentalItem.id, editor.mode) {
        mutableStateOf(false)
    }

    ManagerScreenScaffold(
        title = "Наполнение · ${editor.rentalItem.number}",
        onBack = onBack,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ManagerPanel {
                        Text(
                            text = "Итоговое наполнение",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Укажите, сколько каждой позиции должно остаться в бытовке. " +
                                "Сервер сам создаст задание только для разницы.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (editor.furnitureCatalog.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Активной мебели нет",
                            description = "В справочнике выбранного склада нет активных позиций мебели.",
                        )
                    }
                } else {
                    items(editor.furnitureCatalog, key = EquipmentCatalogItemDto::id) { equipment ->
                        MaintenanceFurnitureQuantityRow(
                            equipment = equipment,
                            quantity = editor.quantities[equipment.id].orEmpty(),
                            enabled = !busy,
                            onQuantityChange = { quantity ->
                                onQuantityChange(equipment.id, quantity)
                            },
                        )
                    }
                }
                if (compositionError != null) {
                    item {
                        Text(
                            text = compositionError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Button(
                onClick = {
                    if (editor.hasObservedFurniture()) {
                        completionChoiceOpen = true
                    } else {
                        onComplete(MaintenanceFurnitureCompletionAction.KEEP_IN_CABIN)
                    }
                },
                enabled = !busy && compositionError == null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Завершить")
            }
        }
    }

    if (completionChoiceOpen) {
        MaintenanceFurnitureCompletionDialog(
            compositionError = compositionError,
            busy = busy,
            onDismiss = { completionChoiceOpen = false },
            onComplete = onComplete,
        )
    }
}

@Composable
private fun MaintenanceFurnitureQuantityRow(
    equipment: EquipmentCatalogItemDto,
    quantity: String,
    enabled: Boolean,
    onQuantityChange: (String) -> Unit,
) {
    val currentQuantity = maintenanceFurnitureQuantityForControls(quantity)
    ManagerPanel {
        Text(
            text = equipment.name,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    onQuantityChange((currentQuantity - 1L).coerceAtLeast(0L).toString())
                },
                enabled = enabled && currentQuantity > 0L,
                modifier = Modifier
                    .width(64.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = quantity,
                onValueChange = onQuantityChange,
                modifier = Modifier.widthIn(min = 88.dp, max = 132.dp),
                enabled = enabled,
                label = { Text("Кол-во") },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
            )
            FilledTonalButton(
                onClick = {
                    val next = if (currentQuantity == Long.MAX_VALUE) {
                        currentQuantity
                    } else {
                        currentQuantity + 1L
                    }
                    onQuantityChange(next.toString())
                },
                enabled = enabled && currentQuantity < Long.MAX_VALUE,
                modifier = Modifier
                    .width(64.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun MaintenanceFurnitureCompletionDialog(
    compositionError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onComplete: (MaintenanceFurnitureCompletionAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!busy) onDismiss()
        },
        title = { Text("Что сделать с мебелью?") },
        text = {
            Text(
                "Оставьте выбранный итоговый состав в бытовке или сформируйте пустой состав, " +
                    "чтобы переместить мебель на склад.",
            )
        },
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onComplete(MaintenanceFurnitureCompletionAction.KEEP_IN_CABIN)
                    },
                    enabled = !busy && compositionError == null,
                ) {
                    Text("Оставить в бытовке")
                }
                OutlinedButton(
                    onClick = {
                        onComplete(MaintenanceFurnitureCompletionAction.MOVE_TO_STOCK)
                    },
                    enabled = !busy,
                ) {
                    Text("Переместить на склад")
                }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text("Отмена")
                }
            }
        },
        dismissButton = {},
    )
}

internal fun maintenanceFurnitureQuantityForControls(value: String): Long =
    value.trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
