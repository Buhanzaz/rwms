package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression coverage for persisted explicit light and dark customer appearance decisions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerThemeTest {
    @Test
    fun `reading surfaces and modal containers are opaque in both appearances`() {
        listOf(CustomerLightColors, CustomerDarkColors).forEach { scheme ->
            listOf(
                scheme.background, scheme.surface, scheme.surfaceVariant,
                scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer,
                scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
                scheme.primaryContainer, scheme.errorContainer,
            ).forEach { color -> assertThat(color.alpha).isEqualTo(1f) }
        }
    }

    @Test
    fun `primary and supporting text remain readable on light and dark surfaces`() {
        listOf(CustomerLightColors, CustomerDarkColors).forEach { scheme ->
            listOf(
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onSurfaceVariant to scheme.surfaceContainerHigh,
                scheme.onBackground to scheme.background,
                scheme.primary to scheme.surface,
                scheme.onErrorContainer to scheme.errorContainer,
            ).forEach { (text, surface) ->
                assertThat(contrastRatio(text, surface)).isAtLeast(4.5f)
            }
        }
        assertThat(CustomerDarkColors.background.luminance()).isLessThan(0.01f)
    }

    @Test
    fun `only explicit light and dark modes resolve and toggle`() {
        assertThat(CustomerAppearanceMode.entries)
            .containsExactly(CustomerAppearanceMode.LIGHT, CustomerAppearanceMode.DARK)
            .inOrder()
        assertThat(resolveCustomerDarkTheme(CustomerAppearanceMode.LIGHT)).isFalse()
        assertThat(resolveCustomerDarkTheme(CustomerAppearanceMode.DARK)).isTrue()
        assertThat(CustomerAppearanceMode.LIGHT.toggle()).isEqualTo(CustomerAppearanceMode.DARK)
        assertThat(CustomerAppearanceMode.DARK.toggle()).isEqualTo(CustomerAppearanceMode.LIGHT)
    }

    @Test
    fun `removed automatic preference values safely become light`() {
        assertThat(customerAppearanceModeFromStoredValue(null)).isEqualTo(CustomerAppearanceMode.LIGHT)
        assertThat(customerAppearanceModeFromStoredValue("SYSTEM")).isEqualTo(CustomerAppearanceMode.LIGHT)
        assertThat(customerAppearanceModeFromStoredValue("BATTERY")).isEqualTo(CustomerAppearanceMode.LIGHT)
        assertThat(customerAppearanceModeFromStoredValue("unsupported")).isEqualTo(CustomerAppearanceMode.LIGHT)
    }

    @Test
    fun `appearance store round trips the manual preference`() = runTest {
        val store = CustomerAppearanceStore(RuntimeEnvironment.getApplication())

        store.setMode(CustomerAppearanceMode.DARK)
        assertThat(store.mode.first()).isEqualTo(CustomerAppearanceMode.DARK)

        store.setMode(CustomerAppearanceMode.LIGHT)
        assertThat(store.mode.first()).isEqualTo(CustomerAppearanceMode.LIGHT)
    }
    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
