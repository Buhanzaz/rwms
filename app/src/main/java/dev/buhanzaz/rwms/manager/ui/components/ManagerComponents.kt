package dev.buhanzaz.rwms.manager.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.manager.network.WarehouseDto

data class ManagerHeaderState(
    val warehouses: List<WarehouseDto>,
    val selectedWarehouseId: String?,
    val warehouseSelectionLocked: Boolean,
    val serverReachable: Boolean,
    val onSelectWarehouse: (String) -> Unit,
    val onCheckServer: () -> Unit,
)

val LocalManagerHeaderState = compositionLocalOf<ManagerHeaderState?> { null }

internal const val MANAGER_BACK_GLYPH = "←"
internal const val MANAGER_BACK_CONTENT_DESCRIPTION = "Назад"

internal data class ManagerWarehouseSelection(
    val warehouseId: String,
    val label: String,
)

internal fun managerWarehouseSelections(
    warehouses: List<WarehouseDto>,
): List<ManagerWarehouseSelection> = warehouses.map { warehouse ->
    ManagerWarehouseSelection(
        warehouseId = warehouse.id,
        label = warehouse.managerWarehouseLabel(),
    )
}

internal fun managerAlternativeWarehouseSelections(
    warehouses: List<WarehouseDto>,
    selectedWarehouseId: String?,
): List<ManagerWarehouseSelection> = managerWarehouseSelections(warehouses)
    .filterNot { it.warehouseId == selectedWarehouseId }

internal fun managerHeaderWarehouseLabel(selectedWarehouse: WarehouseDto?): String {
    return selectedWarehouse?.managerWarehouseLabel() ?: "Склад"
}

private fun WarehouseDto.managerWarehouseLabel(): String = name.trim()
    .ifBlank { city.trim() }
    .ifBlank { "Склад" }

@Composable
fun ManagerScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    titleIcon: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = MANAGER_BACK_CONTENT_DESCRIPTION
                                },
                        ) {
                            Text(
                                text = MANAGER_BACK_GLYPH,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                    titleIcon?.invoke()
                    Text(
                        text = title,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (titleIcon == null) 8.dp else 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    ManagerHeaderControls()
                    actions()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
fun ManagerPanel(
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val panelModifier = if (onClick == null) {
        modifier
    } else {
        modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
    }
    Card(
        modifier = panelModifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun ManagerHeaderControls() {
    val state = LocalManagerHeaderState.current ?: return
    var expanded by remember(state.selectedWarehouseId, state.warehouses) {
        mutableStateOf(false)
    }
    val selected = state.warehouses.firstOrNull { it.id == state.selectedWarehouseId }
    val otherWarehouseSelections = managerAlternativeWarehouseSelections(
        warehouses = state.warehouses,
        selectedWarehouseId = state.selectedWarehouseId,
    )
    var warehouseControlWidthPx by remember { mutableStateOf(0) }
    val warehouseControlWidth = with(LocalDensity.current) { warehouseControlWidthPx.toDp() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClick = state.onCheckServer,
                )
                .semantics {
                    this.contentDescription = if (state.serverReachable) {
                        "Связь с сервером есть"
                    } else {
                        "Связи с сервером нет"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.serverReachable) {
                            Color(0xFF22C55E)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    ),
            )
        }
        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = !state.warehouseSelectionLocked && otherWarehouseSelections.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .onGloballyPositioned { coordinates ->
                        warehouseControlWidthPx = coordinates.size.width
                    },
            ) {
                Text(
                    text = managerHeaderWarehouseLabel(selected),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = if (warehouseControlWidthPx > 0) {
                    Modifier.width(warehouseControlWidth)
                } else {
                    Modifier
                },
            ) {
                otherWarehouseSelections.forEach { selection ->
                    DropdownMenuItem(
                        text = {
                            Text(selection.label)
                        },
                        onClick = {
                            expanded = false
                            state.onSelectWarehouse(selection.warehouseId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun ManagerMenuCard(
    title: String,
    description: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant
    ManagerPanel(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 36.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(if (enabled) accent else MaterialTheme.colorScheme.outline),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (enabled) {
                Text("›", style = MaterialTheme.typography.headlineSmall, color = accent)
            } else {
                Text("Позже", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    emphasis: StatusPillEmphasis = StatusPillEmphasis.Neutral,
) {
    val (background, foreground) = when (emphasis) {
        StatusPillEmphasis.Positive -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
        StatusPillEmphasis.Warning -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        StatusPillEmphasis.Neutral -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        fontWeight = FontWeight.Medium,
    )
}

enum class StatusPillEmphasis { Positive, Warning, Neutral }

@Composable
fun BusyOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f))
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Выполняем операцию…")
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
