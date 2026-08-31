package dev.buhanzaz.rwms.client.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression coverage for persisted manual and battery-aware customer appearance decisions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerThemeTest {
    @Test
    fun `manual and system modes ignore unrelated battery state`() {
        assertThat(
            resolveCustomerDarkTheme(
                mode = CustomerAppearanceMode.SYSTEM,
                systemDark = true,
                batteryLevelPercent = 90,
                powerSaveMode = false,
            ),
        ).isTrue()
        assertThat(
            resolveCustomerDarkTheme(
                mode = CustomerAppearanceMode.LIGHT,
                systemDark = true,
                batteryLevelPercent = 5,
                powerSaveMode = true,
            ),
        ).isFalse()
        assertThat(
            resolveCustomerDarkTheme(
                mode = CustomerAppearanceMode.DARK,
                systemDark = false,
                batteryLevelPercent = 100,
                powerSaveMode = false,
            ),
        ).isTrue()
    }

    @Test
    fun `battery mode becomes dark at threshold or during power saving`() {
        assertThat(
            resolveCustomerDarkTheme(
                mode = CustomerAppearanceMode.BATTERY,
                systemDark = true,
                batteryLevelPercent = 21,
                powerSaveMode = false,
            ),
        ).isFalse()
        assertThat(
            resolveCustomerDarkTheme(
                mode = CustomerAppearanceMode.BATTERY,
                systemDark = false,
                batteryLevelPercent = 20,
                powerSaveMode = false,
            ),
        ).isTrue()
        assertThat(
            resolveCustomerDarkTheme(
                mode = CustomerAppearanceMode.BATTERY,
                systemDark = false,
                batteryLevelPercent = 80,
                powerSaveMode = true,
            ),
        ).isTrue()
    }

    @Test
    fun `appearance store round trips the manual preference`() = runTest {
        val store = CustomerAppearanceStore(RuntimeEnvironment.getApplication())

        store.setMode(CustomerAppearanceMode.DARK)
        assertThat(store.mode.first()).isEqualTo(CustomerAppearanceMode.DARK)

        store.setMode(CustomerAppearanceMode.SYSTEM)
        assertThat(store.mode.first()).isEqualTo(CustomerAppearanceMode.SYSTEM)
    }
}
