package dev.buhanzaz.rwms.manager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ManagerLightColors = lightColorScheme(
    primary = Color(0xFF0069A8),
    onPrimary = Color(0xFFF0F9FF),
    primaryContainer = Color(0xFFE5F3FA),
    onPrimaryContainer = Color(0xFF0B3548),
    secondary = Color(0xFF188653),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF4E6),
    onSecondaryContainer = Color(0xFF175C35),
    tertiary = Color(0xFFE7000B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2E2),
    onTertiaryContainer = Color(0xFF991B1B),
    error = Color(0xFFE7000B),
    onError = Color.White,
    errorContainer = Color(0xFFFFE2E2),
    onErrorContainer = Color(0xFF991B1B),
    background = Color.White,
    onBackground = Color(0xFF090B0C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF090B0C),
    surfaceVariant = Color(0xFFF1F3F3),
    onSurfaceVariant = Color(0xFF67787C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FBFB),
    surfaceContainer = Color(0xFFF4F6F6),
    surfaceContainerHigh = Color(0xFFF1F3F3),
    surfaceContainerHighest = Color(0xFFEBEEEE),
    outline = Color(0xFF9CA8AB),
    outlineVariant = Color(0xFFE3E7E8),
    inverseSurface = Color(0xFF161B1D),
    inverseOnSurface = Color(0xFFF9FBFB),
    inversePrimary = Color(0xFF66C4F1),
    scrim = Color(0xFF090B0C),
)

private val ManagerDarkColors = darkColorScheme(
    primary = Color(0xFF00598A),
    onPrimary = Color(0xFFF0F9FF),
    primaryContainer = Color(0xFF052F4A),
    onPrimaryContainer = Color(0xFFB8E4FA),
    secondary = Color(0xFF86DCAF),
    onSecondary = Color(0xFF063B26),
    secondaryContainer = Color(0xFF164E36),
    onSecondaryContainer = Color(0xFFBBF7D0),
    tertiary = Color(0xFFFF6467),
    onTertiary = Color(0xFF4C0519),
    tertiaryContainer = Color(0xFF7F1D1D),
    onTertiaryContainer = Color(0xFFFFC9C9),
    error = Color(0xFFFF6467),
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFFC9C9),
    background = Color(0xFF090B0C),
    onBackground = Color(0xFFF9FBFB),
    surface = Color(0xFF161B1D),
    onSurface = Color(0xFFF9FBFB),
    surfaceVariant = Color(0xFF22292B),
    onSurfaceVariant = Color(0xFF9CA8AB),
    surfaceContainerLowest = Color(0xFF090B0C),
    surfaceContainerLow = Color(0xFF111517),
    surfaceContainer = Color(0xFF161B1D),
    surfaceContainerHigh = Color(0xFF1C2325),
    surfaceContainerHighest = Color(0xFF22292B),
    outline = Color(0xFF67787C),
    outlineVariant = Color(0xFF30383A),
    inverseSurface = Color(0xFFF9FBFB),
    inverseOnSurface = Color(0xFF161B1D),
    inversePrimary = Color(0xFF0069A8),
    scrim = Color.Black,
)

private val ManagerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

private val ManagerTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
    ),
)

/** The shared, stable RWMS manager visual language. */
@Composable
fun ManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ManagerDarkColors else ManagerLightColors,
        shapes = ManagerShapes,
        typography = ManagerTypography,
        content = content,
    )
}
