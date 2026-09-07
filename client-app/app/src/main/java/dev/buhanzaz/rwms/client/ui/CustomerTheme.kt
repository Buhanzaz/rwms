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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.buhanzaz.rwms.client.R
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.customerAppearanceDataStore by preferencesDataStore(name = "customer_appearance")
private val appearanceModeKey = stringPreferencesKey("appearance_mode")

internal val CustomerLightColors = lightColorScheme(
    primary = Color(0xFF204B79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EFF7),
    onPrimaryContainer = Color(0xFF163A60),
    secondary = Color(0xFF3F6982),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EDF2),
    onSecondaryContainer = Color(0xFF263E4F),
    tertiary = Color(0xFF376C5C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDEEE7),
    onTertiaryContainer = Color(0xFF234B3E),
    background = Color(0xFFF5F8FB),
    onBackground = Color(0xFF1C3247),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C3247),
    surfaceVariant = Color(0xFFEEF3F7),
    onSurfaceVariant = Color(0xFF586D80),
    surfaceDim = Color(0xFFE2E6EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF0F3F6),
    surfaceContainerHigh = Color(0xFFE9EEF2),
    surfaceContainerHighest = Color(0xFFE1E7EC),
    outline = Color(0xFF7B8793),
    outlineVariant = Color(0xFFD6E0E9),
    inverseSurface = Color(0xFF282D33),
    inverseOnSurface = Color(0xFFF4F6F8),
    inversePrimary = Color(0xFFA9D9F5),
    error = Color(0xFFAE2632),
    onError = Color.White,
    errorContainer = Color(0xFFFCE9E9),
    onErrorContainer = Color(0xFF801D27),
    scrim = Color.Black,
)

internal val CustomerDarkColors = darkColorScheme(
    primary = Color(0xFFA9D9F5),
    onPrimary = Color(0xFF14395E),
    primaryContainer = Color(0xFF263D50),
    onPrimaryContainer = Color(0xFFD8EEFB),
    secondary = Color(0xFFB1CADA),
    onSecondary = Color(0xFF233B4A),
    secondaryContainer = Color(0xFF303B43),
    onSecondaryContainer = Color(0xFFE0EAF0),
    tertiary = Color(0xFFA9D5C2),
    onTertiary = Color(0xFF193E30),
    tertiaryContainer = Color(0xFF2B4037),
    onTertiaryContainer = Color(0xFFD6F0E3),
    background = Color(0xFF111315),
    onBackground = Color(0xFFF4F6F8),
    surface = Color(0xFF1C1F23),
    onSurface = Color(0xFFF4F6F8),
    surfaceVariant = Color(0xFF282D33),
    onSurfaceVariant = Color(0xFFB7C0CA),
    surfaceDim = Color(0xFF111315),
    surfaceBright = Color(0xFF34393F),
    surfaceContainerLowest = Color(0xFF16181B),
    surfaceContainerLow = Color(0xFF1C1F23),
    surfaceContainer = Color(0xFF23272C),
    surfaceContainerHigh = Color(0xFF2B3036),
    surfaceContainerHighest = Color(0xFF343A42),
    outline = Color(0xFF8995A2),
    outlineVariant = Color(0xFF414951),
    inverseSurface = Color(0xFFE9EEF2),
    inverseOnSurface = Color(0xFF20262E),
    inversePrimary = Color(0xFF204B79),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF60141B),
    errorContainer = Color(0xFF47262B),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

private val CustomerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@OptIn(ExperimentalTextApi::class)
private val CustomerHeadingFont = FontFamily(
    Font(
        resId = R.font.geist,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        resId = R.font.geist,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)
@OptIn(ExperimentalTextApi::class)
private val CustomerBodyFont = FontFamily(
    Font(
        resId = R.font.geist,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        resId = R.font.geist,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.geist,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

/** Retains the typography of the approved gradient actions while the interface uses Geist. */
@OptIn(ExperimentalTextApi::class)
internal val CustomerActionFont = FontFamily(
    Font(
        resId = R.font.golos_text,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.golos_text,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

private val CustomerTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = CustomerHeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 50.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = CustomerHeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = CustomerHeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = CustomerHeadingFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = CustomerHeadingFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = CustomerHeadingFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = CustomerHeadingFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = CustomerHeadingFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = CustomerBodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = CustomerBodyFont,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = CustomerBodyFont,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = CustomerBodyFont,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = CustomerBodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = CustomerBodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = CustomerBodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
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

/** Applies opaque neutral materials and the shared BLOCK BOX typography in both appearances. */
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
