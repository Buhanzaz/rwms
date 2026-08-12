package dev.buhanzaz.rwms.driver.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0059B2),
    secondary = Color(0xFF3E5F88),
    tertiary = Color(0xFF745A00),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    secondary = Color(0xFFB4C8E8),
    tertiary = Color(0xFFFFDEA1),
)

/** Applies the RWMS driver color schemes and Material typography to [content]. */
@Composable
fun RwmsDriverTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
