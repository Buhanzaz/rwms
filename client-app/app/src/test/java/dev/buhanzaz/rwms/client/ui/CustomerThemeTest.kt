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

/** Regression coverage for persisted explicit light and dark customer appearance decisions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerThemeTest {
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
}
