package dev.buhanzaz.rwms.client.ui

import dev.buhanzaz.rwms.client.data.CustomerApiException
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.EquipmentSelection
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Pure selection rules shared by UI events and unit tests. */
object CustomerCartPolicy {
    /** Adds or removes one cabin and drops equipment from a removed cabin. */
    fun toggleCabin(
        selectedCabins: Set<String>,
        equipment: Map<EquipmentKey, Long>,
        cabinUnitId: String,
    ): SelectionDraft = if (cabinUnitId in selectedCabins) {
        SelectionDraft(
            selectedCabins - cabinUnitId,
            equipment.filterKeys { key -> key.cabinUnitId != cabinUnitId },
        )
    } else {
        SelectionDraft(selectedCabins + cabinUnitId, equipment)
    }

    /** Replaces one equipment quantity, removing zero and never allowing unavailable excess. */
    fun withEquipment(
        selectedCabins: Set<String>,
        equipment: Map<EquipmentKey, Long>,
        key: EquipmentKey,
        quantity: Long,
        available: Long,
    ): SelectionDraft {
        require(key.cabinUnitId in selectedCabins) { "Equipment requires a selected cabin" }
        require(quantity in 0..available) { "Equipment quantity exceeds warehouse availability" }
        val next = equipment.toMutableMap()
        if (quantity == 0L) next.remove(key) else next[key] = quantity
        return SelectionDraft(selectedCabins, next)
    }

    /** Converts a validated local draft into the complete server replacement request. */
    fun toApiSelections(draft: SelectionDraft): List<EquipmentSelection> = draft.equipment
        .toSortedMap(compareBy<EquipmentKey> { it.cabinUnitId }.thenBy { it.inventoryItemId })
        .map { (key, quantity) -> EquipmentSelection(key.cabinUnitId, key.inventoryItemId, quantity) }
}

/** Stable composite identity for a furniture position assigned to one cabin. */
data class EquipmentKey(val cabinUnitId: String, val inventoryItemId: String)

/** Local proposed selection submitted atomically to the owning server. */
data class SelectionDraft(
    val selectedCabins: Set<String>,
    val equipment: Map<EquipmentKey, Long>,
)

/** Pure complete-replacement rules for one selected cabin's rental duration. */
object CustomerRentalTermPolicy {
    /** Replaces one selected cabin's bounded term while preserving every other selected cabin's term. */
    fun withCabinTerm(
        selectedCabins: Set<String>,
        existingTerms: Map<String, Long>,
        cabinUnitId: String,
        months: Long,
    ): Map<String, Long> {
        require(selectedCabins.isNotEmpty())
        require(months in 1..120)
        require(cabinUnitId in selectedCabins)
        return selectedCabins.associateWith { cabinId ->
            if (cabinId == cabinUnitId) months else existingTerms[cabinId] ?: 1L
        }
    }
}

/** Pure slot selection rules; only server-returned slots can ever become selected. */
object DeliverySlotPolicy {
    /** Returns a chosen server slot ID or rejects an unknown/stale identifier. */
    fun select(availableSlotIds: Set<String>, requestedSlotId: String): String {
        require(requestedSlotId in availableSlotIds) { "Only an available server slot can be selected" }
        return requestedSlotId
    }

    /** Groups server offers into ordered dates without creating local availability. */
    fun byDate(slots: List<DeliverySlot>): List<DeliveryDateAvailability> = slots
        .groupBy(DeliverySlot::date)
        .toSortedMap()
        .map { (date, dateSlots) ->
            DeliveryDateAvailability(
                date = date,
                slots = dateSlots.sortedWith(
                    compareBy<DeliverySlot>(DeliverySlot::start)
                        .thenBy(DeliverySlot::end)
                        .thenBy(DeliverySlot::slotId),
                ),
            )
        }

    /**
     * Returns the one server tariff shared by all offers for the selected point.
     *
     * Missing or contradictory values stay unknown so the UI never invents a zero-price delivery.
     */
    fun deliveryPriceRubles(slots: List<DeliverySlot>): Int? {
        val values = slots.map(DeliverySlot::deliveryPriceRubles).distinct()
        return values.singleOrNull()?.takeIf { it >= 0 }
    }

