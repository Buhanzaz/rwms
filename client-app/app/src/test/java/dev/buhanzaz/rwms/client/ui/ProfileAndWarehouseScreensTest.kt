package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
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
        composeRule.onNodeWithTag("profile-screen").performScrollToNode(hasTestTag("profile-notification-settings"))
        composeRule.onNodeWithTag("profile-notification-settings").assertExists()
        composeRule.onNodeWithTag("profile-notification-action").assertIsEnabled()
    }

    @Test
    fun `avatar action remains available when profile opened before catalog selection`() {
        composeRule.setContent {
            CustomerTheme {
                ProfileFormScreen(
                    existing = existingProfile(),
                    busy = false,
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithTag("profile-avatar-preview").assertExists()
        composeRule.onNodeWithTag("profile-avatar-picker").assertIsEnabled()
        val avatar = composeRule.onNodeWithTag("profile-avatar-preview").fetchSemanticsNode().boundsInRoot
        val camera = composeRule.onNodeWithTag("profile-avatar-picker").fetchSemanticsNode().boundsInRoot
        assertThat(avatar.overlaps(camera)).isTrue()
        composeRule.onNodeWithText("Загрузить аватар").assertDoesNotExist()
        composeRule.onNodeWithText("Чтобы загрузить аватар, сначала выберите склад.").assertDoesNotExist()
    }

    @Test
    fun `profile version update after avatar binding preserves unsaved contact edits`() {
        var profile by mutableStateOf(existingProfile())
        var saved: CustomerProfile? = null
        composeRule.setContent {
            CustomerTheme { ProfileFormScreen(existing = profile, busy = false, onSave = { saved = it }) }
        }
        composeRule.onNodeWithTag("profile-first-name").performTextReplacement("Пётр")
        composeRule.runOnIdle { profile = profile.copy(version = 8) }
        composeRule.onNodeWithTag("profile-screen").performScrollToNode(hasTestTag("profile-save"))
        composeRule.onNodeWithTag("profile-save").performClick()
        composeRule.runOnIdle {
            assertThat(saved?.firstName).isEqualTo("Пётр")
            assertThat(saved?.version).isEqualTo(8L)
        }
    }

    @Test
    fun `profile displays saved registration values without a repeated identity step`() {
        val profile = existingProfile().copy(email = "client@example.test")
        composeRule.setContent {
            CustomerTheme { ProfileFormScreen(existing = profile, busy = false, onSave = {}) }
        }
        composeRule.onNodeWithText("Физическое лицо").assertDoesNotExist()
        composeRule.onNodeWithText("Данные клиента").assertDoesNotExist()
        composeRule.onNodeWithText("Иван").assertExists()
        composeRule.onNodeWithTag("profile-screen").performScrollToNode(hasTestTag("profile-last-name"))
        composeRule.onNodeWithText("Петров").assertExists()
        composeRule.onNodeWithTag("profile-screen").performScrollToNode(hasTestTag("profile-email"))
        composeRule.onNodeWithText("client@example.test").assertExists()
        composeRule.onNodeWithTag("profile-screen").performScrollToNode(hasTestTag("profile-phone"))
        composeRule.onNodeWithText("+79990000000").assertExists()
    }

    @Test
    fun `profile displays save failures`() {
        composeRule.setContent {
            CustomerTheme {
                ProfileFormScreen(
                    existing = existingProfile(),
                    busy = false,
                    onSave = {},
                    errorMessage = "Не удалось сохранить профиль",
                )
            }
        }
        composeRule.onNodeWithTag("profile-screen").performScrollToNode(hasTestTag("profile-error"))
        composeRule.onNodeWithText("Не удалось сохранить профиль").assertExists()
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
                    onMenu = {},
                    onProfile = {},
                )
            }
        }

        composeRule.onNodeWithTag("warehouse-location-icon", useUnmergedTree = true).assertExists()
        val header = composeRule.onNodeWithTag("customer-header").fetchSemanticsNode().boundsInRoot
        val remember = composeRule.onNodeWithTag("remember-warehouse").fetchSemanticsNode().boundsInRoot
        val city = composeRule.onNodeWithTag("warehouse-option-warehouse-spb").fetchSemanticsNode().boundsInRoot
        assertThat(remember.top).isAtLeast(header.bottom)
        assertThat(remember.bottom).isAtMost(city.top)
        composeRule.onNodeWithTag("remember-warehouse").performClick()
        composeRule.onNodeWithText("Санкт-Петербург").performClick()

        composeRule.runOnIdle {
            assertThat(selectedId).isEqualTo("warehouse-spb")
            assertThat(remembered).isTrue()
        }
    }

    @Test
    fun `city loading does not announce an empty warehouse list`() {
        var busy by mutableStateOf(true)
        composeRule.setContent {
            CustomerTheme {
                WarehouseScreen(
                    warehouses = emptyList(), busy = busy, onSelect = { _, _ -> },
                    onMenu = null, onProfile = {},
                )
            }
        }
        composeRule.onNodeWithTag("warehouse-loading").assertExists()
        composeRule.onNodeWithText("Нет доступных городов").assertDoesNotExist()
        composeRule.runOnIdle { busy = false }
        composeRule.onNodeWithTag("warehouse-loading").assertDoesNotExist()
        composeRule.onNodeWithText("Нет доступных городов").assertExists()
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
