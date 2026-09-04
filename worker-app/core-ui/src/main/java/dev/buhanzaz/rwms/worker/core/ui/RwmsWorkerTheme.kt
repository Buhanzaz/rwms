package dev.buhanzaz.rwms.worker.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val WorkerLightColors = lightColorScheme(
    primary = Color(0xFF204B79),
    onPrimary = Color.White,
    primaryContainer = Color.White.copy(alpha = 0.60f),
    onPrimaryContainer = Color(0xFF204B79),
    secondary = Color(0xFF549AC5),
    onSecondary = Color.White,
    secondaryContainer = Color.White.copy(alpha = 0.48f),
    onSecondaryContainer = Color(0xFF204B79),
    tertiary = Color(0xFF549AC5),
    onTertiary = Color.White,
    tertiaryContainer = Color.White.copy(alpha = 0.52f),
    onTertiaryContainer = Color(0xFF204B79),
    background = Color.Transparent,
    onBackground = Color(0xE6173C63),
    surface = Color.White.copy(alpha = 0.70f),
    onSurface = Color(0xE6173C63),
    surfaceVariant = Color.White.copy(alpha = 0.54f),
    onSurfaceVariant = Color(0xCC315A80),
    surfaceContainer = Color.White.copy(alpha = 0.58f),
    surfaceContainerLowest = Color.White.copy(alpha = 0.70f),
    surfaceContainerLow = Color.White.copy(alpha = 0.70f),
    surfaceContainerHigh = Color.White.copy(alpha = 0.72f),
    surfaceContainerHighest = Color.White.copy(alpha = 0.70f),
    outline = Color(0xFF204B79).copy(alpha = 0.62f),
    outlineVariant = Color.White.copy(alpha = 0.62f),
    inverseSurface = Color(0xE6204B79),
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFA9D9F5),
    error = Color(0xFFBA1A1A),
)

private val WorkerDarkColors = darkColorScheme(
    primary = Color(0xFFA9D9F5),
    onPrimary = Color(0xFF14395E),
    primaryContainer = Color(0xD9204B79),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF8CC6E9),
    onSecondary = Color(0xFF14395E),
    secondaryContainer = Color(0xC7315A80),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFA9D9F5),
    onTertiary = Color(0xFF14395E),
    tertiaryContainer = Color(0xC7315A80),
    onTertiaryContainer = Color.White,
    background = Color.Transparent,
    onBackground = Color.White,
    surface = Color(0xD9204B79),
    onSurface = Color.White,
    surfaceVariant = Color(0xC7315A80),
    onSurfaceVariant = Color(0xFFE1F1FA),
    surfaceContainer = Color(0xD1244F7A),
    surfaceContainerHigh = Color(0xE62A567F),
    outline = Color(0xFFA9D9F5).copy(alpha = 0.72f),
    outlineVariant = Color.White.copy(alpha = 0.34f),
    inverseSurface = Color.White.copy(alpha = 0.88f),
    inverseOnSurface = Color(0xFF204B79),
    inversePrimary = Color(0xFF204B79),
    error = Color(0xFFFFB4AB),
)

private val WorkerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Applies the customer application's blue-water palette to WorkerApp. */
@Composable
fun RwmsWorkerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    SyncWorkerSystemBars(darkTheme)
    MaterialTheme(
        colorScheme = if (darkTheme) WorkerDarkColors else WorkerLightColors,
        shapes = WorkerShapes,
        content = content,
    )
}

@Composable
private fun SyncWorkerSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity()
    if (view.isInEditMode || activity == null) return
    SideEffect {
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = Color.Transparent.toArgb()
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = Color.Transparent.toArgb()
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
