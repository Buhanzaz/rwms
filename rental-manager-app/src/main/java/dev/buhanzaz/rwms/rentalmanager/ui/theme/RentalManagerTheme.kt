package dev.buhanzaz.rwms.rentalmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE8FF),
    onPrimaryContainer = Color(0xFF0B255B),
    secondary = Color(0xFF42618E),
    secondaryContainer = Color(0xFFD8E5FF),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE1E6EF),
    outline = Color(0xFF737782),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002E6A),
    primaryContainer = Color(0xFF10458F),
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFFABC7F5),
    secondaryContainer = Color(0xFF29496F),
    background = Color(0xFF0C141F),
    surface = Color(0xFF111A25),
    surfaceVariant = Color(0xFF29313D),
    outline = Color(0xFF8D919C),
    error = Color(0xFFFFB4AB),
)

/** Restrained RWMS palette shared by compact bottom navigation and wide navigation rail layouts. */
@Composable
fun RentalManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
