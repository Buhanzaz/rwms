package dev.buhanzaz.rwms.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CustomerLightColors = lightColorScheme(
    primary = Color(0xFF116B8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1EEFA),
    onPrimaryContainer = Color(0xFF073546),
    secondary = Color(0xFF4A626C),
    surface = Color(0xFFF8FAFB),
    surfaceContainer = Color(0xFFEEF2F4),
    error = Color(0xFFBA1A1A),
)

private val CustomerDarkColors = darkColorScheme(
    primary = Color(0xFF78D0F3),
    onPrimary = Color(0xFF003546),
    primaryContainer = Color(0xFF004D66),
    secondary = Color(0xFFB2CBD6),
)

/** Applies the RWMS customer color system while respecting the device appearance. */
@Composable
fun CustomerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) CustomerDarkColors else CustomerLightColors,
        content = content,
    )
}