    /** Describes the one server-owned tariff source without treating it as route feasibility. */
    fun deliveryTariffSource(slots: List<DeliverySlot>): String? {
        val sources = slots.map { slot ->
            when {
                slot.priceZoneId != null && slot.priceIsochroneMinutes == null -> "Особая зона доставки"
                slot.priceZoneId == null && slot.priceIsochroneMinutes != null ->
                    "Изохрона ${slot.priceIsochroneMinutes / 60} ч"
                else -> null
            }
        }.distinct()
        return sources.singleOrNull()
    }
}

/** One display-only date grouping whose slots remain exact logistics responses. */
data class DeliveryDateAvailability(
    val date: String,
    val slots: List<DeliverySlot>,
)

/** Normalizes the first non-blank phrase returned by the device speech recognizer. */
internal object DeliveryVoicePolicy {
    /** Returns a trimmed address candidate or null when speech produced no usable text. */
    fun recognizedAddress(candidates: List<String>?): String? = candidates
        ?.asSequence()
        ?.map(String::trim)
        ?.firstOrNull(String::isNotEmpty)
}

/** Rejects callbacks from canceled or superseded asynchronous geocoder requests. */
internal class LatestDeliveryGeocodeGate {
    private var generation = 0L

    /** Starts a new generation and returns the token accepted for its callback. */
    fun begin(): Long = ++generation

    /** Invalidates the active generation without accepting a late callback. */
    fun invalidate() {
        generation++
    }

    /** Returns whether a callback still belongs to the most recent request. */
    fun isCurrent(token: Long): Boolean = token == generation
}

/** Prevents date navigation until a newer server slot search completed successfully. */
internal object SlotSearchNavigationPolicy {
    /** Allows navigation only for a completed generation newer than the tapped request baseline. */
    fun canNavigate(baseline: Long?, current: Long, busy: Boolean): Boolean =
        baseline != null && !busy && current > baseline
}

/** Chooses the current delivery point or, before one exists, the selected warehouse depot. */
internal fun deliveryMapInitialTarget(
    latitude: Double?,
    longitude: Double?,
    depotLatitude: Double,
    depotLongitude: Double,
): Pair<Double, Double> = if (latitude != null && longitude != null) {
    latitude to longitude
} else {
    depotLatitude to depotLongitude
}

/** Produces one readable address without duplicating equal Yandex object labels. */
internal fun deliveryDisplayAddress(
    description: String?,
    name: String?,
    fallback: String?,
): String = listOfNotNull(description?.trim(), name?.trim())
    .filter(String::isNotEmpty)
    .distinct()
    .joinToString(", ")
    .ifBlank { fallback?.trim().orEmpty() }

/** Pure readiness rules for binding one visible address to one exact delivery point. */
object DeliveryLocationPolicy {
    /** Returns true only when a confirmed address/point and a non-empty authoritative cart can be searched. */
    fun canSearchSlots(
        address: String,
        latitude: Double?,
        longitude: Double?,
        confirmed: Boolean,
        selectedCabinCount: Int,
    ): Boolean = confirmed &&
        address.isNotBlank() &&
        latitude != null && latitude in -90.0..90.0 &&
        longitude != null && longitude in -180.0..180.0 &&
        selectedCabinCount > 0
}

/** Prevents two remotely effective customer commands from overlapping in one ViewModel. */
internal class CustomerMutationGate {
    private val active = AtomicBoolean(false)

    /** Claims the single command lane without waiting or queuing a stale duplicate gesture. */
    fun tryEnter(): Boolean = active.compareAndSet(false, true)

    /** Releases the command lane after success, failure, or cancellation. */
    fun leave() {
        check(active.compareAndSet(true, false)) { "Customer mutation gate was not active" }
    }

    /** Whether local form changes must currently be ignored to protect the command snapshot. */
    fun isActive(): Boolean = active.get()
}

/** Reconciles checkout responses with the fresher durable booking list returned by logistics. */
internal object CustomerBookingPolicy {
    /** Chooses the authoritative list item for the submitted inquiry when it is already visible. */
    fun reconcile(submitted: CustomerBooking, bookings: List<CustomerBooking>): CustomerBooking =
        bookings.firstOrNull { candidate -> candidate.sameBookingAs(submitted) } ?: submitted

    /** Merges an optional in-memory response without letting it shadow a newer listed status. */
    fun visible(latest: CustomerBooking?, bookings: List<CustomerBooking>): List<CustomerBooking> =
        (bookings + listOfNotNull(latest)).distinctBy { booking -> booking.stableIdentity() }

    /** Applies an immediate command response until the following authoritative list refresh. */
    fun replace(updated: CustomerBooking, bookings: List<CustomerBooking>): List<CustomerBooking> =
        listOf(updated) + bookings.filterNot { booking -> booking.sameBookingAs(updated) }

