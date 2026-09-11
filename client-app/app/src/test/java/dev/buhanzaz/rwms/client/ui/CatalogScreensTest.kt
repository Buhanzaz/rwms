package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CabinFacetWarehouse
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CabinFilters
import dev.buhanzaz.rwms.client.data.CabinPhoto
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

        val initialCard = composeRule.onNodeWithTag("cabin-cabin-1").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText("Фильтры").performClick()
        val coveredCard = composeRule.onNodeWithTag("cabin-cabin-1").fetchSemanticsNode().boundsInRoot
        val panel = composeRule.onNodeWithTag("catalog-filter-panel").fetchSemanticsNode().boundsInRoot
        assertThat(coveredCard).isEqualTo(initialCard)
        assertThat(panel.overlaps(coveredCard)).isTrue()

        composeRule.onNodeWithTag("catalog-filter-panel").assertExists()
        composeRule.onNodeWithText("Тип").assertExists()
        composeRule.onNodeWithText("Сбросить фильтры").assertExists()
        composeRule.onNodeWithText("Показать").assertDoesNotExist()
        val typeBounds = composeRule.onNodeWithTag("filter-field-Тип").fetchSemanticsNode().boundsInRoot
        val finishBounds = composeRule.onNodeWithTag("filter-field-Отделка").fetchSemanticsNode().boundsInRoot
        val sizeBounds = composeRule.onNodeWithTag("filter-field-Размер").fetchSemanticsNode().boundsInRoot
        assertThat(typeBounds.width).isWithin(1f).of(finishBounds.width)
        assertThat(typeBounds.width).isWithin(1f).of(sizeBounds.width)
        assertThat(typeBounds.bottom).isAtMost(finishBounds.top)
        assertThat(finishBounds.bottom).isAtMost(sizeBounds.top)
        composeRule.onNodeWithTag("filter-field-Тип").performClick()
        composeRule.onNodeWithText("Нет доступных вариантов в этом городе").assertExists()
        val optionsBounds = composeRule.onNodeWithTag("filter-options-Тип").fetchSemanticsNode().boundsInRoot
        assertThat(optionsBounds.top).isAtLeast(typeBounds.bottom)
        val displacedFinish = composeRule.onNodeWithTag("filter-field-Отделка").fetchSemanticsNode().boundsInRoot
        assertThat(displacedFinish.top).isGreaterThan(finishBounds.top)
    }

    @Test
    fun `type filter limits dimensions and clears an incompatible previous dimension`() {
        val appliedFilters = mutableListOf<CabinFilters>()
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
                    onFilters = { appliedFilters += it },
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
        composeRule.onNodeWithTag("filter-field-Размер").performScrollTo().performClick()
        composeRule.onNodeWithText(compatibleDimension).assertExists()
        composeRule.onNodeWithText(incompatibleDimension).assertDoesNotExist()
        val dimensionOptionTag = "filter-option-Размер-$compatibleDimension"
        composeRule.onNodeWithTag("filter-options-Размер").performScrollTo()
        composeRule.onNodeWithTag(dimensionOptionTag).performScrollTo().assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertThat(appliedFilters).containsExactly(
                CabinFilters(cabinType = "БК-1"),
                CabinFilters(cabinType = "БК-1", dimensions = compatibleDimension),
            ).inOrder()
        }
    }

    @Test
    fun `expanded options stay below their field, scroll internally, and center labels`() {
        val values = (1..12).map { "БК-$it" }
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(
                        facets = CabinFacets(
                            warehouses = listOf(
                                CabinFacetWarehouse(warehouseId = "warehouse-spb", name = "СПБ", cabinTypes = values),
                            ),
                        ),
                    ),
                    onMenu = {}, onProfile = {}, onFilters = {}, onLoadMore = {}, onToggleCabin = {},
                    onEquipment = { _, _, _ -> }, onPhoto = { _, _ -> }, onWarehouse = {},
                )
            }
        }

        composeRule.onNodeWithTag("catalog-filter-button").performClick()
        val fieldBounds = composeRule.onNodeWithTag("filter-field-Тип").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("filter-field-Тип").performClick()

        val optionBounds = composeRule.onNodeWithTag("filter-options-Тип").fetchSemanticsNode().boundsInRoot
        val labelBounds = composeRule.onNodeWithText("БК-1").fetchSemanticsNode().boundsInRoot
        assertThat(optionBounds.top).isAtLeast(fieldBounds.bottom)
        assertThat(optionBounds.height).isAtMost(240f * composeRule.density.density)
        assertThat(labelBounds.center.x).isWithin(1f).of(optionBounds.center.x)
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
        composeRule.onNodeWithTag("filter-characteristic-Пластиковое окно").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertThat(appliedFilters).isEqualTo(
                CabinFilters(finish = "Графит", characteristics = setOf("Пластиковое окно")),
            )
        }
        composeRule.onNodeWithTag("catalog-filter-reset").performScrollTo().performClick()
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
        composeRule.onNodeWithTag("filter-field-Пол").performScrollTo().performClick()
        composeRule.onNodeWithTag("filter-options-Пол").performScrollTo()
        composeRule.onNodeWithTag("filter-option-Пол-Линолеум").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("catalog-filter-button").performClick()
        composeRule.onNodeWithTag("cabin-cabin-1").assertExists()
        assertThat(appliedFilters).isEqualTo(CabinFilters(linoleum = true))
    }

    @Test
    fun `catalog keeps state visible while busy and disables mutations`() {
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(cabins = listOf(catalogCabin()), busy = true),
                    onMenu = {}, onProfile = {}, onFilters = {}, onLoadMore = {}, onToggleCabin = {},
                    onEquipment = { _, _, _ -> }, onPhoto = { _, _ -> }, onWarehouse = {},
                )
            }
        }

        composeRule.onNodeWithTag("catalog-loading").assertExists()
        composeRule.onNodeWithTag("catalog-empty").assertDoesNotExist()
        composeRule.onNodeWithText("В заказ").assertIsNotEnabled()
        composeRule.onNodeWithTag("warehouse-selector").assertIsNotEnabled()
    }

    @Test
    fun `guest loading and errors never render a false empty catalog`() {
        val guestState = mutableStateOf(
            CustomerGuestCatalogState(
                warehouses = catalogState().warehouses,
                selectedWarehouse = catalogState().selectedWarehouse,
                busy = true,
            ),
        )
        composeRule.setContent {
            CustomerTheme {
                GuestCabinCatalogScreen(
                    state = guestState.value,
                    onBack = {}, onLogin = {}, onFilters = {}, onLoadMore = {}, onPhoto = { _, _ -> }, onWarehouse = {},
                )
            }
        }
        composeRule.onNodeWithTag("guest-catalog-loading").assertExists()
        composeRule.onNodeWithTag("catalog-empty").assertDoesNotExist()

        composeRule.runOnIdle { guestState.value = CustomerGuestCatalogState(error = "Каталог временно недоступен") }
        composeRule.onNodeWithTag("guest-catalog-error").assertExists()
        composeRule.onNodeWithTag("catalog-empty").assertDoesNotExist()
    }

    @Test
    fun `empty filtered catalog offers an immediate reset without oversized spacing`() {
        var appliedFilters: CabinFilters? = null
        composeRule.setContent {
            CustomerTheme {
                CabinCatalogScreen(
                    state = catalogState().copy(filters = CabinFilters(finish = "Графит")),
                    onMenu = {}, onProfile = {}, onFilters = { appliedFilters = it }, onLoadMore = {}, onToggleCabin = {},
                    onEquipment = { _, _, _ -> }, onPhoto = { _, _ -> }, onWarehouse = {},
                )
            }
        }

        val headerBounds = composeRule.onNodeWithTag("customer-header").fetchSemanticsNode().boundsInRoot
        val messageBounds = composeRule.onNodeWithText("Свободных бытовок по выбранным условиям нет").fetchSemanticsNode().boundsInRoot
        assertThat(messageBounds.top - headerBounds.bottom).isAtMost(24f * composeRule.density.density)
        composeRule.onNodeWithTag("catalog-empty-clear-filters").performClick()
        composeRule.runOnIdle { assertThat(appliedFilters).isEqualTo(CabinFilters()) }
    }

    @Test
    fun `gallery pages with a one finger swipe at its unzoomed scale`() {
        composeRule.setContent {
            CustomerTheme {
                FullscreenCabinGallery(
                    cabin = catalogCabin().copy(
                        photos = listOf(
                            CabinPhoto("photo-1", 1, "unsupported-1", "unsupported-1"),
                            CabinPhoto("photo-2", 1, "unsupported-2", "unsupported-2"),
                        ),
                    ),
                    initialPage = 0,
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText("1 / 2").assertExists()
        composeRule.onNodeWithContentDescription("Фото 1 из 2").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 2").assertExists()
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

        assertThat(stickyFilterBounds.top).isAtLeast(headerBounds.top)
        assertThat(stickyFilterBounds.bottom).isAtMost(headerBounds.bottom)
        assertThat(catalogBounds.top).isAtLeast(headerBounds.bottom)
        assertThat(catalogBounds.bottom).isEqualTo(composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        assertThat(cardBounds.top).isWithin(1f).of(headerBounds.bottom + 16f * composeRule.density.density)
        assertThat(filterBounds.width).isLessThan(cardBounds.width)
        composeRule.onNodeWithText("Пластиковое окно").assertExists()
        composeRule.onNodeWithText("Усиленная дверь").assertExists()
        composeRule.onNodeWithTag("cabin-attribute-Пластиковое окно").assertExists()
        composeRule.onNodeWithTag("cabin-attribute-Усиленная дверь").assertExists()
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
        composeRule.onNodeWithText("Отделка").assertExists()
        composeRule.onNodeWithText("Графит").assertExists()
        composeRule.onNodeWithText("+ Дополнительно").assertExists()
        composeRule.onNodeWithText("В заказе").assertExists()
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
