package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose regressions for the catalog warehouse header, avatar, and reachable filter controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogScreensTest {
    /** Compose semantics environment for focused catalog assertions. */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `catalog header shows selected warehouse and switches through anchored menu`() {
        var selectedWarehouseId: String? = null

        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState(),
                    onMenu = {},
                    onProfile = {},
                    onCart = {},
                    onFilters = {},
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = { selectedWarehouseId = it.id },
                )
            }
        }

        composeRule.onNodeWithText("Свободные бытовки").assertDoesNotExist()
        composeRule.onNodeWithText("Склад: СПБ").assertExists()
        composeRule.onNodeWithTag("profile-avatar").assertExists()
        composeRule.onNodeWithText("ИП").assertExists()

        composeRule.onNodeWithTag("warehouse-selector").performClick()
        composeRule.onNodeWithTag("warehouse-option-warehouse-moscow").assertExists().performClick()

        composeRule.runOnIdle { assertThat(selectedWarehouseId).isEqualTo("warehouse-moscow") }
    }

    @Test
    fun `warehouse expansion grows header without shifting centered selector or profile`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerTopBar(
                    title = "СПБ",
                    onMenu = {},
                    onProfile = {},
                    selectedWarehouse = catalogState().selectedWarehouse,
                    warehouses = catalogState().warehouses,
                    onWarehouseSelected = {},
                    avatarInitials = "ИП",
                )
            }
        }
        val initialHeader = composeRule.onNodeWithTag("customer-header").fetchSemanticsNode().boundsInRoot
        val initialSelector = composeRule.onNodeWithTag("warehouse-selector").fetchSemanticsNode().boundsInRoot
        val initialProfile = composeRule.onNodeWithTag("profile-avatar").fetchSemanticsNode().boundsInRoot

        assertThat(initialSelector.center.x).isWithin(1f).of(initialHeader.center.x)
        composeRule.onNodeWithTag("warehouse-selector").performClick()
        composeRule.waitForIdle()

        val expandedHeader = composeRule.onNodeWithTag("customer-header").fetchSemanticsNode().boundsInRoot
        val expandedSelector = composeRule.onNodeWithTag("warehouse-selector").fetchSemanticsNode().boundsInRoot
        val expandedProfile = composeRule.onNodeWithTag("profile-avatar").fetchSemanticsNode().boundsInRoot
        assertThat(expandedHeader.height).isGreaterThan(initialHeader.height)
        assertThat(expandedSelector.center.x).isWithin(1f).of(initialSelector.center.x)
        assertThat(expandedProfile.center.x).isWithin(1f).of(initialProfile.center.x)
        assertThat(expandedProfile.center.y).isWithin(1f).of(initialProfile.center.y)
    }

    @Test
    fun `empty facets are explicit and filter actions remain reachable`() {
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState(),
                    onMenu = {},
                    onProfile = {},
                    onCart = {},
                    onFilters = {},
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = {},
                )
            }
        }

        composeRule.onNodeWithText("Фильтры").performClick()

        composeRule.onNodeWithTag("catalog-filter-sheet").assertExists()
        composeRule.onNodeWithText("Тип").assertExists()
        composeRule.onAllNodesWithText("Нет доступных вариантов на этом складе").onFirst().assertExists()
        composeRule.onNodeWithText("Сбросить").assertExists()
        composeRule.onNodeWithText("Показать").assertExists()
    }

    @Test
    fun `filter control matches cabin card width and characteristic rows stay explicit`() {
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(
                        cabins = listOf(
                            CustomerCabin(
                                unitId = "cabin-1",
                                version = 1,
                                accountingNo = "БК-1",
                                characteristics = listOf("Пластиковое окно", "Усиленная дверь"),
                            ),
                        ),
                    ),
                    onMenu = {},
                    onProfile = {},
                    onCart = {},
                    onFilters = {},
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = {},
                )
            }
        }

        val filterBounds = composeRule.onNodeWithTag("catalog-filter-button").fetchSemanticsNode().boundsInRoot
        val cardBounds = composeRule.onNodeWithTag("cabin-cabin-1").fetchSemanticsNode().boundsInRoot
        assertThat(filterBounds.width).isWithin(1f).of(cardBounds.width)
        composeRule.onNodeWithText("Пластиковое окно").assertExists()
        composeRule.onNodeWithText("Усиленная дверь").assertExists()
    }

    @Test
    fun `catalog card presents one available cabin with server delivery guidance and no price`() {
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(
                        cabins = listOf(catalogCabin()),
                        estimatedDeliveryDates = listOf("2026-09-08", "2026-09-10"),
                    ),
                    onMenu = {},
                    onProfile = {},
                    onCart = {},
                    onFilters = {},
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = {},
                )
            }
        }

        composeRule.onNodeWithText("В наличии").assertExists()
        composeRule.onNodeWithText("Ориентир: с 8 сентября · точный срок после адреса").assertExists()
        composeRule.onAllNodesWithText("шт.", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("₽", substring = true).assertCountEquals(0)
    }

    @Test
    fun `floating cart keeps the selected count and opens checkout`() {
        var cartOpened = false
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(
                        cabins = listOf(catalogCabin()),
                        selectedCabinIds = setOf("cabin-1"),
                    ),
                    onMenu = {},
                    onProfile = {},
                    onCart = { cartOpened = true },
                    onFilters = {},
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = {},
                )
            }
        }

        composeRule.onNodeWithTag("cart-fab").assertExists()
        composeRule.onNodeWithText("В заказе 1 позиция").assertExists()
        composeRule.onNodeWithTag("cart-checkout-button").performClick()
        composeRule.runOnIdle { assertThat(cartOpened).isTrue() }
    }

    @Test
    @Config(sdk = [35], application = Application::class, qualifiers = "w1000dp-h800dp")
    fun `wide catalog keeps the photo led horizontal card`() {
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(cabins = listOf(catalogCabin())),
                    onMenu = {},
                    onProfile = {},
                    onCart = {},
                    onFilters = {},
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = {},
                )
            }
        }

        composeRule.onNodeWithTag("cabin-card-wide").assertExists()
        composeRule.onNodeWithTag("cabin-card-compact").assertDoesNotExist()
    }

    @Test
    fun `adaptive and cart labels keep stable customer semantics`() {
        assertThat(usesWideCabinCard(919f)).isFalse()
        assertThat(usesWideCabinCard(920f)).isTrue()
        assertThat(cartPositionsLabel(2)).isEqualTo("В заказе 2 позиции")
        assertThat(cartPositionsLabel(11)).isEqualTo("В заказе 11 позиций")
        assertThat(deliveryEstimateLabel(emptyList()))
            .isEqualTo("Ориентир доставки уточняется · точный срок после адреса")
    }

    private fun catalogState(): CustomerWorkflowState {
        val selected = CustomerWarehouse(
            id = "warehouse-spb",
            name = "СПБ",
            city = "Санкт-Петербург",
            timezone = "Europe/Moscow",
            depotLatitude = 59.763806,
            depotLongitude = 30.471798,
        )
        return CustomerWorkflowState(
            bootstrapping = false,
            profile = CustomerProfile(
                entityType = CustomerEntityType.INDIVIDUAL,
                firstName = "Иван",
                lastName = "Петров",
                phone = "+79990000000",
            ),
            warehouses = listOf(
                selected,
                CustomerWarehouse(
                    id = "warehouse-moscow",
                    name = "Москва",
                    city = "Москва",
                    timezone = "Europe/Moscow",
                    depotLatitude = 55.7558,
                    depotLongitude = 37.6173,
                ),
            ),
            selectedWarehouse = selected,
            inquiryId = "inquiry-spb",
        )
    }

    private fun catalogCabin(): CustomerCabin = CustomerCabin(
        unitId = "cabin-1",
        version = 1,
        accountingNo = "БК-1",
        type = "Офисная бытовка",
        finish = "Графит",
        dimensions = "6,0 × 2,4 × 2,6 м",
        category = "Офисная",
        linoleum = true,
        characteristics = listOf("Панорамное остекление", "Электрика и освещение"),
    )
}
