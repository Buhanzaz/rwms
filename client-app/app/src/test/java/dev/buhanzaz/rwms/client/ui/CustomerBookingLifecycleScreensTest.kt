package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.AvailableEquipment
import dev.buhanzaz.rwms.client.data.BookingChangeApplicationState
import dev.buhanzaz.rwms.client.data.BookingChangeOperation
import dev.buhanzaz.rwms.client.data.BookingChangeSettlement
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerBookingCabin
import dev.buhanzaz.rwms.client.data.CustomerBookingChangeQuote
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerRouteProfile
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotKind
import java.time.Instant
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
    fun `completed booking requests cancellation terms without fabricating a fee or confirmation`() {
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

        composeRule.onNodeWithText("Оформлен").assertExists()
        composeRule.onNodeWithText("Обновить статусы").assertDoesNotExist()
        composeRule.onNodeWithText("Обновить заказы").assertDoesNotExist()
        composeRule.onNodeWithTag("profile-notification-settings").assertDoesNotExist()
        composeRule.onNodeWithText("Стоимость отмены: 0 ₽").assertDoesNotExist()
        composeRule.onNodeWithText("Стоимость отмены", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Отменить заказ").assertExists()
        composeRule.onNodeWithTag("booking-cancel-booking-a").performScrollTo().performClick()
        composeRule.onNodeWithTag("booking-change-dialog").assertDoesNotExist()

        composeRule.runOnIdle {
            assertThat(cancelled.get()).isEqualTo("booking-a")
        }
    }

    @Test
    fun `cancelled booking does not claim delivery is pending or offer reception for an old arrival`() {
        val cancelled = booking("booking-cancelled", "CANCELLED").copy(
            cabins = listOf(CustomerBookingCabin("cabin-a", "17102011", 2, "ARRIVED", true)),
        )
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(cancelled), latest = null, busy = false,
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Отменён").assertExists()
        composeRule.onNodeWithText("Доставка отменена").performScrollTo().assertExists()
        composeRule.onNodeWithText("Ожидает доставки").assertDoesNotExist()
        composeRule.onNodeWithText("Принять бытовку").assertDoesNotExist()
        composeRule.onNodeWithText("Сообщить до приёмки").assertDoesNotExist()
        composeRule.onNodeWithTag("booking-reschedule-booking-cancelled").assertDoesNotExist()
    }

    @Test
    fun `booking screen passes server quote consent and keeps replacement picker behind the fee dialog`() {
        val consent = AtomicReference<Boolean>()
        val phone = AtomicReference<String>()
        val quote = changeQuote()
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(booking("booking-a", "COMPLETED")), latest = null, busy = false,
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                    rescheduleBookingId = "booking-a", rescheduleSlots = listOf(slot("slot-new")),
                    selectedRescheduleSlotId = "slot-new", changeQuote = quote, changeDialogVisible = true,
                    onApplyChange = consent::set, onCallChangeSupport = phone::set,
                )
            }
        }
        composeRule.onNodeWithTag("booking-reschedule-dialog").assertDoesNotExist()
        composeRule.onNodeWithText("12 500 ₽").assertExists()
        composeRule.onNodeWithText("Новое время доставки: 2 сентября, среда · 12:00–15:00").assertExists()
        composeRule.onNodeWithText("Оплатить и перенести (тест)").performClick()
        composeRule.onNodeWithText("Связаться с менеджером").performClick()
        composeRule.onNodeWithText("Оплачено — тестовый режим").assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(consent.get()).isTrue()
            assertThat(phone.get()).isEqualTo("+74951234567")
        }
    }

    @Test
    fun `only expired or unconfigured offered terms are refreshed without discarding a waiver`() {
        val quote = changeQuote()
        val expiry = Instant.parse(quote.expiresAt)
        assertThat(quote.requiresFreshBookingChangeTerms(expiry.minusSeconds(1))).isFalse()
        assertThat(quote.requiresFreshBookingChangeTerms(expiry)).isTrue()
        assertThat(quote.copy(settlement = BookingChangeSettlement.WAIVED).requiresFreshBookingChangeTerms(expiry)).isFalse()
        assertThat(quote.copy(settlement = BookingChangeSettlement.POLICY_UNCONFIGURED)
            .requiresFreshBookingChangeTerms(expiry.minusSeconds(1))).isTrue()
        assertThat(quote.copy(applicationState = BookingChangeApplicationState.APPLYING)
            .requiresFreshBookingChangeTerms(expiry)).isFalse()
        assertThat(quote.copy(applicationState = BookingChangeApplicationState.APPLIED)
            .requiresFreshBookingChangeTerms(expiry)).isFalse()
    }

    @Test
    fun `newer exact manager waiver after rejected payment requires a new explicit free confirmation`() {
        val previous = changeQuote()
        val recovered = previous.copy(version = 1, settlement = BookingChangeSettlement.WAIVED, amountRubles = "0")
        val current = booking("booking-a", "COMPLETED").copy(version = previous.bookingVersion, slotId = previous.oldSlotId)
        val workflow = CustomerWorkflowState(bookings = listOf(current))
        val canConfirm = workflow.canConfirmRecoveredBookingChangeWaiver(
            previous, recovered, "CUSTOMER_CHANGE_QUOTE_STALE", true, Instant.parse(previous.expiresAt).minusSeconds(1),
        )
        assertThat(canConfirm).isTrue()
        val consent = AtomicReference<Boolean>()
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(current), latest = null, busy = false,
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                    changeQuote = recovered, changeDialogVisible = true,
                    changeUnavailableReason = if (canConfirm) null else "Условия устарели",
                    onApplyChange = consent::set,
                )
            }
        }
        composeRule.onNodeWithText("Менеджер отменил неустойку.").assertExists()
        composeRule.onNodeWithText("Оплатить и перенести (тест)").assertDoesNotExist()
        composeRule.runOnIdle { assertThat(consent.get()).isNull() }
        composeRule.onNodeWithText("Подтвердить перенос").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertThat(consent.get()).isFalse() }
    }

    @Test
    fun `unchanged waiver version does not unlock a rejected change`() {
        val previous = changeQuote()
        val recovered = previous.copy(settlement = BookingChangeSettlement.WAIVED, amountRubles = "0")
        val current = booking("booking-a", "COMPLETED").copy(version = previous.bookingVersion, slotId = previous.oldSlotId)
        val canConfirm = CustomerWorkflowState(bookings = listOf(current)).canConfirmRecoveredBookingChangeWaiver(
            previous, recovered, "CUSTOMER_CHANGE_QUOTE_STALE", true, Instant.parse(previous.expiresAt).minusSeconds(1),
        )
        assertThat(canConfirm).isFalse()
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(current), latest = null, busy = false,
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                    changeQuote = recovered, changeDialogVisible = true,
                    changeUnavailableReason = if (canConfirm) null else "Условия устарели",
                    onApplyChange = { error("A stale waiver must not apply") },
                )
            }
        }
        composeRule.onNodeWithText("Подтвердить перенос").assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("Связаться с менеджером").assertIsEnabled()
    }

    @Test
    fun `new waiver cannot override expired terms changed policy target or unavailable booking refresh`() {
        val previous = changeQuote()
        val recovered = previous.copy(version = 1, settlement = BookingChangeSettlement.WAIVED, amountRubles = "0")
        val current = booking("booking-a", "COMPLETED").copy(version = previous.bookingVersion, slotId = previous.oldSlotId)
        val workflow = CustomerWorkflowState(bookings = listOf(current))
        val now = Instant.parse(previous.expiresAt).minusSeconds(1)
        listOf(
            recovered.copy(quoteId = "different-quote"),
            recovered.copy(slotVersion = 2),
            recovered.copy(slotId = "different-slot"),
            recovered.copy(noticeDays = 3),
            recovered.copy(expiresAt = now.plusSeconds(60).toString()),
            recovered.copy(targetWindowStart = "13:00:00"),
        ).forEach { changed ->
            assertThat(workflow.canConfirmRecoveredBookingChangeWaiver(
                previous, changed, "CUSTOMER_CHANGE_QUOTE_STALE", true, now,
            )).isFalse()
        }
        assertThat(workflow.canConfirmRecoveredBookingChangeWaiver(
            previous, recovered, "CUSTOMER_CHANGE_QUOTE_STALE", true, Instant.parse(previous.expiresAt),
        )).isFalse()
        assertThat(workflow.canConfirmRecoveredBookingChangeWaiver(
            previous, recovered, "CUSTOMER_CHANGE_QUOTE_STALE", false, now,
        )).isFalse()
        listOf("CUSTOMER_DELIVERY_SLOT_EXPIRED", "CUSTOMER_DELIVERY_SLOT_TAKEN", "CUSTOMER_BOOKING_VERSION_CONFLICT").forEach { code ->
            assertThat(workflow.canConfirmRecoveredBookingChangeWaiver(previous, recovered, code, true, now)).isFalse()
        }
        listOf(
            emptyList(), listOf(current.copy(version = 8)), listOf(current.copy(slotId = "different-source")),
            listOf(current.copy(status = "CANCELLED")),
        ).forEach { bookings ->
            assertThat(workflow.copy(bookings = bookings).canConfirmRecoveredBookingChangeWaiver(
                previous, recovered, "CUSTOMER_CHANGE_QUOTE_STALE", true, now,
            )).isFalse()
        }
    }

    private fun changeQuote() = CustomerBookingChangeQuote(
        quoteId = "quote-a", version = 0, bookingId = "booking-a", bookingVersion = 7,
        operation = BookingChangeOperation.RESCHEDULE, oldSlotId = "slot-old", slotId = "slot-new", slotVersion = 1,
        amountRubles = "12500", settlement = BookingChangeSettlement.PAYMENT_REQUIRED,
        applicationState = BookingChangeApplicationState.OFFERED, testPaymentAvailable = true,
        supportPhone = "+7 (495) 123-45-67", expiresAt = "2026-09-01T15:00:00Z", noticeDays = 2,
        deliveryDate = "2026-09-02", warehouseTimeZone = "Europe/Moscow",
        targetDeliveryDate = "2026-09-02", targetWindowStart = "12:00:00", targetWindowEnd = "15:00:00",
    )

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
        composeRule.onNodeWithText("2 сентября, среда").assertExists()
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
    fun `reschedule dialog shows a uniform delivery price once`() {
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(booking(id = "booking-a", status = "COMPLETED")), latest = null, busy = false,
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                    rescheduleBookingId = "booking-a", rescheduleSlots = listOf(slot("slot-a"), slot("slot-b")),
                )
            }
        }

        composeRule.onAllNodesWithText("Стоимость доставки: 10 000 ₽").assertCountEquals(1)
    }

    @Test
    fun `reschedule dialog keeps delivery prices next to offers when they differ`() {
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(booking(id = "booking-a", status = "COMPLETED")), latest = null, busy = false,
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                    rescheduleBookingId = "booking-a",
                    rescheduleSlots = listOf(
                        slot("slot-a"),
                        slot("slot-b").copy(deliveryPriceRubles = 12_000),
                        slot("slot-c").copy(deliveryPriceRubles = null),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Стоимость доставки: 10 000 ₽").assertExists()
        composeRule.onNodeWithText("Стоимость доставки: 12 000 ₽").assertExists()
        composeRule.onNodeWithText("Стоимость доставки: Не рассчитана").assertExists()
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

        composeRule.onNodeWithText("Отмена выполняется").assertExists()
        composeRule.onNodeWithText("Отмена ещё выполняется. Ожидаем подтверждение сервиса.").assertExists()
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
            bookingRescheduleVersion = originalBooking.version,
            bookingRescheduleSourceSlotId = originalBooking.slotId,
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
        assertThat(successful.bookingRescheduleVersion).isNull()
        assertThat(successful.bookingRescheduleSourceSlotId).isNull()
        assertThat(successful.bookingRescheduleSlots).isEmpty()
        assertThat(successful.selectedBookingRescheduleSlotId).isNull()
        assertThat(original.booking).isEqualTo(originalBooking)
        assertThat(original.bookingRescheduleId).isEqualTo("booking-a")
        assertThat(original.bookingRescheduleSlots).containsExactly(offer)
        assertThat(original.selectedBookingRescheduleSlotId).isEqualTo("slot-new")
    }

    @Test
    fun `booking version change invalidates offers even when the current projection was already replaced`() {
        val source = booking(id = "booking-a", status = "COMPLETED")
        val offer = slot("slot-new")
        val refreshed = source.copy(version = source.version + 1)
        val current = rescheduleState(source, offer).copy(
            booking = refreshed,
            bookings = listOf(refreshed),
        )

        val reconciled = current.withReconciledBookings(listOf(refreshed))

        assertThat(reconciled.booking).isEqualTo(refreshed)
        assertThat(reconciled.bookings).containsExactly(refreshed)
        assertRescheduleCleared(reconciled)
        assertThat(current.bookingRescheduleSlots).containsExactly(offer)
    }

    @Test
    fun `different source slot invalidates replacement offers even with the same booking version`() {
        val source = booking(id = "booking-a", status = "COMPLETED")
        val refreshed = source.copy(slotId = "different-confirmed-slot")

        val reconciled = rescheduleState(source, slot("slot-new"))
            .withReconciledBookings(listOf(refreshed))

        assertRescheduleCleared(reconciled)
    }

    @Test
    fun `unchanged booking fence preserves exact replacement offers and selection`() {
        val source = booking(id = "booking-a", status = "COMPLETED")
        val offer = slot("slot-new")
        val current = rescheduleState(source, offer)

        val reconciled = current.withReconciledBookings(listOf(source.copy()))

        assertThat(reconciled.bookingRescheduleId).isEqualTo(source.bookingId)
        assertThat(reconciled.bookingRescheduleVersion).isEqualTo(source.version)
        assertThat(reconciled.bookingRescheduleSourceSlotId).isEqualTo(source.slotId)
        assertThat(reconciled.bookingRescheduleSlots).containsExactly(offer)
        assertThat(reconciled.selectedBookingRescheduleSlotId).isEqualTo(offer.slotId)
    }

    @Test
    fun `missing or no longer changeable booking clears its replacement selection`() {
        val source = booking(id = "booking-a", status = "COMPLETED")
        val current = rescheduleState(source, slot("slot-new"))

        listOf(
            emptyList(),
            listOf(source.copy(status = "CANCELLED")),
            listOf(source.copy(status = "CANCELLATION_PENDING")),
            listOf(source.copy(bookingId = "different-booking")),
        ).forEach { refreshed ->
            assertRescheduleCleared(current.withReconciledBookings(refreshed))
        }
    }

    @Test
    fun `offers without a captured booking fence are discarded on refresh`() {
        val source = booking(id = "booking-a", status = "COMPLETED")
        val current = rescheduleState(source, slot("slot-new")).copy(bookingRescheduleVersion = null)

        assertRescheduleCleared(current.withReconciledBookings(listOf(source)))
    }

    private fun rescheduleState(source: CustomerBooking, offer: DeliverySlot) = CustomerWorkflowState(
        booking = source,
        bookings = listOf(source),
        bookingRescheduleId = source.bookingId,
        bookingRescheduleVersion = source.version,
        bookingRescheduleSourceSlotId = source.slotId,
        bookingRescheduleSlots = listOf(offer),
        selectedBookingRescheduleSlotId = offer.slotId,
    )

    private fun assertRescheduleCleared(state: CustomerWorkflowState) {
        assertThat(state.bookingRescheduleId).isNull()
        assertThat(state.bookingRescheduleVersion).isNull()
        assertThat(state.bookingRescheduleSourceSlotId).isNull()
        assertThat(state.bookingRescheduleSlots).isEmpty()
        assertThat(state.selectedBookingRescheduleSlotId).isNull()
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
            pricingVersion = 3,
            monthlyPriceRubles = 12_000,
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
                    onBack = {},
                    onProfile = {},
                    onChooseCabin = {},
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
        composeRule.onNodeWithText("12 000 ₽/мес.").assertExists()
        composeRule.onNodeWithTag("cart-cabin-photo-placeholder").assertExists()
        val typeBounds = composeRule.onNodeWithTag("cart-cabin-type-cabin-a").fetchSemanticsNode().boundsInRoot
        val numberBounds = composeRule.onNodeWithTag("cart-cabin-number-cabin-a").fetchSemanticsNode().boundsInRoot
        assertThat(typeBounds.left).isLessThan(numberBounds.left)
        composeRule.onNodeWithText("Выбрано: 1").assertDoesNotExist()
        composeRule.onNodeWithText("№ БК-1").assertExists()
        composeRule.onNodeWithText("Блок-контейнер").assertExists()
        composeRule.onNodeWithText("Удалить").assertExists()
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

    @Test
    fun `empty cart offers a direct return to cabin selection`() {
        var chooseCabinCalls = 0
        composeRule.setContent {
            CustomerTheme {
                CartScreen(
                    state = CustomerWorkflowState(bootstrapping = false),
                    onBack = {},
                    onProfile = {},
                    onChooseCabin = { chooseCabinCalls++ },
                    onContinue = {},
                    onToggleCabin = {},
                    onCabinRentalMonths = { _, _ -> },
                    onEquipment = { _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("cart-delivery-button").assertDoesNotExist()
        composeRule.onNodeWithTag("cart-choose-cabin-button").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertThat(chooseCabinCalls).isEqualTo(1) }
    }

    @Test
    fun `cart loading and error states do not claim it is empty`() {
        var workflow by mutableStateOf(
            CustomerWorkflowState(bootstrapping = false, error = "Не удалось обновить корзину"),
        )
        composeRule.setContent {
            CustomerTheme {
                CartScreen(
                    state = workflow,
                    onBack = {}, onProfile = {}, onChooseCabin = {}, onContinue = {}, onToggleCabin = {},
                    onCabinRentalMonths = { _, _ -> }, onEquipment = { _, _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Не удалось обновить корзину").assertExists()
        composeRule.onNodeWithTag("cart-choose-cabin-button").assertDoesNotExist()

        composeRule.runOnIdle { workflow = workflow.copy(busy = true, error = null) }
        composeRule.onNodeWithTag("cart-loading").assertExists()
        composeRule.onNodeWithTag("cart-choose-cabin-button").assertDoesNotExist()
    }

    @Test
    fun `order loading is distinct from an empty order list`() {
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = emptyList(), latest = null, busy = true,
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("bookings-loading").assertExists()
        composeRule.onNodeWithText("Оформленных заказов пока нет").assertDoesNotExist()
    }

    @Test
    fun `failed bill loading replaces progress with the real error`() {
        var paymentErrors by mutableStateOf(emptyMap<String, String>())
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(booking("booking-bill", "COMPLETED").copy(orderId = "order-bill")),
                    latest = null,
                    busy = false,
                    paymentErrors = paymentErrors,
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("booking-payment-loading-booking-bill").assertExists()
        composeRule.runOnIdle { paymentErrors = mapOf("booking-bill" to "Не удалось загрузить счёт") }
        composeRule.onNodeWithTag("booking-payment-loading-booking-bill").assertDoesNotExist()
        composeRule.onNodeWithText("Не удалось загрузить счёт").assertExists()
    }

    @Test
    fun `payment result verification is shown as neutral pending information`() {
        composeRule.setContent {
            CustomerTheme {
                BookingsScreen(
                    bookings = listOf(booking("booking-checking", "COMPLETED")),
                    latest = null,
                    busy = false,
                    paymentErrors = mapOf("booking-checking" to CUSTOMER_PAYMENT_CHECKING_MESSAGE),
                    onMenu = {}, onProfile = {}, onAccept = { _, _, _ -> }, onReport = { _, _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("payment-checking-booking-checking").assertExists()
        composeRule.onNodeWithText(CUSTOMER_PAYMENT_CHECKING_MESSAGE).assertExists()
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