    /** Every accepted checkout lifecycle keeps its original inquiry immutable. */
    fun locksCart(booking: CustomerBooking?): Boolean = booking?.status in setOf(
        "PENDING",
        "COMPLETED",
        "CANCELLATION_PENDING",
        "CANCELLED",
    )

    /** A booking from an earlier inquiry never locks a newly opened cart. */
    fun locksCart(booking: CustomerBooking?, activeInquiryId: String?): Boolean =
        activeInquiryId != null && booking?.inquiryId == activeInquiryId && locksCart(booking)

    private fun CustomerBooking.sameBookingAs(other: CustomerBooking): Boolean =
        (bookingId != null && bookingId == other.bookingId) || inquiryId == other.inquiryId

    private fun CustomerBooking.stableIdentity(): String = bookingId ?: inquiryId
}

/** Pure CustomerApp presentation and action rules for service-owned booking mutations. */
internal object CustomerBookingLifecyclePolicy {
    /** Only an exact completed booking can offer cancellation or rescheduling controls. */
    fun canChange(booking: CustomerBooking): Boolean =
        booking.bookingId != null && booking.version > 0 && booking.status == "COMPLETED"

    /** Returns the Russian lifecycle label without exposing a backend enum to the customer. */
    fun statusLabel(status: String): String = when (status) {
        "COMPLETED" -> "Оформлен"
        "REJECTED" -> "Отклонён"
        "PENDING" -> "Обрабатывается"
        "CANCELLATION_PENDING" -> "Отмена выполняется"
        "CANCELLED" -> "Отменён"
        else -> "Статус уточняется"
    }

    /** Converts stable recovery codes carried by a booking projection into actionable copy. */
    fun recoveryMessage(errorCode: String?): String? = when (errorCode) {
        null -> null
        "CUSTOMER_BOOKING_CANCELLATION_PENDING" ->
            "Отмена ещё выполняется. Обновите статус немного позже."
        "CUSTOMER_BOOKING_CANCELLATION_FAILED" ->
            "Не удалось завершить отмену. Обновите статус или повторите попытку позже."
        "CUSTOMER_BOOKING_RECONCILIATION_REQUIRED" ->
            "Отмена требует проверки сотрудником RWMS. Текущий статус сохранён."
        "ORDER_MUTATION_RECONCILIATION_REQUIRED",
        "CUSTOMER_BOOKING_MUTATION_FAILED",
        "ORDER_MUTATION_RECOVERY_FAILED",
        "ORDER_MUTATION_LOCAL_RECONCILIATION_FAILED",
        -> "Изменение заказа требует проверки сотрудником RWMS. Текущий статус сохранён."
        else -> "Статус операции уточняется. Обновите заказ немного позже."
    }

    /** Formats a real server fee, while an absent policy remains absent rather than becoming zero. */
    fun cancellationFeeLabel(
        amount: Long?,
        locale: Locale = Locale.forLanguageTag("ru-RU"),
    ): String? = amount?.let { value ->
        val formatted = NumberFormat.getIntegerInstance(locale).format(value)
            .replace('\u00a0', ' ')
            .replace('\u202f', ' ')
        "$formatted ₽"
    }

    /** Orders only exact server offers and never creates a local replacement date. */
    fun orderedSlots(slots: List<DeliverySlot>): List<DeliverySlot> =
        slots.sortedWith(compareBy(DeliverySlot::date, DeliverySlot::start, DeliverySlot::slotId))
}

/** Decides when a terminal customer inquiry must be replaced without discarding its booking. */
internal object CustomerInquiryRecoveryPolicy {
    /** The customer session exposes `BOOKED` once that cart can no longer serve catalogue calls. */
    fun requiresFreshInquiry(sessionState: String): Boolean = sessionState in setOf("BOOKED", "CANCELLED")

    /** Handles an older or temporarily inconsistent session whose underlying inquiry is archived. */
    fun isArchivedInquiry(failure: CustomerApiException): Boolean =
        failure.status == 409 && failure.code == "INQUIRY_ARCHIVED"

    /** A submitted booking makes only its source inquiry immutable; another cart stays editable. */
    fun requiresFreshInquiry(booking: CustomerBooking?, activeInquiryId: String?): Boolean =
        activeInquiryId != null &&
            booking?.inquiryId == activeInquiryId &&
            CustomerBookingPolicy.locksCart(booking)
}
