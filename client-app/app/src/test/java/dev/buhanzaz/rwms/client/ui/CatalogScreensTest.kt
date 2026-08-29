package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        composeRule.onNodeWithText("СПБ").assertExists()
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
}
