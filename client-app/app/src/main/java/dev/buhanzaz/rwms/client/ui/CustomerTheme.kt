package dev.buhanzaz.rwms.client.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.customerAppearanceDataStore by preferencesDataStore(name = "customer_appearance")
private val appearanceModeKey = stringPreferencesKey("appearance_mode")

private val CustomerLightColors = lightColorScheme(
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
    onBackground = Color(0xFF173C63),
    surface = Color.White.copy(alpha = 0.86f),
    onSurface = Color(0xFF173C63),
    surfaceVariant = Color.White.copy(alpha = 0.68f),
    onSurfaceVariant = Color(0xFF315A80),
    surfaceContainer = Color.White.copy(alpha = 0.76f),
    surfaceContainerHigh = Color.White.copy(alpha = 0.92f),
    outline = Color(0xFF204B79).copy(alpha = 0.62f),
    outlineVariant = Color.White.copy(alpha = 0.62f),
    inverseSurface = Color(0xE6204B79),
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFA9D9F5),
    error = Color(0xFFBA1A1A),
)

private val CustomerDarkColors = darkColorScheme(
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
    surface = Color(0xEB173C63),
    onSurface = Color.White,
    surfaceVariant = Color(0xD9244F7A),
    onSurfaceVariant = Color(0xFFE1F1FA),
    surfaceContainer = Color(0xE6204B79),
    surfaceContainerHigh = Color(0xF2244F7A),
    outline = Color(0xFFA9D9F5).copy(alpha = 0.72f),
    outlineVariant = Color.White.copy(alpha = 0.34f),
    inverseSurface = Color.White.copy(alpha = 0.88f),
    inverseOnSurface = Color(0xFF204B79),
    inversePrimary = Color(0xFF204B79),
    error = Color(0xFFFFB4AB),
)

private val CustomerShapes = Shapes(
    extraSmall = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val CustomerTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

/** Explicit CustomerApp appearance choices; no device-driven appearance is supported. */
enum class CustomerAppearanceMode(val toggleActionTitle: String) {
    LIGHT("Включить тёмную тему"),
    DARK("Включить светлую тему"),
}

/** Returns the other explicit appearance used by the one-tap drawer action. */
internal fun CustomerAppearanceMode.toggle(): CustomerAppearanceMode = when (this) {
    CustomerAppearanceMode.LIGHT -> CustomerAppearanceMode.DARK
    CustomerAppearanceMode.DARK -> CustomerAppearanceMode.LIGHT
}

/** Reads a persisted appearance while mapping removed automatic options to the safe light default. */
internal fun customerAppearanceModeFromStoredValue(storedValue: String?): CustomerAppearanceMode =
    CustomerAppearanceMode.entries.firstOrNull { it.name == storedValue } ?: CustomerAppearanceMode.LIGHT

/** Persists the non-authoritative CustomerApp appearance preference in local DataStore. */
internal class CustomerAppearanceStore(context: Context) {
    private val applicationContext = context.applicationContext

    val mode: Flow<CustomerAppearanceMode> = applicationContext.customerAppearanceDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            customerAppearanceModeFromStoredValue(preferences[appearanceModeKey])
        }
        .distinctUntilChanged()

    suspend fun setMode(mode: CustomerAppearanceMode) {
        applicationContext.customerAppearanceDataStore.edit { preferences ->
            preferences[appearanceModeKey] = mode.name
        }
    }
}

/** Resolves the explicit persisted choice without consulting device or battery state. */
internal fun resolveCustomerDarkTheme(mode: CustomerAppearanceMode): Boolean =
    mode == CustomerAppearanceMode.DARK

/** Applies the supplied blue-water palette and rounded component language. */
@Composable
fun CustomerTheme(
    appearanceMode: CustomerAppearanceMode = CustomerAppearanceMode.LIGHT,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveCustomerDarkTheme(appearanceMode)
    SyncCustomerSystemBars(darkTheme)
    MaterialTheme(
        colorScheme = if (darkTheme) CustomerDarkColors else CustomerLightColors,
        shapes = CustomerShapes,
        typography = CustomerTypography,
        content = content,
    )
}

@Composable
private fun SyncCustomerSystemBars(darkTheme: Boolean) {
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
