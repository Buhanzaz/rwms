package dev.buhanzaz.rwms.worker.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sync

@OptIn(ExperimentalMaterial3Api::class)
/** Provides the common worker screen shell with app bar, content and transient messages. */
@Composable
fun WorkerScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    profileMonogram: String? = null,
    profileAvatar: Bitmap? = null,
    onProfile: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    } else if (onMenu != null) {
                        IconButton(onClick = onMenu) {
                            Icon(Icons.Filled.Menu, contentDescription = "Открыть меню")
                        }
                    }
                },
                actions = {
                    actions()
                    if (onProfile != null && (!profileMonogram.isNullOrBlank() || profileAvatar != null)) {
                        WorkerProfileAvatar(
                            monogram = profileMonogram.orEmpty(),
                            avatar = profileAvatar,
                            onClick = onProfile,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WorkerGlassSurface,
                    scrolledContainerColor = WorkerGlassSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

/** Opens the worker profile from the compact monogram used by the Figma app bar. */
@Composable
private fun WorkerProfileAvatar(
    monogram: String,
    avatar: Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            if (avatar != null) {
                Image(
                    bitmap = avatar.asImageBitmap(),
                    contentDescription = "Профиль",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = monogram.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
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
