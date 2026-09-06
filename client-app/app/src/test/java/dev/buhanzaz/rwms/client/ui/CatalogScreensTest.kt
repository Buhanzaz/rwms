package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CabinFacetWarehouse
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CabinFilters
import dev.buhanzaz.rwms.client.data.CabinTypeDimensions
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerEntityType
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
        composeRule.onNodeWithText("Бытовки в аренду").assertDoesNotExist()
        composeRule.onAllNodesWithText("Выберите отдельный экземпляр", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Санкт-Петербург").assertExists()
        composeRule.onNodeWithTag("profile-avatar").assertExists()
        composeRule.onNodeWithTag("catalog-sticky-filter").assertExists()
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

        composeRule.onNodeWithTag("catalog-filter-panel").assertExists()
        composeRule.onNodeWithText("Тип").assertExists()
        composeRule.onNodeWithText("Сбросить фильтры").assertExists()
        composeRule.onNodeWithText("Показать").assertDoesNotExist()
        val typeBounds = composeRule.onNodeWithTag("filter-field-Тип").fetchSemanticsNode().boundsInRoot
        val finishBounds = composeRule.onNodeWithTag("filter-field-Отделка").fetchSemanticsNode().boundsInRoot
        val sizeBounds = composeRule.onNodeWithTag("filter-field-Размер").fetchSemanticsNode().boundsInRoot
        assertThat(typeBounds.width).isWithin(1f).of(finishBounds.width)
        assertThat(typeBounds.width).isWithin(1f).of(sizeBounds.width)
        assertThat(typeBounds.top).isWithin(1f).of(finishBounds.top)
        composeRule.onNodeWithTag("filter-field-Тип").performClick()
        composeRule.onNodeWithText("Нет доступных вариантов в этом городе").assertExists()
        val optionsBounds = composeRule.onNodeWithTag("filter-options-Тип").fetchSemanticsNode().boundsInRoot
        assertThat(optionsBounds.top).isAtLeast(typeBounds.bottom)
    }

    @Test
    fun `type filter limits dimensions and clears an incompatible previous dimension`() {
        var appliedFilters: CabinFilters? = null
        val compatibleDimension = "6,0 × 2,4 × 2,6 м"
        val incompatibleDimension = "7,0 × 2,4 × 2,6 м"

        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(
                        filters = CabinFilters(dimensions = incompatibleDimension),
                        facets = catalogFacets(compatibleDimension, incompatibleDimension),
                    ),
                    onMenu = {},
                    onProfile = {},
                    onFilters = { appliedFilters = it },
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = {},
                )
            }
        }

        composeRule.onNodeWithTag("catalog-filter-button").performClick()
        composeRule.onNodeWithTag("filter-field-Тип").performClick()
        composeRule.onNodeWithText("БК-1").performClick()
        composeRule.onNodeWithText(incompatibleDimension).assertDoesNotExist()
        composeRule.onNodeWithTag("filter-field-Размер").performClick()
        composeRule.onNodeWithText(compatibleDimension).assertExists()
        composeRule.onNodeWithText(incompatibleDimension).assertDoesNotExist()
        composeRule.onNodeWithText(compatibleDimension).performClick()

        composeRule.runOnIdle {
            assertThat(appliedFilters).isEqualTo(CabinFilters(cabinType = "БК-1", dimensions = compatibleDimension))
        }
    }

    @Test
    fun `visible characteristics and reset apply without a second action`() {
        var appliedFilters: CabinFilters? = null
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(
                        filters = CabinFilters(finish = "Графит"),
                        facets = CabinFacets(
                            warehouses = listOf(
                                CabinFacetWarehouse(
                                    warehouseId = "warehouse-spb",
                                    name = "СПБ",
                                    characteristics = listOf("Пластиковое окно", "Усиленная дверь"),
                                ),
                            ),
                        ),
                    ),
                    onMenu = {}, onProfile = {}, onFilters = { appliedFilters = it },
                    onLoadMore = {}, onToggleCabin = {}, onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> }, onWarehouse = {},
                )
            }
        }

        composeRule.onNodeWithTag("catalog-filter-button").performClick()
        composeRule.onNodeWithTag("filter-characteristics").assertExists()
        composeRule.onNodeWithTag("filter-characteristic-Пластиковое окно").performClick()
        composeRule.runOnIdle {
            assertThat(appliedFilters).isEqualTo(
                CabinFilters(finish = "Графит", characteristics = setOf("Пластиковое окно")),
            )
        }
        composeRule.onNodeWithTag("catalog-filter-reset").performClick()
        composeRule.runOnIdle { assertThat(appliedFilters).isEqualTo(CabinFilters()) }
    }

    @Test
    fun `filters stay above cards and apply each choice without moving the header`() {
        var appliedFilters: CabinFilters? = null
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(cabins = listOf(catalogCabin())),
                    onMenu = {}, onProfile = {}, onFilters = { appliedFilters = it },
                    onLoadMore = {}, onToggleCabin = {}, onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> }, onWarehouse = {},
                )
            }
        }
        val originalHeader = composeRule.onNodeWithTag("customer-header").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("catalog-filter-button").performClick()
        composeRule.onNodeWithTag("cabin-cabin-1").assertExists()
        val filterHeader = composeRule.onNodeWithTag("customer-header").fetchSemanticsNode().boundsInRoot
        assertThat(filterHeader).isEqualTo(originalHeader)
        composeRule.onNodeWithTag("filter-field-Пол").performClick()
        composeRule.onNodeWithText("Линолеум").performClick()
        composeRule.onNodeWithText("Закрыть фильтры").performClick()
        composeRule.onNodeWithTag("cabin-cabin-1").assertExists()
        assertThat(appliedFilters).isEqualTo(CabinFilters(linoleum = true))
    }

    @Test
    fun `compact filter stays between header and catalog without a full width button`() {
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(
                        cabins = listOf(
                            CustomerCabin(
                                unitId = "cabin-1",
                                version = 1,
                                accountingNo = "БК-1",
                                pricingVersion = 3,
                                monthlyPriceRubles = 8_000,
                                characteristics = listOf("Пластиковое окно", "Усиленная дверь"),
                            ),
                        ),
                    ),
                    onMenu = {},
                    onProfile = {},
                    onFilters = {},
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = {},
                )
            }
        }

        val headerBounds = composeRule.onNodeWithTag("customer-header").fetchSemanticsNode().boundsInRoot
        val stickyFilterBounds = composeRule.onNodeWithTag("catalog-sticky-filter").fetchSemanticsNode().boundsInRoot
        val catalogBounds = composeRule.onNodeWithTag("catalog-screen").fetchSemanticsNode().boundsInRoot
        val filterBounds = composeRule.onNodeWithTag("catalog-filter-button").fetchSemanticsNode().boundsInRoot
        val cardBounds = composeRule.onNodeWithTag("cabin-cabin-1").fetchSemanticsNode().boundsInRoot

        assertThat(stickyFilterBounds.top).isAtLeast(headerBounds.bottom)
        assertThat(catalogBounds.top).isWithin(1f).of(stickyFilterBounds.bottom)
        assertThat(filterBounds.width).isLessThan(cardBounds.width)
        composeRule.onNodeWithText("Пластиковое окно").assertExists()
        composeRule.onNodeWithText("Усиленная дверь").assertExists()
    }

    @Test
    fun `catalog card leads with type and omits deprecated status and delivery details`() {
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(
                        cabins = listOf(catalogCabin()),
                        selectedCabinIds = setOf("cabin-1"),
                    ),
                    onMenu = {},
                    onProfile = {},
                    onFilters = {},
                    onLoadMore = {},
                    onToggleCabin = {},
                    onEquipment = { _, _, _ -> },
                    onPhoto = { _, _ -> },
                    onWarehouse = {},
                )
            }
        }

        val numberBounds = composeRule.onNodeWithTag("cabin-number-cabin-1").fetchSemanticsNode().boundsInRoot
        val typeBounds = composeRule.onNodeWithTag("cabin-type-cabin-1").fetchSemanticsNode().boundsInRoot
        assertThat(typeBounds.left).isLessThan(numberBounds.left)
        composeRule.onNodeWithText("№ БК-1").assertExists()
        composeRule.onNodeWithText("Офисная бытовка").assertExists()
        composeRule.onNodeWithText("8 000 ₽/мес.").assertExists()
        composeRule.onNodeWithText("Отделка: Графит").assertExists()
        composeRule.onNodeWithText("+ Дополнительно").assertExists()
        composeRule.onNodeWithText("Мебель по выбору").assertDoesNotExist()
        composeRule.onNodeWithText("Настроить мебель").assertDoesNotExist()
        composeRule.onNodeWithText("В наличии").assertDoesNotExist()
        composeRule.onAllNodesWithText("Ориентир", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag("cart-fab").assertDoesNotExist()
        composeRule.onNodeWithTag("cart-checkout-button").assertDoesNotExist()
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
    fun `adaptive card threshold remains stable`() {
        assertThat(usesWideCabinCard(919f)).isFalse()
        assertThat(usesWideCabinCard(920f)).isTrue()
    }

    private fun catalogFacets(
        compatibleDimension: String,
        incompatibleDimension: String,
    ): CabinFacets = CabinFacets(
        warehouses = listOf(
            CabinFacetWarehouse(
                warehouseId = "warehouse-spb",
                name = "СПБ",
                cabinTypes = listOf("БК-1", "БК-2"),
                dimensions = listOf(compatibleDimension, incompatibleDimension),
                typeDimensions = listOf(
                    CabinTypeDimensions("БК-1", listOf(compatibleDimension)),
                    CabinTypeDimensions("БК-2", listOf(incompatibleDimension)),
                ),
            ),
        ),
    )

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
        pricingVersion = 3,
        monthlyPriceRubles = 8_000,
        type = "Офисная бытовка",
        finish = "Графит",
        dimensions = "6,0 × 2,4 × 2,6 м",
        category = "Офисная",
        linoleum = true,
        characteristics = listOf("Панорамное остекление", "Электрика и освещение"),
    )
}
