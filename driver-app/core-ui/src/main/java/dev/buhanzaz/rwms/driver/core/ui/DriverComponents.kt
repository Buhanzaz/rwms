package dev.buhanzaz.rwms.driver.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync

@OptIn(ExperimentalMaterial3Api::class)
/** Provides the common driver screen shell with app bar, content and transient messages. */
@Composable
fun DriverScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
            )
        },
        content = content,
    )
}

/** Displays offline, synchronizing and failed synchronization state without becoming task truth. */
@Composable
fun SyncStatusBanner(
    online: Boolean,
    stage: String?,
    completed: Int,
    total: Int,
    message: String?,
    modifier: Modifier = Modifier,
) {
    val visible = !online || stage != null && stage != "IDLE"
    if (!visible) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Sync, contentDescription = null)
            Text(
                if (online) message ?: "Синхронизация" else "Нет связи с RWMS",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Renders a compact visual label for a canonical task status. */
@Composable
fun TaskStatusChip(status: String, modifier: Modifier = Modifier) {
    val label = when (status) {
        "WAITING" -> "Ожидает"
        "IN_PROGRESS" -> "В работе"
        "PAUSED" -> "Приостановлено"
        "DONE" -> "Готово"
        "CANCELLED" -> "Отменено"
        else -> status
    }
    val color = when (status) {
        "IN_PROGRESS" -> MaterialTheme.colorScheme.primaryContainer
        "PAUSED" -> MaterialTheme.colorScheme.tertiaryContainer
        "DONE" -> Color(0xFFC6EBCD)
        "CANCELLED" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    AssistChip(
        modifier = modifier,
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color),
    )
}
