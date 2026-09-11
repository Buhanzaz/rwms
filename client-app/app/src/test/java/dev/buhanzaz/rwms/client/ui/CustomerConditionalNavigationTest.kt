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
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CabinPhoto
import dev.buhanzaz.rwms.client.BuildConfig
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

        composeRule.onNodeWithTag("customer-auth-login").assertExists()
        composeRule.onNodeWithTag("profile-screen").assertDoesNotExist()
    }

    @Test
    fun `signed in without registration data shows a real error without another profile form`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(CustomerWorkflowState(bootstrapping = false)),
                )
            }
        }

        composeRule.onNodeWithTag("profile-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("registration-status-screen").assertExists()
        composeRule.onNodeWithText("Данные регистрации недоступны. Обратитесь в поддержку.").assertExists()
        composeRule.onNodeWithText("Вход").assertDoesNotExist()
    }

    @Test
    fun `interrupted registration retries saving without repeating profile fields`() {
        var retries = 0
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(CustomerWorkflowState(
                        bootstrapping = false,
                        registrationPending = true,
                        registrationError = "Не удалось сохранить регистрацию",
                    )),
                    onRetryRegistration = { retries += 1 },
                )
            }
        }
        composeRule.onNodeWithText("Не удалось сохранить регистрацию").assertExists()
        composeRule.onNodeWithTag("profile-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("registration-retry").performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `failed city bootstrap stays an error instead of an empty successful list`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(CustomerAppState.Ready(readyCatalogWorkflow().copy(
                    selectedWarehouse = null,
                    inquiryId = null,
                    warehouses = emptyList(),
                    registrationError = "Не удалось загрузить города",
                )))
            }
        }
        composeRule.onNodeWithText("Не удалось загрузить города").assertExists()
        composeRule.onNodeWithText("Нет доступных городов").assertDoesNotExist()
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
        val drawer = composeRule.onNodeWithTag("customer-drawer").fetchSemanticsNode().boundsInRoot
        val logo = composeRule.onNodeWithTag("drawer-logo", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val subtitle = composeRule.onNodeWithText("Аренда и доставка").fetchSemanticsNode().boundsInRoot
        assertThat(logo.center.x).isWithin(1f).of(drawer.center.x)
        assertThat(subtitle.center.x).isWithin(1f).of(drawer.center.x)
        assertThat(drawer.width).isWithin(1f).of(264f * composeRule.density.density)
        composeRule.onNodeWithText("Свободные бытовки").assertDoesNotExist()
        composeRule.onNodeWithText("Как на телефоне").assertDoesNotExist()
        composeRule.onNodeWithTag("appearance-selector").assertDoesNotExist()
        composeRule.onNodeWithText("Светлая тема").assertExists()
        composeRule.onNodeWithTag("appearance-toggle").performScrollTo().performClick()
        composeRule.runOnIdle { assertThat(appearanceMode).isEqualTo(CustomerAppearanceMode.DARK) }

        composeRule.onNodeWithText("Темная тема").assertExists()
        composeRule.onNodeWithTag("appearance-toggle").performScrollTo().performClick()
        composeRule.runOnIdle { assertThat(appearanceMode).isEqualTo(CustomerAppearanceMode.LIGHT) }
        composeRule.onNodeWithText("Светлая тема").assertExists()
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
    fun `catalog loading keeps its screen available without a blocking dialog`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(CustomerAppState.Ready(readyCatalogWorkflow().copy(busy = true)))
            }
        }
        composeRule.onNodeWithTag("catalog-loading").assertExists()
        composeRule.onAllNodes(androidx.compose.ui.test.isDialog()).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Открыть меню").performClick()
        composeRule.onNodeWithTag("customer-drawer").assertExists()
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
        composeRule.onNodeWithTag("cart-count", useUnmergedTree = true).assertExists()
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

    @Test
    fun `continue as guest opens catalog without profile or ordering and order action opens login`() {
        val city = requireNotNull(readyCatalogWorkflow().selectedWarehouse)
        val cabin = CustomerCabin("public-cabin", 1, "42", 1, 18_000)
        var state by mutableStateOf<CustomerAppState>(CustomerAppState.SignedOut())
        val mutations = AtomicInteger()
        composeRule.setContent {
            CompositionLocalProvider(LocalCustomerStoreVideoBackgroundEnabled provides false) {
                CustomerTheme {
                    CustomerAppContent(
                        state,
                        onContinueAsGuest = { state = CustomerAppState.GuestCatalog(CustomerGuestCatalogState(warehouses = listOf(city))) },
                        onGuestWarehouse = { state = CustomerAppState.GuestCatalog(CustomerGuestCatalogState(
                            warehouses = listOf(city), selectedWarehouse = it, cabins = listOf(cabin),
                        )) },
                        onGuestLogin = { state = CustomerAppState.SignedOut(showLogin = true) },
                        onToggleCabin = { mutations.incrementAndGet() },
                        onCheckout = { mutations.incrementAndGet() },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("customer-auth-guest").performScrollTo().performClick()
        composeRule.onNodeWithTag("warehouse-screen").assertExists()
        composeRule.onNodeWithTag("profile-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("remember-warehouse").assertDoesNotExist()
        composeRule.onNodeWithTag("menu-button").assertDoesNotExist()
        composeRule.onNodeWithTag("warehouse-option-${city.id}").performClick()
        composeRule.onNodeWithTag("cabin-public-cabin").assertExists()
        composeRule.onNodeWithTag("cart-fab").assertDoesNotExist()
        composeRule.onNodeWithText("В заказ").assertDoesNotExist()
        composeRule.onNodeWithText("+ Дополнительно").assertDoesNotExist()
        composeRule.onNodeWithText("Войти для заказа").performScrollTo().performClick()
        composeRule.onNodeWithTag("customer-login-screen").assertExists()
        composeRule.onNodeWithTag("customer-auth-start").assertDoesNotExist()
        composeRule.onNodeWithTag("catalog-screen").assertDoesNotExist()
        assertThat(mutations.get()).isEqualTo(0)
    }

    @Test
    fun `guest can inspect and close photos without entering the customer workflow`() {
        val city = requireNotNull(readyCatalogWorkflow().selectedWarehouse)
        // Unsupported test URLs exercise the unavailable-photo state without external network traffic.
        val cabin = CustomerCabin("public-cabin", 1, "42", 1, 18_000, photos = listOf(CabinPhoto("p", 1, "", "")))
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(CustomerAppState.GuestCatalog(CustomerGuestCatalogState(
                    warehouses = listOf(city), selectedWarehouse = city, cabins = listOf(cabin),
                )))
            }
        }
        composeRule.onNodeWithContentDescription("Бытовка 42, фото 1 из 1").performClick()
        composeRule.onNodeWithTag("gallery-close").assertExists().performClick()
        composeRule.onNodeWithTag("catalog-screen").assertExists()
        composeRule.onNodeWithTag("cart-fab").assertDoesNotExist()
        composeRule.onNodeWithTag("profile-screen").assertDoesNotExist()
    }

    @Test
    fun `guest read failure stays visible instead of reporting an empty successful result`() {
        val city = requireNotNull(readyCatalogWorkflow().selectedWarehouse)
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(CustomerAppState.GuestCatalog(CustomerGuestCatalogState(
                    selectedWarehouse = city, error = "Каталог временно недоступен",
                )))
            }
        }
        composeRule.onNodeWithText("Каталог временно недоступен").assertExists()
        composeRule.onNodeWithText("Свободных бытовок по выбранным условиям нет").assertDoesNotExist()
        composeRule.onNodeWithTag("header-back").assertExists()
    }

    @Test
    fun `guest photo URLs resolve on the gateway and reject foreign origins`() {
        val publicPath = "/api/logistics/public/v1/catalog/warehouses/w/cabins/c/photos/p?generation=2&variant=LARGE"
        val privatePath = "/api/logistics/customer/v1/inquiries/i/cabins/c/photos/p?generation=2&variant=LARGE"
        assertThat(customerMediaUrl(publicPath)).isEqualTo(BuildConfig.PUBLIC_BASE_URL + publicPath)
        assertThat(customerMediaUrl(BuildConfig.PUBLIC_BASE_URL + publicPath)).isEqualTo(BuildConfig.PUBLIC_BASE_URL + publicPath)
        assertThat(customerMediaUrl(privatePath)).isEqualTo(BuildConfig.PUBLIC_BASE_URL + privatePath)
        assertThat(customerMediaUrl("https://foreign.invalid$publicPath")).isEmpty()
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
