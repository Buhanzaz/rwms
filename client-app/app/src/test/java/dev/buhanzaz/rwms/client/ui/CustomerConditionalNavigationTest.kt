package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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

        composeRule.onNodeWithText("Войти").assertExists()
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
        composeRule.onAllNodesWithText("Аренда").assertCountEquals(1)
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
    fun `legal entity availability is explained in profile without a drawer placeholder`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    state = CustomerAppState.Ready(readyCatalogWorkflow()),
                )
            }
        }

        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithTag("legal-entity-access-placeholder").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Закрыть меню").assertDoesNotExist()
        composeRule.onNodeWithText("Доступ для юрлиц").assertDoesNotExist()
        composeRule.onNodeWithText("Профиль").performClick()
        composeRule.onNodeWithTag("profile-screen").performScrollToNode(hasTestTag("profile-legal-entity-access"))
        composeRule.onNodeWithText("Доступ для юрлиц").assertExists()
        composeRule.onNodeWithText(
            "Регистрация юридических лиц пока недоступна. Сейчас вы используете личный профиль для аренды.",
        ).assertExists()
    }

    @Test
    fun `delivery map owns busy progress while other routes keep the global overlay`() {
        assertThat(shouldShowGlobalBusyOverlay(isBusy = true, current = CatalogRoute)).isTrue()
        assertThat(shouldShowGlobalBusyOverlay(isBusy = true, current = DeliveryMapRoute)).isFalse()
        assertThat(shouldShowGlobalBusyOverlay(isBusy = false, current = DeliveryMapRoute)).isFalse()
    }

    @Test
    fun `cart shortcut appears with a live count and opens the cart without a footer`() {
        var workflow by mutableStateOf(readyCatalogWorkflow())
        composeRule.setContent {
            CustomerTheme { CustomerAppContent(CustomerAppState.Ready(workflow)) }
        }
        composeRule.onNodeWithTag("cart-fab").assertDoesNotExist()
        composeRule.onNodeWithText("Аренда").assertIsNotDisplayed()
        composeRule.onAllNodesWithText("Заказы").assertCountEquals(0)
        composeRule.runOnIdle { workflow = workflow.copy(selectedCabinIds = setOf("a", "b")) }
        composeRule.onNodeWithTag("cart-count").assertExists()
        composeRule.onNodeWithText("2").assertExists()
        composeRule.onNodeWithTag("cart-fab").performClick()
        composeRule.onNodeWithTag("cart-screen").assertExists()
        composeRule.onNodeWithTag("cart-fab").assertDoesNotExist()
        composeRule.onNodeWithTag("header-back").performClick()
        composeRule.onNodeWithTag("catalog-screen").assertExists()
        composeRule.onNodeWithTag("cart-fab").assertExists()
    }

    @Test
    fun `cart shortcut is hidden in profile and restored only in catalog`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(readyCatalogWorkflow().copy(selectedCabinIds = setOf("a"))),
                )
            }
        }
        composeRule.onNodeWithTag("profile-avatar").performClick()
        composeRule.onNodeWithTag("profile-screen").assertExists()
        composeRule.onNodeWithTag("cart-fab").assertDoesNotExist()
        composeRule.onNodeWithTag("header-back").performClick()
        composeRule.onNodeWithTag("catalog-screen").assertExists()
        composeRule.onNodeWithTag("cart-fab").assertExists()
        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithText("Мои заказы").performClick()
        composeRule.onNodeWithTag("cart-fab").assertDoesNotExist()
    }

    @Test
    fun `system back from drawer cart returns to rental catalog`() {
        var backDispatcher: OnBackPressedDispatcher? = null
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CustomerTheme { CustomerAppContent(CustomerAppState.Ready(readyCatalogWorkflow())) }
        }
        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithText("Корзина (0)").performClick()
        composeRule.onNodeWithTag("cart-screen").assertExists()
        composeRule.runOnIdle { checkNotNull(backDispatcher).onBackPressed() }
        composeRule.onNodeWithTag("catalog-screen").assertExists()
        composeRule.onNodeWithTag("cart-screen").assertDoesNotExist()
    }

    @Test
    fun `forward and back transitions keep neighboring screens apart`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(readyCatalogWorkflow().copy(selectedCabinIds = setOf("a"))),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("cart-fab").performClick()
        composeRule.mainClock.advanceTimeBy(128)
        assertCatalogAndCartDoNotOverlap()
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("header-back").performClick()
        composeRule.mainClock.advanceTimeBy(128)
        assertCatalogAndCartDoNotOverlap()
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("catalog-screen").assertExists()
    }

    private fun assertCatalogAndCartDoNotOverlap() {
        val catalog = composeRule.onNodeWithTag("catalog-screen").fetchSemanticsNode().boundsInRoot
        val cart = composeRule.onNodeWithTag("cart-screen").fetchSemanticsNode().boundsInRoot
        assertThat(catalog.width).isGreaterThan(0f)
        assertThat(cart.width).isGreaterThan(0f)
        assertThat(catalog.right).isAtMost(cart.left + 1f)
    }

    @Test
    fun `city selection shares menu and profile and opens catalog after selection`() {
        val initial = readyCatalogWorkflow()
        var workflow by mutableStateOf(initial.copy(selectedWarehouse = null, warehouses = listOf(initial.selectedWarehouse!!)))
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(workflow),
                    onWarehouse = { warehouse, _ -> workflow = workflow.copy(selectedWarehouse = warehouse) },
                )
            }
        }
        composeRule.onNodeWithTag("warehouse-screen").assertExists()
        composeRule.onNodeWithText("Выйти").assertIsNotDisplayed()
        composeRule.onNodeWithTag("profile-avatar").performClick()
        composeRule.onNodeWithTag("profile-screen").assertExists()
        composeRule.onNodeWithTag("header-back").performClick()
        composeRule.onNodeWithTag("warehouse-screen").assertExists()
        composeRule.onNodeWithTag("menu-button").assertExists()
        composeRule.onNodeWithText("Санкт-Петербург").performClick()
        composeRule.onNodeWithTag("catalog-screen").assertExists()
        composeRule.onNodeWithTag("warehouse-screen").assertDoesNotExist()
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
