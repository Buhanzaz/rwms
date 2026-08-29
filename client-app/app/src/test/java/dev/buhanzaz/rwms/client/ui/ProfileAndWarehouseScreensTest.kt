package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose regressions for mutable profile fields, avatar gating, and warehouse preference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileAndWarehouseScreensTest {
    /** Compose semantics environment for the focused customer-profile assertions. */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `existing profile fields are editable and save preserves identity and version`() {
        var saved: CustomerProfile? = null
        val profile = existingProfile()

        composeRule.setContent {
            CustomerTheme {
                ProfileFormScreen(
                    existing = profile,
                    busy = false,
                    selectedWarehouse = warehouse(),
                    onSave = { saved = it },
                )
            }
        }

        composeRule.onNodeWithTag("profile-first-name").assertIsEnabled().performTextReplacement("Пётр")
        composeRule.onNodeWithTag("profile-screen").performScrollToNode(hasTestTag("profile-save"))
        composeRule.onNodeWithTag("profile-save").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertThat(saved?.id).isEqualTo(profile.id)
            assertThat(saved?.version).isEqualTo(7L)
            assertThat(saved?.entityType).isEqualTo(CustomerEntityType.INDIVIDUAL)
            assertThat(saved?.firstName).isEqualTo("Пётр")
        }
    }

    @Test
    fun `avatar action explains and enforces warehouse requirement`() {
        composeRule.setContent {
            CustomerTheme {
                ProfileFormScreen(
                    existing = existingProfile(),
                    busy = false,
                    selectedWarehouse = null,
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithTag("profile-avatar-preview").assertExists()
        composeRule.onNodeWithTag("profile-avatar-picker").assertIsNotEnabled()
        composeRule.onNodeWithText("Чтобы загрузить аватар, сначала выберите склад.").assertExists()
    }

    @Test
    fun `warehouse step forwards checked remember preference`() {
        var remembered: Boolean? = null
        var selectedId: String? = null

        composeRule.setContent {
            CustomerTheme {
                WarehouseScreen(
                    warehouses = listOf(warehouse()),
                    busy = false,
                    onSelect = { selected, remember ->
                        selectedId = selected.id
                        remembered = remember
                    },
                    onLogout = {},
                )
            }
        }

        composeRule.onNodeWithTag("remember-warehouse").performClick()
        composeRule.onNodeWithText("СПБ").performClick()

        composeRule.runOnIdle {
            assertThat(selectedId).isEqualTo("warehouse-spb")
            assertThat(remembered).isTrue()
        }
    }

    private fun existingProfile(): CustomerProfile = CustomerProfile(
        id = "profile-a",
        version = 7,
        entityType = CustomerEntityType.INDIVIDUAL,
        firstName = "Иван",
        lastName = "Петров",
        phone = "+79990000000",
    )

    private fun warehouse(): CustomerWarehouse = CustomerWarehouse(
        id = "warehouse-spb",
        name = "СПБ",
        city = "Санкт-Петербург",
        timezone = "Europe/Moscow",
        depotLatitude = 59.763806,
        depotLongitude = 30.471798,
    )
}
