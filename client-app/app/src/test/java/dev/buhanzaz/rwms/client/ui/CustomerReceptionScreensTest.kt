package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerBookingCabin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose regressions for arrived-only cabin reception and the full-screen signature entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerReceptionScreensTest {
    /** Compose semantics environment for focused reception assertions. */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `only arrived unaccepted cabin exposes acceptance and opens signature`() {
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(booking()),
                    latest = null,
                    busy = false,
                    onMenu = {},
                    onProfile = {},
                    onRefresh = {},
                    onAccept = { _, _, _ -> },
                    onReport = { _, _, _, _, _ -> },
                )
            }
        }

        composeRule.onAllNodesWithText("Принять бытовку").fetchSemanticsNodes().also { nodes ->
            assertThat(nodes).hasSize(1)
        }
        composeRule.onNodeWithText("Принять бытовку").performClick()
        composeRule.onNodeWithText("Приёмка бытовки № БК-1").assertExists()
    }

    private fun booking(): CustomerBooking = CustomerBooking(
        bookingId = "booking-a",
        orderId = "order-a",
        status = "COMPLETED",
        inquiryId = "inquiry-a",
        warehouseId = "warehouse-a",
        cabins = listOf(
            CustomerBookingCabin(
                cabinUnitId = "cabin-a",
                accountingNo = "БК-1",
                rentalMonths = 3,
                deliveryState = "ARRIVED",
                arrivalEligible = true,
            ),
            CustomerBookingCabin(
                cabinUnitId = "cabin-b",
                accountingNo = "БК-2",
                rentalMonths = 3,
                deliveryState = "SCHEDULED",
                arrivalEligible = false,
            ),
        ),
    )
}
