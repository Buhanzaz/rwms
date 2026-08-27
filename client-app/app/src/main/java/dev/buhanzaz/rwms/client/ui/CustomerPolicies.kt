package dev.buhanzaz.rwms.client.ui

import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.EquipmentSelection
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

    /** Checkout pending/completed states fence further cart commands for the active inquiry. */
    fun locksCart(booking: CustomerBooking?): Boolean = booking?.status in setOf("PENDING", "COMPLETED")

    private fun CustomerBooking.sameBookingAs(other: CustomerBooking): Boolean =
        (bookingId != null && bookingId == other.bookingId) || inquiryId == other.inquiryId

    private fun CustomerBooking.stableIdentity(): String = bookingId ?: inquiryId
}
