package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerRouteProfile
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotKind
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
    fun `address search matches header geometry and preserves native cursor edits`() {
        val changedAddress = AtomicReference("Санкт-Петербург, длинная улица, дом 123, корпус 4, помещение 567")
        composeRule.setContent {
            var address by remember { mutableStateOf(changedAddress.get()) }
            CustomerTheme {
                Column {
                    CustomerTopBar("Адрес доставки")
                    DeliveryAddressPanel(
                        address = address,
                        geocoding = false,
                        status = null,
                        suggestions = emptyList(),
                        continueEnabled = false,
                        onAddress = { address = it; changedAddress.set(it) },
                        onSearch = {},
                        onSuggestion = {},
                        onVoice = {},
                        onContinue = {},
                    )
                }
            }
        }
        val header = composeRule.onNodeWithTag("customer-header").fetchSemanticsNode().boundsInRoot
        val search = composeRule.onNodeWithTag("delivery-search-bar").fetchSemanticsNode().boundsInRoot
        assertThat(search.width).isWithin(1f).of(header.width)
        assertThat(search.height).isWithin(1f).of(header.height)
        val field = composeRule.onNodeWithTag("delivery-address-field")
        field.performClick().performTextInputSelection(TextRange(0))
        field.performTextInput("Начало ")
        composeRule.runOnIdle { assertThat(changedAddress.get()).startsWith("Начало Санкт-Петербург") }
        val length = changedAddress.get().length
        field.performTextInputSelection(TextRange(length))
        field.performTextInput(" конец")
        composeRule.runOnIdle { assertThat(changedAddress.get()).endsWith("567 конец") }
    }

    @Test
    fun `same delivery price is shown once across several time choices`() {
        composeRule.setContent {
            CustomerTheme {
                DeliverySlotsScreen(
                    state = CustomerWorkflowState(
                        bootstrapping = false,
                        slots = listOf(slot("morning", "2026-09-01", "09:00:00"), slot("afternoon", "2026-09-01", "12:00:00")),
                    ),
                    date = "2026-09-01",
                    onBack = {}, onSelectSlot = {}, onHoldSlot = {}, onHeld = {},
                )
            }
        }
        composeRule.onAllNodesWithText("12 500 ₽").assertCountEquals(1)
        composeRule.onNodeWithTag("delivery-slot-morning").assertExists()
        composeRule.onNodeWithTag("delivery-slot-afternoon").assertExists()
    }

    @Test
    fun `different delivery offers retain their own amounts without inventing a shared tariff`() {
        composeRule.setContent {
            CustomerTheme {
                DeliverySlotsScreen(
                    state = CustomerWorkflowState(
                        bootstrapping = false,
                        slots = listOf(slot("morning", "2026-09-01", "09:00:00"), slot("afternoon", "2026-09-01", "12:00:00").copy(deliveryPriceRubles = 16000)),
                    ),
                    date = "2026-09-01",
                    onBack = {}, onSelectSlot = {}, onHoldSlot = {}, onHeld = {},
                )
            }
        }
        composeRule.onNodeWithText("Зависит от выбранного времени").assertExists()
        composeRule.onNodeWithText("12 500 ₽").assertExists()
        composeRule.onNodeWithTag("delivery-slots-screen").performScrollToNode(hasText("16 000 ₽"))
        composeRule.onNodeWithText("16 000 ₽").assertExists()
    }

    @Test
    fun `hold deadline uses the warehouse zone without relying on the device clock`() {
        assertThat(customerHoldDeadlineLabel("2026-09-07T10:30:00Z", "Europe/Moscow"))
            .isEqualTo("7 сентября, 13:30")
        assertThat(customerHoldDeadlineLabel("invalid", "Europe/Moscow")).isNull()
    }

    @Test
    fun `date step renders and selects only server returned dates`() {
        val selectedDate = AtomicReference<String>()
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            slots = listOf(
                slot("late", "2026-09-02", "15:00:00"),
                slot("early", "2026-09-01", "09:00:00", DeliverySlotKind.DURING_DAY),
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
        composeRule.onNodeWithText("12 500 ₽").assertExists()
        composeRule.onNodeWithText("Особая зона доставки", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Изохрона", substring = true).assertDoesNotExist()
        val explanationBounds = composeRule.onNodeWithTag("delivery-date-explanation").fetchSemanticsNode().boundsInRoot
        val priceBounds = composeRule.onNodeWithTag("delivery-price").fetchSemanticsNode().boundsInRoot
        assertThat(priceBounds.top).isAtLeast(explanationBounds.bottom)
        val firstDateBounds = composeRule.onNodeWithTag("delivery-date-2026-09-01")
            .fetchSemanticsNode().boundsInRoot
        assertThat(priceBounds.bottom).isAtMost(firstDateBounds.top)
        composeRule.onNodeWithTag("delivery-date-expand-2026-09-01").performClick()
        composeRule.onNodeWithText("В течение дня. Точное время подтвердит логист").assertExists()
        composeRule.onNodeWithTag("delivery-date-2026-09-01").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertThat(selectedDate.get()).isEqualTo("2026-09-01")
        }
    }

    @Test
    fun `date step exposes missing tariff without manufacturing zero price`() {
        composeRule.setContent {
            CustomerTheme {
                DeliveryDatesScreen(
                    state = CustomerWorkflowState(
                        bootstrapping = false,
                        slots = listOf(slot("unknown", "2026-09-01", "09:00:00").copy(deliveryPriceRubles = null)),
                        slotSearchCompleted = true,
                    ),
                    onBack = {},
                    onDate = {},
                )
            }
        }

        composeRule.onNodeWithText("Не рассчитана").assertExists()
        composeRule.onNodeWithText("Стоимость доставки: 0 ₽").assertDoesNotExist()
        composeRule.onNodeWithTag("delivery-date-2026-09-01").assertExists()
    }

    @Test
    fun `slot step filters the chosen date and requests a server hold`() {
        val holdRequests = AtomicInteger()
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            slots = listOf(
                slot("chosen", "2026-09-01", "09:00:00", DeliverySlotKind.DURING_DAY),
                slot("other-date", "2026-09-02", "12:00:00"),
            ),
            selectedSlotId = "chosen",
            privateSiteAccessConfirmed = true,
            failedTripChargeAcknowledged = true,
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
        composeRule.onNodeWithText("Маршрут подтверждён · вариантов:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("12 500 ₽").assertExists()
        composeRule.onNodeWithText("В течение дня. Точное время подтвердит логист").assertExists()
        composeRule.onNodeWithTag("delivery-slot-other-date").assertDoesNotExist()
        composeRule.onNodeWithTag("private-site-access-confirmation").assertDoesNotExist()
        composeRule.onNodeWithTag("failed-trip-charge-acknowledgement").assertDoesNotExist()
        composeRule.onNodeWithTag("hold-slot-button").performClick()

        composeRule.runOnIdle {
            assertThat(holdRequests.get()).isEqualTo(1)
        }
    }

    @Test
    fun `flexible arrival is last in both date preview and slot selection`() {
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            slots = listOf(
                slot("flexible", "2026-09-01", "08:00:00", DeliverySlotKind.DURING_DAY),
                slot("fixed", "2026-09-01", "12:00:00"),
            ),
            slotSearchCompleted = true,
        )
        var showSlots by mutableStateOf(false)
        composeRule.setContent {
            CustomerTheme {
                if (showSlots) {
                    DeliverySlotsScreen(workflow, "2026-09-01", {}, {}, {}, {})
                } else {
                    DeliveryDatesScreen(workflow, {}, { showSlots = true })
                }
            }
        }
        composeRule.onNodeWithTag("delivery-date-expand-2026-09-01").performClick()
        // Finish expansion before positioning the containing lazy item; the preview scrolls independently.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("delivery-dates-screen").performScrollToIndex(1)
        composeRule.onNodeWithText("12:00–18:00").assertIsDisplayed()
        composeRule.onNodeWithText("В течение дня. Точное время подтвердит логист").assertIsDisplayed()
        val fixed = composeRule.onNodeWithText("12:00–18:00").fetchSemanticsNode().boundsInRoot
        val flexible = composeRule.onNodeWithText("В течение дня. Точное время подтвердит логист").fetchSemanticsNode().boundsInRoot
        assertThat(flexible.top).isAtLeast(fixed.bottom)
        composeRule.onNodeWithTag("delivery-date-2026-09-01").performScrollTo().performClick()
        composeRule.onNodeWithTag("delivery-slots-screen").performScrollToIndex(1)
        composeRule.onNodeWithTag("delivery-slot-fixed").assertIsDisplayed()
        composeRule.onNodeWithTag("delivery-slot-flexible").assertIsDisplayed()
        val fixedCard = composeRule.onNodeWithTag("delivery-slot-fixed").fetchSemanticsNode().boundsInRoot
        val flexibleCard = composeRule.onNodeWithTag("delivery-slot-flexible").fetchSemanticsNode().boundsInRoot
        assertThat(flexibleCard.top).isAtLeast(fixedCard.bottom)
    }

    @Test
    fun `responsibility modal requires both attestations before slot calculation`() {
        val searchRequests = AtomicInteger()
        composeRule.setContent {
            var privateSiteAccess by remember { mutableStateOf(false) }
            var failedTripAcknowledged by remember { mutableStateOf(false) }
            CustomerTheme {
                DeliveryResponsibilityDialog(
                    selectedCabinCount = 2,
                    siteCabinCapacity = 1,
                    privateSiteAccessConfirmed = privateSiteAccess,
                    failedTripChargeAcknowledged = failedTripAcknowledged,
                    busy = false,
                    onSiteCabinCapacity = {},
                    onPrivateSiteAccess = { privateSiteAccess = it },
                    onFailedTripAcknowledgement = { failedTripAcknowledged = it },
                    onDismiss = {},
                    onConfirm = { searchRequests.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag("confirm-delivery-responsibility").assertIsNotEnabled()
        composeRule.onNodeWithTag("private-site-access-confirmation").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm-delivery-responsibility").assertIsNotEnabled()
        composeRule.onNodeWithTag("failed-trip-charge-acknowledgement").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm-delivery-responsibility").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertThat(searchRequests.get()).isEqualTo(1)
        }
    }

    @Test
    fun `multi cabin responsibility selects solo truck or trailer capacity`() {
        val selectedCapacity = AtomicInteger(1)
        composeRule.setContent {
            var capacity by remember { mutableStateOf(1) }
            CustomerTheme {
                DeliveryResponsibilityDialog(
                    selectedCabinCount = 3,
                    siteCabinCapacity = capacity,
                    privateSiteAccessConfirmed = false,
                    failedTripChargeAcknowledged = false,
                    busy = false,
                    onSiteCabinCapacity = {
                        capacity = it
                        selectedCapacity.set(it)
                    },
                    onPrivateSiteAccess = {},
                    onFailedTripAcknowledgement = {},
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithTag("site-cabin-capacity-2").performClick()
        composeRule.onNodeWithText("Машина с прицепом проедет к адресу и сможет работать на объекте")
            .assertExists()
        composeRule.runOnIdle { assertThat(selectedCapacity.get()).isEqualTo(2) }
    }

    @Test
    fun `one cabin responsibility fixes solo truck capacity without dropdown`() {
        composeRule.setContent {
            CustomerTheme {
                DeliveryResponsibilityDialog(
                    selectedCabinCount = 1,
                    siteCabinCapacity = 1,
                    privateSiteAccessConfirmed = false,
                    failedTripChargeAcknowledged = false,
                    busy = false,
                    onSiteCabinCapacity = {},
                    onPrivateSiteAccess = {},
                    onFailedTripAcknowledgement = {},
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithTag("site-cabin-capacity-fixed").assertExists()
        composeRule.onNodeWithTag("site-cabin-capacity-selector").assertDoesNotExist()
        composeRule.onNodeWithText("Машина без прицепа проедет к адресу и сможет работать на объекте")
            .assertExists()
    }

    @Test
    fun `missing yandex voice input is explained in a modal`() {
        composeRule.setContent {
            CustomerTheme { VoiceInputInstallationDialog(onDismiss = {}) }
        }

        composeRule.onNodeWithText("Установить голосовой ввод.").assertExists()
        composeRule.onNodeWithText("Для голосового адреса нужен установленный голосовой ввод Яндекса.")
            .assertExists()
    }

    @Test
    fun `slot calculation overlay shows blocking progress above the exact status`() {
        composeRule.setContent {
            CustomerTheme { DeliverySlotCalculationOverlay() }
        }

        composeRule.onNodeWithTag("delivery-slot-calculation-overlay").assertExists()
        composeRule.onNodeWithTag("delivery-slot-calculation-progress").assertExists()
        composeRule.onNodeWithText("Идёт расчёт свободных слотов").assertExists()
    }

    @Test
    fun `confirmation step shows held slot and requested rental term`() {
        val held = slot("held", "2026-09-01", "09:00:00", DeliverySlotKind.DURING_DAY)
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            address = "Невский проспект, 1",
            selectedCabinIds = setOf("cabin-1", "cabin-2"),
            rentalTerms = mapOf("cabin-1" to 3L, "cabin-2" to 3L),
            heldSlot = HeldDeliverySlot(cartVersion = 8, slot = held),
        )
        composeRule.setContent {
            CustomerTheme {
                DeliveryConfirmationScreen(
                    state = workflow,
                    onBack = {},
                    onCheckout = {},
                )
            }
        }

        composeRule.onNodeWithText("Невский проспект, 1").assertExists()
        composeRule.onNodeWithText("12 500 ₽").assertExists()
        composeRule.onNodeWithText("В течение дня. Точное время подтвердит логист").assertExists()
        composeRule.onNodeWithTag("delivery-confirmation-screen")
            .performScrollToNode(hasText("2 шт. · 3 мес."))
        composeRule.onNodeWithText("2 шт. · 3 мес.").assertExists()
        composeRule.onNodeWithText("2").assertExists()
        composeRule.onNodeWithTag("delivery-confirmation-screen")
            .performScrollToNode(hasTestTag("checkout-button"))
        composeRule.onNodeWithTag("checkout-button").assertExists()
    }

    @Test
    fun `confirmation step enables checkout for the displayed default rental term`() {
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            address = "Шереметьевский сквер",
            selectedCabinIds = setOf("cabin-1"),
            heldSlot = HeldDeliverySlot(
                cartVersion = 8,
                slot = slot("held", "2026-09-01", "09:00:00").copy(state = "HELD"),
            ),
        )
        composeRule.setContent {
            CustomerTheme {
                DeliveryConfirmationScreen(
                    state = workflow,
                    onBack = {},
                    onCheckout = {},
                )
            }
        }

        composeRule.onNodeWithTag("delivery-confirmation-screen")
            .performScrollToNode(hasText("1 шт. · 1 мес."))
        composeRule.onNodeWithText("09:00–18:00").assertExists()
        composeRule.onNodeWithText("1 шт. · 1 мес.").assertExists()
        composeRule.onNodeWithTag("delivery-confirmation-screen").performScrollToNode(hasTestTag("checkout-button"))
        composeRule.onNodeWithTag("checkout-button").assertIsEnabled()
    }

    private fun slot(
        id: String,
        date: String,
        start: String,
        kind: DeliverySlotKind = DeliverySlotKind.FIXED_WINDOW,
    ): DeliverySlot = DeliverySlot(
        slotId = id,
        version = 4,
        date = date,
        kind = kind,
        start = start,
        end = "18:00:00",
        travelZoneHours = 1,
        capacityRemaining = 2,
        deliveryPriceRubles = 12500,
        priceZoneId = "00000000-0000-0000-0000-000000000020",
        priceIsochroneMinutes = null,
        siteCabinCapacity = 2,
        roadRouteConfirmed = true,
        privateSiteAccessConfirmed = true,
        failedTripChargeAcknowledged = true,
        routeProfile = CustomerRouteProfile(4.0, 2.5, 14.0, 20.0, 8.0, 5),
        expiresAt = "2026-08-27T12:00:00+03:00",
        state = "OFFERED",
    )
}
