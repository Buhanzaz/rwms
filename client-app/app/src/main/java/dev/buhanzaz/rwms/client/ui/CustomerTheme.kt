package dev.buhanzaz.rwms.client.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
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

private const val LOW_BATTERY_PERCENT = 20
private val Context.customerAppearanceDataStore by preferencesDataStore(name = "customer_appearance")
private val appearanceModeKey = stringPreferencesKey("appearance_mode")

private val CustomerLightColors = lightColorScheme(
    primary = Color(0xFF0B63E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF071D3D),
    secondary = Color(0xFF405F7C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E8F8),
    onSecondaryContainer = Color(0xFF172F45),
    tertiary = Color(0xFFD56400),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE1C5),
    onTertiaryContainer = Color(0xFF552200),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF111821),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111821),
    surfaceVariant = Color(0xFFE8EDF3),
    onSurfaceVariant = Color(0xFF56616F),
    surfaceContainer = Color(0xFFF0F3F7),
    surfaceContainerHigh = Color(0xFFE8EDF3),
    outline = Color(0xFF798593),
    outlineVariant = Color(0xFFCFD6DF),
    inverseSurface = Color(0xFF101B2A),
    inverseOnSurface = Color(0xFFF6F8FC),
    inversePrimary = Color(0xFF9CC2FF),
    error = Color(0xFFBA1A1A),
)

private val CustomerDarkColors = darkColorScheme(
    primary = Color(0xFF9CC2FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF154A91),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFFB6CAE0),
    onSecondary = Color(0xFF203447),
    secondaryContainer = Color(0xFF354A5E),
    onSecondaryContainer = Color(0xFFD5E7FB),
    tertiary = Color(0xFFFFB77B),
    onTertiary = Color(0xFF6B3500),
    tertiaryContainer = Color(0xFF934B00),
    onTertiaryContainer = Color(0xFFFFDCC1),
    background = Color(0xFF0D1219),
    onBackground = Color(0xFFE5EAF1),
    surface = Color(0xFF111821),
    onSurface = Color(0xFFE5EAF1),
    surfaceVariant = Color(0xFF2B3541),
    onSurfaceVariant = Color(0xFFC1C9D4),
    surfaceContainer = Color(0xFF19212C),
    surfaceContainerHigh = Color(0xFF232D39),
    outline = Color(0xFF8C98A7),
    outlineVariant = Color(0xFF3D4855),
    inverseSurface = Color(0xFF050B12),
    inverseOnSurface = Color(0xFFF6F8FC),
    inversePrimary = Color(0xFF9CC2FF),
    error = Color(0xFFFFB4AB),
)

/** User-selectable source of the CustomerApp light or dark appearance. */
enum class CustomerAppearanceMode(val title: String, val description: String) {
    SYSTEM("Как на телефоне", "Следовать системной теме"),
    LIGHT("Светлая", "Всегда использовать светлое оформление"),
    DARK("Тёмная", "Всегда использовать тёмное оформление"),
    BATTERY("По заряду", "Тёмная при заряде 20% и ниже или энергосбережении"),
}

/** Current device signals used only to resolve the battery-aware visual preference. */
internal data class CustomerBatteryStatus(
    val levelPercent: Int = 100,
    val powerSaveMode: Boolean = false,
)

/** Persists the non-authoritative CustomerApp appearance preference in local DataStore. */
internal class CustomerAppearanceStore(context: Context) {
    private val applicationContext = context.applicationContext

    val mode: Flow<CustomerAppearanceMode> = applicationContext.customerAppearanceDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[appearanceModeKey]
                ?.let { stored -> CustomerAppearanceMode.entries.firstOrNull { it.name == stored } }
                ?: CustomerAppearanceMode.SYSTEM
        }
        .distinctUntilChanged()

    suspend fun setMode(mode: CustomerAppearanceMode) {
        applicationContext.customerAppearanceDataStore.edit { preferences ->
            preferences[appearanceModeKey] = mode.name
        }
    }
}

/** Resolves the selected source without allowing battery mode to inherit the phone theme. */
internal fun resolveCustomerDarkTheme(
    mode: CustomerAppearanceMode,
    systemDark: Boolean,
    batteryLevelPercent: Int,
    powerSaveMode: Boolean,
): Boolean = when (mode) {
    CustomerAppearanceMode.SYSTEM -> systemDark
    CustomerAppearanceMode.LIGHT -> false
    CustomerAppearanceMode.DARK -> true
    CustomerAppearanceMode.BATTERY -> powerSaveMode || batteryLevelPercent in 0..LOW_BATTERY_PERCENT
}

/** Applies the logistics-aligned customer palette from the persisted appearance source. */
@Composable
fun CustomerTheme(
    appearanceMode: CustomerAppearanceMode = CustomerAppearanceMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val batteryStatus = if (appearanceMode == CustomerAppearanceMode.BATTERY) {
        rememberCustomerBatteryStatus()
    } else {
        CustomerBatteryStatus()
    }
    val darkTheme = resolveCustomerDarkTheme(
        mode = appearanceMode,
        systemDark = systemDark,
        batteryLevelPercent = batteryStatus.levelPercent,
        powerSaveMode = batteryStatus.powerSaveMode,
    )
    SyncCustomerSystemBars(darkTheme)
    MaterialTheme(
        colorScheme = if (darkTheme) CustomerDarkColors else CustomerLightColors,
        content = content,
    )
}

@Composable
private fun rememberCustomerBatteryStatus(): CustomerBatteryStatus {
    val context = LocalContext.current.applicationContext
    var status by remember(context) { mutableStateOf(context.readCustomerBatteryStatus()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                status = context.readCustomerBatteryStatus(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        status = context.readCustomerBatteryStatus()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}

private fun Context.readCustomerBatteryStatus(intent: Intent? = null): CustomerBatteryStatus {
    val batteryIntent = intent
        ?.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED }
        ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percentage = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val powerSave = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    return CustomerBatteryStatus(levelPercent = percentage, powerSaveMode = powerSave)
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
