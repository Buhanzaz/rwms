package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose-level proof that signed-out and signed-in customer destinations cannot coexist. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerConditionalNavigationTest {
    /** Compose semantics environment for the conditional navigation assertions. */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `signed out shows login and hides profile workflow`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalCustomerStoreVideoBackgroundEnabled provides false) {
                CustomerTheme { CustomerAppContent(CustomerAppState.SignedOut()) }
            }
        }

        composeRule.onNodeWithText("Вход").assertExists()
        composeRule.onNodeWithTag("profile-screen").assertDoesNotExist()
    }

    @Test
    fun `signed in without profile shows profile and hides login`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(CustomerWorkflowState(bootstrapping = false)),
                )
            }
        }

        composeRule.onNodeWithTag("profile-screen").assertExists()
        composeRule.onNodeWithText("Вход").assertDoesNotExist()
    }

    @Test
    fun `created warehouse session opens catalog even while its first page is empty`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(
                        CustomerWorkflowState(
                            bootstrapping = false,
                            profile = CustomerProfile(
                                entityType = CustomerEntityType.INDIVIDUAL,
                                firstName = "Иван",
                                lastName = "Петров",
                                phone = "+79990000000",
                            ),
                            selectedWarehouse = CustomerWarehouse(
                                id = "c89b65f1-2891-4176-bd88-1d231e869a25",
                                name = "СПБ",
                                city = "Санкт-Петербург",
                                timezone = "Europe/Moscow",
                                depotLatitude = 59.763806,
                                depotLongitude = 30.471798,
                            ),
                            inquiryId = "00000000-0000-0000-0000-000000000601",
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("warehouse-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("catalog-screen").assertExists()
    }

    @Test
    fun `catalog requests a fresh inquiry when the visible booking owns its cart`() {
        val recoveryRequests = AtomicInteger()
        val inquiryId = "00000000-0000-0000-0000-000000000601"

        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    state = CustomerAppState.Ready(
                        CustomerWorkflowState(
                            bootstrapping = false,
                            profile = CustomerProfile(
                                entityType = CustomerEntityType.INDIVIDUAL,
                                firstName = "Иван",
                                lastName = "Петров",
                                phone = "+79990000000",
                            ),
                            selectedWarehouse = CustomerWarehouse(
                                id = "c89b65f1-2891-4176-bd88-1d231e869a25",
                                name = "СПБ",
                                city = "Санкт-Петербург",
                                timezone = "Europe/Moscow",
                                depotLatitude = 59.763806,
                                depotLongitude = 30.471798,
                            ),
                            inquiryId = inquiryId,
                            booking = CustomerBooking(
                                bookingId = "00000000-0000-0000-0000-000000000701",
                                status = "COMPLETED",
                                inquiryId = inquiryId,
                                warehouseId = "c89b65f1-2891-4176-bd88-1d231e869a25",
                            ),
                        ),
                    ),
                    onEnsureActiveInquiry = { recoveryRequests.incrementAndGet() },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { recoveryRequests.get() == 1 }
        assertThat(recoveryRequests.get()).isEqualTo(1)
    }

    @Test
    fun `drawer presents rental and switches only between explicit light and dark themes`() {
        var appearanceMode by mutableStateOf(CustomerAppearanceMode.LIGHT)
        composeRule.setContent {
            CustomerTheme(appearanceMode = appearanceMode) {
                CustomerAppContent(
                    state = CustomerAppState.Ready(readyCatalogWorkflow()),
                    appearanceMode = appearanceMode,
                    onAppearanceMode = { appearanceMode = it },
                )
            }
        }

        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Аренда").assertCountEquals(2)
        composeRule.onNodeWithText("Свободные бытовки").assertDoesNotExist()
        composeRule.onNodeWithText("Как на телефоне").assertDoesNotExist()
        composeRule.onNodeWithTag("appearance-selector").assertDoesNotExist()
        composeRule.onNodeWithText("Включить тёмную тему").assertExists()
        composeRule.onNodeWithTag("appearance-toggle").performScrollTo().performClick()
        composeRule.runOnIdle { assertThat(appearanceMode).isEqualTo(CustomerAppearanceMode.DARK) }

        composeRule.onNodeWithText("Включить светлую тему").assertExists()
        composeRule.onNodeWithTag("appearance-toggle").performScrollTo().performClick()
        composeRule.runOnIdle { assertThat(appearanceMode).isEqualTo(CustomerAppearanceMode.LIGHT) }
    }

    @Test
    fun `legal entity access is a drawer placeholder and keeps the catalog open`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    state = CustomerAppState.Ready(readyCatalogWorkflow()),
                )
            }
        }

        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithTag("legal-entity-access-placeholder").performScrollTo().performClick()
        composeRule.onNodeWithText("Доступ для юридических лиц появится позже").assertExists()
        composeRule.onNodeWithTag("catalog-screen").assertExists()
    }

    @Test
    fun `delivery map owns busy progress while other routes keep the global overlay`() {
        assertThat(shouldShowGlobalBusyOverlay(isBusy = true, current = CatalogRoute)).isTrue()
        assertThat(shouldShowGlobalBusyOverlay(isBusy = true, current = DeliveryMapRoute)).isFalse()
        assertThat(shouldShowGlobalBusyOverlay(isBusy = false, current = DeliveryMapRoute)).isFalse()
    }

    private fun readyCatalogWorkflow(): CustomerWorkflowState = CustomerWorkflowState(
        bootstrapping = false,
        profile = CustomerProfile(
            entityType = CustomerEntityType.INDIVIDUAL,
            firstName = "Иван",
            lastName = "Петров",
            phone = "+79990000000",
        ),
        selectedWarehouse = CustomerWarehouse(
            id = "c89b65f1-2891-4176-bd88-1d231e869a25",
            name = "СПБ",
            city = "Санкт-Петербург",
            timezone = "Europe/Moscow",
            depotLatitude = 59.763806,
            depotLongitude = 30.471798,
        ),
        inquiryId = "00000000-0000-0000-0000-000000000601",
    )
}
