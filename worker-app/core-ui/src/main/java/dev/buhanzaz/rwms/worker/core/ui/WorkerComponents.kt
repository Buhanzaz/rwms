package dev.buhanzaz.rwms.worker.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu

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
            WorkerTopBar(
                title = title,
                onBack = onBack,
                onMenu = onMenu,
                profileMonogram = profileMonogram,
                profileAvatar = profileAvatar,
                onProfile = onProfile,
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

/** Mirrors the client header's fixed, opaque reading surface without its filter content. */
@Composable
private fun WorkerTopBar(
    title: String,
    onBack: (() -> Unit)?,
    onMenu: (() -> Unit)?,
    profileMonogram: String?,
    profileAvatar: Bitmap?,
    onProfile: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("worker-header"),
            shape = shape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(Modifier.fillMaxWidth().height(60.dp)) {
                if (onBack != null || onMenu != null) {
                    IconButton(
                        onClick = onBack ?: checkNotNull(onMenu),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp)
                            .testTag(if (onBack == null) "menu-button" else "header-back"),
                    ) {
                        if (onBack == null) {
                            Surface(
                                modifier = Modifier.size(36.dp),
                                shape = CircleShape,
                                color = Color.Transparent,
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Открыть меню")
                                }
                            }
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 54.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                    if (onProfile != null && (!profileMonogram.isNullOrBlank() || profileAvatar != null)) {
                        WorkerProfileAvatar(
                            monogram = profileMonogram.orEmpty(),
                            avatar = profileAvatar,
                            onClick = onProfile,
                        )
                    }
                }
            }
        }
    }
}

/** Opens the worker profile from the compact monogram used by the Figma app bar. */
@Composable
private fun WorkerProfileAvatar(
    monogram: String,
    avatar: Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp).semantics { contentDescription = "Профиль" },
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
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
