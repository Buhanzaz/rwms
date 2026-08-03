package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.manager.R
import dev.buhanzaz.rwms.manager.ui.components.ManagerMenuCard
import dev.buhanzaz.rwms.manager.ui.components.ManagerScreenScaffold

@Composable
fun ManagerMainMenuScreen(
    onLogout: () -> Unit,
    pendingUploadCount: Int,
    onOpenUploads: () -> Unit,
    onOpenLogistics: () -> Unit,
    onOpenInventory: () -> Unit,
    onOpenMaintenance: () -> Unit,
) {
    ManagerScreenScaffold(
        title = "RWMS",
        titleIcon = {
            Image(
                painter = painterResource(R.drawable.rwms_logo),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                contentScale = ContentScale.Fit,
            )
        },
        actions = { TextButton(onClick = onLogout) { Text("Выйти") } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(managerMenuItems(pendingUploadCount)) { item ->
                ManagerMenuCard(
                    title = item.title,
                    description = item.description,
                    accent = item.accent,
                    onClick = when (item.destination) {
                        ManagerMenuDestination.Logistics -> onOpenLogistics
                        ManagerMenuDestination.Inventory -> onOpenInventory
                        ManagerMenuDestination.Maintenance -> onOpenMaintenance
                        ManagerMenuDestination.Uploads -> onOpenUploads
                    },
                )
            }
        }
    }
}

private data class ManagerMenuItem(
    val title: String,
    val description: String,
    val accent: Color,
    val destination: ManagerMenuDestination,
)

private enum class ManagerMenuDestination { Uploads, Logistics, Inventory, Maintenance }

private fun managerMenuItems(pendingUploadCount: Int) = listOf(
    ManagerMenuItem(
        title = "Загрузки",
        description = if (pendingUploadCount == 0) {
            "Нет ожидающих отправок"
        } else {
            "Операций в работе: $pendingUploadCount"
        },
        accent = Color(0xFF0069A8),
        destination = ManagerMenuDestination.Uploads,
    ),
    ManagerMenuItem(
        title = "Логистика",
        description = "Возвраты, приемка и документы",
        accent = Color(0xFF0069A8),
        destination = ManagerMenuDestination.Logistics,
    ),
    ManagerMenuItem(
        title = "Инвентаризация",
        description = "Активная сессия и история проверок",
        accent = Color(0xFF0069A8),
        destination = ManagerMenuDestination.Inventory,
    ),
    ManagerMenuItem(
        title = "Ремонтный цикл",
        description = "Сметы, ремонты, очередь и приёмка",
        accent = Color(0xFF0069A8),
        destination = ManagerMenuDestination.Maintenance,
    ),
)
