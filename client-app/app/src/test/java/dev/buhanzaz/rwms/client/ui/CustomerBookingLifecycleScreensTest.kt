package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.AvailableEquipment
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerRouteProfile
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotKind
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose regressions for CustomerApp booking lifecycle and server-scoped cart configuration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerBookingLifecycleScreensTest {
    /** Compose semantics environment for the focused booking lifecycle assertions. */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `completed booking offers confirmed cancellation without fabricating a fee`() {
        val cancelled = AtomicReference<String>()
        val booking = booking(id = "booking-a", status = "COMPLETED")
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(booking),
                    latest = null,
                    busy = false,
                    onMenu = {},
                    onProfile = {},
                    onAccept = { _, _, _ -> },
                    onReport = { _, _, _, _, _ -> },
                    onCancel = cancelled::set,
                )
            }
        }

        composeRule.onNodeWithText("Статус: Оформлен").assertExists()
        composeRule.onNodeWithText("Обновить статусы").assertDoesNotExist()
        composeRule.onNodeWithText("Стоимость отмены: 0 ₽").assertDoesNotExist()
        composeRule.onNodeWithText("Стоимость отмены", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("booking-cancel-booking-a").performScrollTo().performClick()
        composeRule.onNodeWithText("Отменить заказ?").assertExists()
        composeRule.onNodeWithTag("booking-confirm-cancel").performClick()

        composeRule.runOnIdle {
            assertThat(cancelled.get()).isEqualTo("booking-a")
        }
    }

    @Test
    fun `reschedule dialog confirms only an exact server offer`() {
        val confirmedSlot = AtomicReference<String>()
        val offer = slot("slot-new")
        composeRule.setContent {
            var selectedSlotId by remember { mutableStateOf<String?>(null) }
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(booking(id = "booking-a", status = "COMPLETED")),
                    latest = null,
                    busy = false,
                    onMenu = {},
                    onProfile = {},
                    onAccept = { _, _, _ -> },
                    onReport = { _, _, _, _, _ -> },
                    rescheduleBookingId = "booking-a",
                    rescheduleSlots = listOf(offer),
                    selectedRescheduleSlotId = selectedSlotId,
                    onSelectRescheduleSlot = { selectedSlotId = it },
                    onConfirmReschedule = { confirmedSlot.set(selectedSlotId) },
                )
            }
        }

        composeRule.onNodeWithTag("booking-reschedule-dialog").assertExists()
        composeRule.onNodeWithText("2026-09-02").assertExists()
        composeRule.onNodeWithText("12:00–15:00").assertExists()
        composeRule.onNodeWithText("Стоимость доставки: 10 000 ₽").assertExists()
        composeRule.onNodeWithTag("booking-confirm-reschedule").assertIsNotEnabled()
        composeRule.runOnIdle { assertThat(confirmedSlot.get()).isNull() }

        composeRule.onNodeWithTag("booking-reschedule-slot-slot-new").performClick()
        composeRule.onNodeWithTag("booking-confirm-reschedule").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertThat(confirmedSlot.get()).isEqualTo("slot-new")
        }
    }

    @Test
    fun `pending cancellation is Russian and never exposes its backend code or edit actions`() {
        val pending = booking(
            id = "booking-pending",
            status = "CANCELLATION_PENDING",
            errorCode = "CUSTOMER_BOOKING_CANCELLATION_PENDING",
        )
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(pending),
                    latest = null,
                    busy = false,
                    onMenu = {},
                    onProfile = {},
                    onAccept = { _, _, _ -> },
                    onReport = { _, _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Статус: Отмена выполняется").assertExists()
        composeRule.onNodeWithText("Отмена ещё выполняется. Обновите статус немного позже.").assertExists()
        composeRule.onNodeWithText("CUSTOMER_BOOKING_CANCELLATION_PENDING").assertDoesNotExist()
        composeRule.onNodeWithTag("booking-cancel-booking-pending").assertDoesNotExist()
        composeRule.onNodeWithTag("booking-reschedule-booking-pending").assertDoesNotExist()
    }

    @Test
    fun `successful reschedule clears old offers while an uncommitted state remains intact`() {
        val originalBooking = booking(id = "booking-a", status = "COMPLETED")
        val offer = slot("slot-new")
        val original = CustomerWorkflowState(
            bootstrapping = false,
            booking = originalBooking,
            bookings = listOf(originalBooking),
            bookingRescheduleId = originalBooking.bookingId,
            bookingRescheduleSlots = listOf(offer),
            selectedBookingRescheduleSlotId = offer.slotId,
        )
        val updated = originalBooking.copy(
            version = originalBooking.version + 1,
            slotId = offer.slotId,
            deliveryDate = offer.date,
            windowStart = offer.start,
            windowEnd = offer.end,
        )

        val successful = original.withSuccessfulBookingReschedule(updated)

        assertThat(successful.booking).isEqualTo(updated)
        assertThat(successful.bookings).containsExactly(updated)
        assertThat(successful.bookingRescheduleId).isNull()
        assertThat(successful.bookingRescheduleSlots).isEmpty()
        assertThat(successful.selectedBookingRescheduleSlotId).isNull()
        assertThat(original.booking).isEqualTo(originalBooking)
        assertThat(original.bookingRescheduleId).isEqualTo("booking-a")
        assertThat(original.bookingRescheduleSlots).containsExactly(offer)
        assertThat(original.selectedBookingRescheduleSlotId).isEqualTo("slot-new")
    }

    @Test
    fun `cart keeps each cabin configuration and rental term server scoped`() {
        val updatedTerm = AtomicReference<Pair<String, Long>?>()
        val updatedEquipment = AtomicReference<Triple<String, String, Long>?>()
        val removedCabin = AtomicReference<String?>()
        val cabin = CustomerCabin(
            unitId = "cabin-a",
            version = 3,
            accountingNo = "БК-1",
            type = "Блок-контейнер",
            finish = "Светлый дуб",
            dimensions = "6 × 2,4 × 2,5 м",
            characteristics = listOf("Усиленная дверь"),
        )
        val table = AvailableEquipment(
            inventoryItemId = "table",
            name = "Стол",
            category = "Мебель",
            availableQuantity = 3,
            maximumPerCabin = 2,
        )
        composeRule.setContent {
            CustomerTheme {
                CartScreen(
                    state = CustomerWorkflowState(
                        bootstrapping = false,
                        cabins = listOf(cabin),
                        selectedCabinIds = setOf(cabin.unitId),
                        equipment = listOf(table),
                        equipmentDraft = mapOf(EquipmentKey(cabin.unitId, table.inventoryItemId) to 1L),
                        rentalTerms = mapOf(cabin.unitId to 1L),
                    ),
                    onMenu = {},
                    onProfile = {},
                    onContinue = {},
                    onToggleCabin = removedCabin::set,
                    onCabinRentalMonths = { cabinId, months -> updatedTerm.set(cabinId to months) },
                    onEquipment = { cabinId, item, quantity ->
                        updatedEquipment.set(Triple(cabinId, item.inventoryItemId, quantity))
                    },
                )
            }
        }

        composeRule.onNodeWithTag("cart-cabin-cabin-a").assertExists()
        composeRule.onNodeWithTag("cart-cabin-photo-placeholder").assertExists()
        composeRule.onNodeWithText("Выбрано: 1").assertExists()
        composeRule.onNodeWithText("№ БК-1").assertExists()
        composeRule.onNodeWithText("Блок-контейнер").assertExists()
        composeRule.onNodeWithText("Светлый дуб").assertExists()
        composeRule.onNodeWithText("Стол · 1 шт.").assertExists()
        composeRule.onNodeWithText("Мебель по выбору").assertDoesNotExist()
        composeRule.onNodeWithText("Срок для всех бытовок").assertDoesNotExist()
        composeRule.onNodeWithText("+ Дополнительно").assertExists()
        composeRule.onNodeWithTag("cart-rental-decrement-cabin-a").assertIsNotEnabled()

        composeRule.onNodeWithTag("cart-rental-increment-cabin-a").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertThat(updatedTerm.get()).isEqualTo("cabin-a" to 2L)
        }

        composeRule.onNodeWithTag("cart-additional-cabin-a").performScrollTo().performClick()
        composeRule.onNodeWithTag("cart-additional-sheet-cabin-a").assertExists()
        composeRule.onNodeWithTag("cart-equipment-increment-cabin-a-table").performClick()
        composeRule.runOnIdle {
            assertThat(updatedEquipment.get()).isEqualTo(Triple("cabin-a", "table", 2L))
        }

        composeRule.onNodeWithTag("cart-remove-cabin-a").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertThat(removedCabin.get()).isEqualTo("cabin-a")
        }
    }

    private fun booking(
        id: String,
        status: String,
        errorCode: String? = null,
    ): CustomerBooking = CustomerBooking(
        bookingId = id,
        version = 4,
        orderId = "order-$id",
        status = status,
        errorCode = errorCode,
        inquiryId = "inquiry-$id",
        slotId = "slot-old",
        warehouseId = "warehouse-a",
        deliveryAddress = "Невский проспект, 1",
        deliveryDate = "2026-09-01",
        windowStart = "09:00:00",
        windowEnd = "12:00:00",
    )

    private fun slot(id: String): DeliverySlot = DeliverySlot(
        slotId = id,
        version = 5,
        date = "2026-09-02",
        kind = DeliverySlotKind.FIXED_WINDOW,
        start = "12:00:00",
        end = "15:00:00",
        travelZoneHours = 1,
        capacityRemaining = 1,
        deliveryPriceRubles = 10_000,
        priceIsochroneMinutes = 60,
        siteCabinCapacity = 1,
        roadRouteConfirmed = true,
        privateSiteAccessConfirmed = false,
        failedTripChargeAcknowledged = false,
        routeProfile = CustomerRouteProfile(4.0, 2.5, 9.0, 12.0, 8.0, 3),
        expiresAt = "2026-08-31T18:00:00Z",
        state = "OFFERED",
    )
}
