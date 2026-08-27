package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.HeldDeliverySlot
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose regression coverage for the server-backed date, slot, and confirmation steps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeliveryFlowScreensTest {
    /** Compose semantics environment for the focused delivery destination assertions. */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `date step renders and selects only server returned dates`() {
        val selectedDate = AtomicReference<String>()
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            slots = listOf(
                slot("late", "2026-09-02", "15:00:00"),
                slot("early", "2026-09-01", "09:00:00"),
            ),
            slotSearchCompleted = true,
        )
        composeRule.setContent {
            CustomerTheme {
                DeliveryDatesScreen(
                    state = workflow,
                    onBack = {},
                    onDate = selectedDate::set,
                )
            }
        }

        composeRule.onNodeWithTag("delivery-date-2026-09-01").assertExists()
        composeRule.onNodeWithTag("delivery-date-2026-09-02").assertExists()
        composeRule.onNodeWithTag("delivery-date-2026-09-03").assertDoesNotExist()
        composeRule.onNodeWithTag("delivery-date-expand-2026-09-01").performClick()
        composeRule.onNodeWithText("09:00–18:00").assertExists()
        composeRule.onNodeWithTag("delivery-date-2026-09-01").performClick()

        composeRule.runOnIdle {
            assertThat(selectedDate.get()).isEqualTo("2026-09-01")
        }
    }

    @Test
    fun `slot step filters the chosen date and requests a server hold`() {
        val holdRequests = AtomicInteger()
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            slots = listOf(
                slot("chosen", "2026-09-01", "12:00:00"),
                slot("other-date", "2026-09-02", "12:00:00"),
            ),
            selectedSlotId = "chosen",
        )
        composeRule.setContent {
            CustomerTheme {
                DeliverySlotsScreen(
                    state = workflow,
                    date = "2026-09-01",
                    onBack = {},
                    onSelectSlot = {},
                    onHoldSlot = { holdRequests.incrementAndGet() },
                    onHeld = {},
                )
            }
        }

        composeRule.onNodeWithTag("delivery-slot-chosen").assertExists()
        composeRule.onNodeWithTag("delivery-slot-other-date").assertDoesNotExist()
        composeRule.onNodeWithTag("hold-slot-button").performClick()

        composeRule.runOnIdle {
            assertThat(holdRequests.get()).isEqualTo(1)
        }
    }

    @Test
    fun `confirmation step shows held slot and requested rental term`() {
        val held = slot("held", "2026-09-01", "09:00:00")
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            address = "Невский проспект, 1",
            selectedCabinIds = setOf("cabin-1", "cabin-2"),
            rentalMonths = 3,
            heldSlot = HeldDeliverySlot(cartVersion = 8, slot = held),
        )
        composeRule.setContent {
            CustomerTheme {
                DeliveryConfirmationScreen(
                    state = workflow,
                    onBack = {},
                    onRentalMonths = {},
                    onCheckout = {},
                )
            }
        }

        composeRule.onNodeWithText("Невский проспект, 1").assertExists()
        composeRule.onNodeWithText("09:00–18:00").assertExists()
        composeRule.onNodeWithTag("delivery-confirmation-screen")
            .performScrollToNode(hasText("3 мес."))
        composeRule.onNodeWithText("3 мес.").assertExists()
        composeRule.onNodeWithText("2").assertExists()
        composeRule.onNodeWithTag("checkout-button").assertExists()
    }

    private fun slot(id: String, date: String, start: String): DeliverySlot = DeliverySlot(
        slotId = id,
        version = 4,
        date = date,
        start = start,
        end = "18:00:00",
        travelZoneHours = 1,
        capacityRemaining = 2,
        expiresAt = "2026-08-27T12:00:00+03:00",
        state = "OFFERED",
    )
}
