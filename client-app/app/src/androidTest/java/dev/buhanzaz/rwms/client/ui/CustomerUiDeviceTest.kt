package dev.buhanzaz.rwms.client.ui

import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.mapview.MapView
import dev.buhanzaz.rwms.client.data.CabinFacetWarehouse
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Native rendering and interaction checks using production screens with test-owned workflow state. */
class CustomerUiDeviceTest {
    /** The existing Compose test activity avoids live customer commands while retaining native views. */
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun initializeNativeViews() {
        composeRule.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            MapKitFactory.initialize(composeRule.activity)
        }
    }

    @Test
    fun lightDrawer() = reviewDrawer(CustomerAppearanceMode.LIGHT)

    @Test
    fun darkDrawer() = reviewDrawer(CustomerAppearanceMode.DARK)

    private fun reviewDrawer(mode: CustomerAppearanceMode) {
        showWorkflow(mode)
        composeRule.onNodeWithTag("cart-fab").performClick()
        composeRule.onNodeWithTag("header-back").performClick()
        composeRule.onNodeWithTag("catalog-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("menu-button").performClick()
        val drawer = composeRule.onNodeWithTag("customer-drawer").fetchSemanticsNode().boundsInRoot
        val logo = composeRule.onNodeWithTag("drawer-logo").fetchSemanticsNode().boundsInRoot
        assertTrue("Compact logo should stay inside the drawer", logo.left >= drawer.left && logo.right <= drawer.right)
        assertTrue("Brand header should leave room for navigation", logo.width < drawer.width * 0.65f)
        composeRule.onNodeWithContentDescription("Закрыть меню").assertDoesNotExist()
        saveScreen("drawer-${mode.name.lowercase()}")
    }

    @Test
    fun readableFilters() {
        showWorkflow(CustomerAppearanceMode.LIGHT)
        composeRule.onNodeWithTag("catalog-filter-button").performClick()
        composeRule.onNodeWithTag("cabin-native-cabin").assertDoesNotExist()
        composeRule.onNodeWithText("Показать").assertIsDisplayed()
        composeRule.onNodeWithTag("filter-field-Тип").performClick()
        composeRule.onNodeWithText("БК-2").assertIsDisplayed()
        saveScreen("filters-light")
    }

    @Test
    fun deliveryTermsAndMapBackNavigation() {
        showWorkflow(CustomerAppearanceMode.LIGHT)
        composeRule.onNodeWithTag("cart-fab").performClick()
        composeRule.onNodeWithText("Продолжить к доставке").performScrollTo().performClick()
        composeRule.onNodeWithTag("delivery-map-screen").assertIsDisplayed()
        composeRule.runOnIdle {
            val map = composeRule.activity.window.decorView.descendants().filterIsInstance<MapView>().single()
            assertTrue("Map pixels must move with the Compose screen", map.descendants().any { it is TextureView })
        }
        composeRule.onNodeWithContentDescription("Продолжить к выбору даты").performClick()
        composeRule.onNodeWithTag("confirm-delivery-responsibility").assertIsNotEnabled()
        composeRule.onNodeWithTag("private-site-access-confirmation").performScrollTo().performClick()
        composeRule.onNodeWithTag("failed-trip-charge-acknowledgement").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm-delivery-responsibility").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("delivery-dates-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("header-back").performClick()
        composeRule.onNodeWithTag("delivery-map-screen").assertIsDisplayed()
        saveScreen("delivery-map-after-back")
    }

    @Test
    fun roundedDeliveryTerms() {
        var access by mutableStateOf(false)
        var responsibility by mutableStateOf(false)
        composeRule.setContent {
            CustomerTheme {
                CustomerStoreBackground(Modifier.fillMaxSize())
                DeliveryResponsibilityDialog(
                    selectedCabinCount = 1,
                    siteCabinCapacity = 1,
                    privateSiteAccessConfirmed = access,
                    failedTripChargeAcknowledged = responsibility,
                    busy = false,
                    onSiteCabinCapacity = {},
                    onPrivateSiteAccess = { access = it },
                    onFailedTripAcknowledgement = { responsibility = it },
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        composeRule.onNodeWithTag("private-site-access-confirmation").performClick()
        saveScreen("delivery-terms")
    }

    private fun showWorkflow(mode: CustomerAppearanceMode) {
        var workflow by mutableStateOf(nativeWorkflow())
        composeRule.setContent {
            CustomerTheme(appearanceMode = mode) {
                Box(Modifier.fillMaxSize()) {
                    CustomerStoreBackground(Modifier.fillMaxSize())
                    CustomerAppContent(
                        state = CustomerAppState.Ready(workflow),
                        appearanceMode = mode,
                        onPrivateSiteAccess = { workflow = workflow.copy(privateSiteAccessConfirmed = it) },
                        onFailedTripAcknowledgement = { workflow = workflow.copy(failedTripChargeAcknowledged = it) },
                        onSearchSlots = { workflow = workflow.copy(slotSearchGeneration = workflow.slotSearchGeneration + 1) },
                    )
                }
            }
        }
    }

    private fun saveScreen(name: String) {
        composeRule.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(composeRule.activity.getExternalFilesDir(null), "ui-review-$name.png")
        file.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        bitmap.recycle()
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private fun nativeWorkflow(): CustomerWorkflowState {
        val warehouse = CustomerWarehouse(
            id = "native-warehouse", name = "СПБ", city = "Санкт-Петербург", timezone = "Europe/Moscow",
            depotLatitude = 59.763806, depotLongitude = 30.471798,
        )
        return CustomerWorkflowState(
            bootstrapping = false,
            profile = CustomerProfile(
                entityType = CustomerEntityType.INDIVIDUAL, firstName = "Иван", lastName = "Петров", phone = "+79990000000",
            ),
            selectedWarehouse = warehouse,
            warehouses = listOf(warehouse),
            inquiryId = "native-inquiry",
            cabins = listOf(
                CustomerCabin(
                    unitId = "native-cabin", version = 1, accountingNo = "1001", type = "БК-1",
                    finish = "Вагонка", dimensions = "6 × 2,4 м", pricingVersion = 1, monthlyPriceRubles = 8000,
                ),
            ),
            facets = CabinFacets(
                warehouses = listOf(
                    CabinFacetWarehouse(warehouse.id, warehouse.name, cabinTypes = listOf("БК-1", "БК-2")),
                ),
            ),
            selectedCabinIds = setOf("native-cabin"),
            rentalTerms = mapOf("native-cabin" to 1L),
            address = "Санкт-Петербург, Московское шоссе",
            latitude = warehouse.depotLatitude,
            longitude = warehouse.depotLongitude,
            deliveryLocationConfirmed = true,
        )
    }
}
