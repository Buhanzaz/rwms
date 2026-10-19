package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.manager.ui.components.ManagerMenuCard
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold

/** Presents the manager entry points for return, shipment and transfer workflows. */
@Composable
fun LogisticsMenuScreen(
    onBack: () -> Unit,
    onOpenReturns: () -> Unit,
    onOpenShipments: () -> Unit,
    onOpenTransfers: () -> Unit,
) {
    ManagerScreenScaffold(title = "Логистика", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Операции склада", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Выберите доступный сценарий. Недоступные операции не создают документов.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                ManagerMenuCard(
                    title = "Возвраты",
                    description = "Осмотр, фото, комплектность и смета",
                    accent = Color(0xFF2563EB),
                    onClick = onOpenReturns,
                )
            }
            item {
                ManagerMenuCard(
                    title = "Отгрузки",
                    description = "Планирование, мебель и подтверждение отгрузки",
                    accent = Color(0xFFF59E0B),
                    onClick = onOpenShipments,
                )
            }
            item {
                ManagerMenuCard(
                    title = "Перемещения",
                    description = "Между складами, отправка и приёмка с фото",
                    accent = Color(0xFF8B5CF6),
                    onClick = onOpenTransfers,
                )
            }
        }
    }
}
