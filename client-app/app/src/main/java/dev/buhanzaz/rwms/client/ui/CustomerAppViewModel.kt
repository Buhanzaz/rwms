package dev.buhanzaz.rwms.client.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.client.auth.CustomerAuthRepository
import dev.buhanzaz.rwms.client.auth.CustomerAuthState
import dev.buhanzaz.rwms.client.data.AvailableEquipment
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CabinFilters
import dev.buhanzaz.rwms.client.data.CheckoutRequest
import dev.buhanzaz.rwms.client.data.CustomerApiException
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerCart
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerEvidenceFile
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerSignatureStroke
import dev.buhanzaz.rwms.client.data.CustomerRepository
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import dev.buhanzaz.rwms.client.data.CustomerWorkflowReference
import dev.buhanzaz.rwms.client.data.CustomerWorkflowStore
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotSearchRequest
import dev.buhanzaz.rwms.client.data.HeldDeliverySlot
import dev.buhanzaz.rwms.client.data.InquirySession
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round

/** Complete UI state for the signed-in customer funnel; no field is authoritative outside the server. */
data class CustomerWorkflowState(
    val bootstrapping: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val profile: CustomerProfile? = null,
    val warehouses: List<CustomerWarehouse> = emptyList(),
    val selectedWarehouse: CustomerWarehouse? = null,
    val rememberWarehouseChoice: Boolean = false,
    val inquiryId: String? = null,
    val selectionVersion: Long = 0,
    val facets: CabinFacets = CabinFacets(),
    val filters: CabinFilters = CabinFilters(),
    val cabins: List<CustomerCabin> = emptyList(),
    val cabinPage: Long = 0,
    val cabinTotalPages: Long = 0,
    val selectedCabinIds: Set<String> = emptySet(),
    val equipment: List<AvailableEquipment> = emptyList(),
    val equipmentDraft: Map<EquipmentKey, Long> = emptyMap(),
    val rentalTerms: Map<String, Long> = emptyMap(),
    val selectedRentalTermCabinIds: Set<String> = emptySet(),
    val cart: CustomerCart? = null,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val deliveryLocationConfirmed: Boolean = false,
    val siteCabinCapacity: Int = 1,
    val privateSiteAccessConfirmed: Boolean = false,
    val failedTripChargeAcknowledged: Boolean = false,
    val slots: List<DeliverySlot> = emptyList(),
    val slotSearchCompleted: Boolean = false,
    val slotSearchGeneration: Long = 0,
    val selectedSlotId: String? = null,
    val heldSlot: HeldDeliverySlot? = null,
    val booking: CustomerBooking? = null,
    val bookings: List<CustomerBooking> = emptyList(),
)

/**
 * Replaces customer access attestations while preserving offers and slot selection; an actual
 * answer change clears only a hold created with the previous answers.
 */
internal fun CustomerWorkflowState.withDeliveryAttestations(
    privateSiteAccessConfirmed: Boolean = this.privateSiteAccessConfirmed,
    failedTripChargeAcknowledged: Boolean = this.failedTripChargeAcknowledged,
): CustomerWorkflowState {
    if (this.privateSiteAccessConfirmed == privateSiteAccessConfirmed &&
        this.failedTripChargeAcknowledged == failedTripChargeAcknowledged
    ) {
        return this
    }
    return copy(
        privateSiteAccessConfirmed = privateSiteAccessConfirmed,
        failedTripChargeAcknowledged = failedTripChargeAcknowledged,
        heldSlot = null,
    )
}

/**
 * Replaces the address receiving capacity that constrains truck/trailer routing.
 *
 * A changed answer invalidates every offer and attestation calculated for the previous vehicle
 * configuration. A one-cabin cart is always served by the solo-truck capacity of one.
 */
internal fun CustomerWorkflowState.withSiteCabinCapacity(requestedCapacity: Int): CustomerWorkflowState {
    val normalized = normalizedSiteCabinCapacity(selectedCabinIds.size, requestedCapacity)
    if (siteCabinCapacity == normalized) return this
    return copy(
        siteCabinCapacity = normalized,
        privateSiteAccessConfirmed = false,
        failedTripChargeAcknowledged = false,
        slots = emptyList(),
        slotSearchCompleted = false,
        selectedSlotId = null,
        heldSlot = null,
    )
}

/** Returns the only supported receiving capacity for one cabin or validates the 1/2 choice. */
internal fun normalizedSiteCabinCapacity(selectedCabinCount: Int, requestedCapacity: Int): Int {
    require(selectedCabinCount >= 0)
    require(requestedCapacity in 1..2)
    return if (selectedCabinCount <= 1) 1 else requestedCapacity
}

/** App-level conditional state consumed by Navigation 3. */
sealed interface CustomerAppState {
    /** Encrypted session initialization. */
    data object Loading : CustomerAppState

    /** Login/register graph with an optional safe error. */
    data class SignedOut(val message: String? = null, val submitting: Boolean = false) : CustomerAppState

    /** Signed-in customer workflow graph. */
    data class Ready(val workflow: CustomerWorkflowState) : CustomerAppState
}

/** Orchestrates UI calls while the logistics service owns every booking transition. */
@HiltViewModel
class CustomerAppViewModel @Inject constructor(
    private val authRepository: CustomerAuthRepository,
    private val repository: CustomerRepository,
    private val workflowStore: CustomerWorkflowStore,
) : ViewModel() {
    private val mutableWorkflow = MutableStateFlow(CustomerWorkflowState())
    private val mutableState = MutableStateFlow<CustomerAppState>(CustomerAppState.Loading)
    private val mutationGate = CustomerMutationGate()
    private var bootstrappedSession = false

    /** Conditional signed-out/signed-in state. */
    val state: StateFlow<CustomerAppState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.state.collectLatest { auth ->
                when (auth) {
                    CustomerAuthState.Loading -> mutableState.value = CustomerAppState.Loading
                    CustomerAuthState.Authenticating -> mutableState.value = CustomerAppState.SignedOut(submitting = true)
                    is CustomerAuthState.SignedOut -> {
                        runCatching { workflowStore.clear() }
                        bootstrappedSession = false
                        mutableWorkflow.value = CustomerWorkflowState()
                        mutableState.value = CustomerAppState.SignedOut(auth.message)
                    }
                    CustomerAuthState.SignedIn -> {
                        mutableState.value = CustomerAppState.Ready(mutableWorkflow.value)
                        if (!bootstrappedSession) {
                            bootstrappedSession = true
                            bootstrap()
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            mutableWorkflow.collectLatest { workflow ->
                if (authRepository.state.value == CustomerAuthState.SignedIn) {
                    mutableState.value = CustomerAppState.Ready(workflow)
                }
            }
        }
    }

    /** Starts the PKCE customer login. */
    fun login(username: String, password: String) {
        viewModelScope.launch { authRepository.login(username, password) }
    }

    /** Registers and immediately signs in with the same non-persisted credentials. */
    fun register(username: String, password: String, confirmation: String) {
        viewModelScope.launch { authRepository.register(username, password, confirmation) }
    }

    /** Clears remote/local authorization and returns to the signed-out graph. */
    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    /** Saves a validated individual or legal profile. */
    fun saveProfile(profile: CustomerProfile) = launchMutation {
        validateProfile(profile)?.let { throw CustomerApiException(422, it) }
        val saved = if (profile.id == null) {
            repository.createProfile(profile)
        } else {
            repository.updateProfile(profile)
        }
        val warehouses = if (profile.id == null) repository.warehouses() else mutableWorkflow.value.warehouses
        mutableWorkflow.value = mutableWorkflow.value.copy(profile = saved, warehouses = warehouses)
    }

    /** Uploads and binds an avatar only after a warehouse has fixed its authorization scope. */
    fun uploadProfileAvatar(uri: Uri) = launchMutation {
        val current = mutableWorkflow.value
        val profile = current.profile
            ?: throw CustomerApiException(409, "Сначала сохраните профиль")
        val warehouse = current.selectedWarehouse
            ?: throw CustomerApiException(409, "Сначала выберите склад")
        val updated = withContext(Dispatchers.IO) {
            repository.uploadProfileAvatar(profile, warehouse.id, uri)
        }
        mutableWorkflow.value = mutableWorkflow.value.copy(profile = updated)
    }

    /** Selects a warehouse, optionally resumes its remembered inquiry, and loads free cabins. */
    fun selectWarehouse(
        warehouse: CustomerWarehouse,
        rememberWarehouse: Boolean,
    ) = launchMutation {
        val existing = workflowStore.read()
        if (existing?.warehouseId == warehouse.id && existing.inquiryId != null) {
            val updated = workflowStore.updateRemember(existing, rememberWarehouse)
            resumeWorkflow(updated, mutableWorkflow.value.warehouses)
            return@launchMutation
        }
        val reference = workflowStore.begin(warehouse.id, rememberWarehouse)
        val session = repository.createInquiry(warehouse.id, reference.createIdempotencyKey)
        val bound = workflowStore.bind(reference, session)
        activateWorkflow(bound, session, mutableWorkflow.value.warehouses)
    }

    /** Opens a fresh mutable cart when the current inquiry has already produced a booking. */
    fun ensureActiveInquiry() {
        val current = mutableWorkflow.value
        if (!CustomerInquiryRecoveryPolicy.requiresFreshInquiry(current.booking, current.inquiryId)) return
        launchMutation {
            val latest = mutableWorkflow.value
            if (!CustomerInquiryRecoveryPolicy.requiresFreshInquiry(latest.booking, latest.inquiryId)) {
                return@launchMutation
            }
            val warehouse = latest.selectedWarehouse
                ?: throw CustomerApiException(409, "Сначала выберите склад")
            restartWorkflow(workflowStore.read(), warehouse, latest.warehouses)
        }
    }

    /** Replaces cabin filters and reloads page zero from the authoritative free inventory. */
    fun applyFilters(filters: CabinFilters) = launchMutation {
        val inquiryId = requireNotNull(mutableWorkflow.value.inquiryId)
        val page = repository.cabins(inquiryId, filters, 0)
        mutableWorkflow.value = mutableWorkflow.value.copy(
            filters = filters,
            cabins = page.content,
            cabinPage = page.page,
            cabinTotalPages = page.totalPages,
        )
    }

    /** Appends the next authoritative page without duplicating cabins already rendered. */
    fun loadMoreCabins() {
        val snapshot = mutableWorkflow.value
        if (snapshot.busy || snapshot.cabinPage + 1 >= snapshot.cabinTotalPages) return
        launchMutation {
            val current = mutableWorkflow.value
            val page = repository.cabins(
                requireNotNull(current.inquiryId),
                current.filters,
                (current.cabinPage + 1).toInt(),
            )
            mutableWorkflow.value = current.copy(
                cabins = (current.cabins + page.content).distinctBy(CustomerCabin::unitId),
                cabinPage = page.page,
                cabinTotalPages = page.totalPages,
            )
        }
    }

    /** Adds/cancels a cabin with optimistic conflict fencing, then reloads cart/equipment. */
    fun toggleCabin(unitId: String) = launchMutation {
        val current = mutableWorkflow.value
        requireMutableCart(current)
        val inquiryId = requireNotNull(current.inquiryId)
        val draft = CustomerCartPolicy.toggleCabin(current.selectedCabinIds, current.equipmentDraft, unitId)
        val selection = repository.updateCabins(inquiryId, current.selectionVersion, draft.selectedCabins)
        val available = repository.equipment(inquiryId).filter { it.availableQuantity > 0 }
        val cart = repository.cart(inquiryId)
        val selectedIds = selection.cabins.mapTo(mutableSetOf()) { it.unitId }
        mutableWorkflow.value = current.copy(
            selectionVersion = cart.version,
            selectedCabinIds = selectedIds,
            equipmentDraft = draft.equipment,
            rentalTerms = cart.rentalTerms.associate { it.cabinUnitId to it.rentalMonths },
            selectedRentalTermCabinIds = current.selectedRentalTermCabinIds.intersect(
                selection.cabins.mapTo(mutableSetOf()) { it.unitId },
            ),
            equipment = available,
            cart = cart,
            siteCabinCapacity = normalizedSiteCabinCapacity(selectedIds.size, current.siteCabinCapacity),
            privateSiteAccessConfirmed = false,
            failedTripChargeAcknowledged = false,
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
            booking = null,
        )
    }

    /** Sets one cabin's furniture quantity and submits the complete replacement selection. */
    fun setEquipment(cabinUnitId: String, item: AvailableEquipment, quantity: Long) = launchMutation {
        val current = mutableWorkflow.value
        requireMutableCart(current)
        val inquiryId = requireNotNull(current.inquiryId)
        val draft = CustomerCartPolicy.withEquipment(
            selectedCabins = current.selectedCabinIds,
            equipment = current.equipmentDraft,
            key = EquipmentKey(cabinUnitId, item.inventoryItemId),
            quantity = quantity,
            available = item.availableQuantity,
        )
        repository.updateEquipment(
            inquiryId,
            current.selectionVersion,
            CustomerCartPolicy.toApiSelections(draft),
        )
        val cart = repository.cart(inquiryId)
        mutableWorkflow.value = current.copy(
            selectionVersion = cart.version,
            equipmentDraft = draft.equipment,
            cart = cart,
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
            booking = null,
        )
    }

    /** Selects which cart cabins receive the next bulk rental-duration change. */
    fun toggleRentalTermCabin(cabinUnitId: String) {
        if (mutationGate.isActive()) return
        val current = mutableWorkflow.value
        require(cabinUnitId in current.selectedCabinIds)
        val next = if (cabinUnitId in current.selectedRentalTermCabinIds) {
            current.selectedRentalTermCabinIds - cabinUnitId
        } else {
            current.selectedRentalTermCabinIds + cabinUnitId
        }
        mutableWorkflow.value = current.copy(selectedRentalTermCabinIds = next)
    }

    /** Applies one duration to checked cabins, or to the complete cart when none are checked. */
    fun setRentalMonths(months: Long) = launchMutation {
        require(months in 1..120)
        val current = mutableWorkflow.value
        requireMutableCart(current)
        val next = CustomerRentalTermPolicy.apply(
            current.selectedCabinIds,
            current.rentalTerms,
            current.selectedRentalTermCabinIds,
            months,
        )
        val response = repository.updateRentalTerms(
            requireNotNull(current.inquiryId),
            current.selectionVersion,
            next,
        )
        mutableWorkflow.value = current.copy(
            selectionVersion = response.version,
            rentalTerms = response.terms.associate { it.cabinUnitId to it.rentalMonths },
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
        )
    }

    /** Applies a duration override to exactly one selected cabin. */
    fun setCabinRentalMonths(cabinUnitId: String, months: Long) = launchMutation {
        require(months in 1..120)
        val current = mutableWorkflow.value
        require(cabinUnitId in current.selectedCabinIds)
        val next = CustomerRentalTermPolicy.apply(
            current.selectedCabinIds,
            current.rentalTerms,
            setOf(cabinUnitId),
            months,
        )
        val response = repository.updateRentalTerms(
            requireNotNull(current.inquiryId),
            current.selectionVersion,
            next,
        )
        mutableWorkflow.value = current.copy(
            selectionVersion = response.version,
            rentalTerms = response.terms.associate { it.cabinUnitId to it.rentalMonths },
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
        )
    }

    /** Stores an address draft and invalidates any earlier address-to-point confirmation. */
    fun setAddress(address: String) {
        if (mutationGate.isActive()) return
        mutableWorkflow.value = mutableWorkflow.value.copy(
            address = address,
            deliveryLocationConfirmed = false,
            siteCabinCapacity = 1,
            privateSiteAccessConfirmed = false,
            failedTripChargeAcknowledged = false,
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
        )
    }

    /** Stores a map-point draft and invalidates any earlier address-to-point confirmation. */
    fun setMapPoint(latitude: Double, longitude: Double) {
        if (mutationGate.isActive()) return
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        mutableWorkflow.value = mutableWorkflow.value.copy(
            latitude = latitude.roundedCoordinate(),
            longitude = longitude.roundedCoordinate(),
            deliveryLocationConfirmed = false,
            siteCabinCapacity = 1,
            privateSiteAccessConfirmed = false,
            failedTripChargeAcknowledged = false,
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
        )
    }

    /** Atomically confirms the address and coordinates returned by Yandex geocoding. */
    fun confirmDeliveryLocation(address: String, latitude: Double, longitude: Double) {
        if (mutationGate.isActive()) return
        require(address.isNotBlank()) { "Delivery address must not be blank" }
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        mutableWorkflow.value = mutableWorkflow.value.copy(
            address = address.trim(),
            latitude = latitude.roundedCoordinate(),
            longitude = longitude.roundedCoordinate(),
            deliveryLocationConfirmed = true,
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
        )
    }

    /**
     * Stores the private-site confirmation without discarding calculated offers or the selected slot.
     * A changed attestation invalidates only an already held slot because the hold binds both answers.
     */
    fun setPrivateSiteAccessConfirmed(confirmed: Boolean) {
        if (mutationGate.isActive()) return
        val current = mutableWorkflow.value
        mutableWorkflow.value = current.withDeliveryAttestations(privateSiteAccessConfirmed = confirmed)
    }

    /**
     * Stores failed-trip acknowledgement without changing offers or their selection.
     * A changed acknowledgement clears only an existing hold that used the previous answer.
     */
    fun setFailedTripChargeAcknowledged(acknowledged: Boolean) {
        if (mutationGate.isActive()) return
        val current = mutableWorkflow.value
        mutableWorkflow.value = current.withDeliveryAttestations(failedTripChargeAcknowledged = acknowledged)
    }

    /** Selects whether the address can receive one solo-truck cabin or two with a trailer. */
    fun setSiteCabinCapacity(capacity: Int) {
        if (mutationGate.isActive()) return
        mutableWorkflow.value = mutableWorkflow.value.withSiteCabinCapacity(capacity)
    }

    /**
     * Searches server-calculated slots for the confirmed address and point, forwarding the
     * current attestations as context without requiring them on the map step.
     */
    fun searchSlots() = launchMutation {
        val current = mutableWorkflow.value
        requireMutableCart(current)
        if (current.selectedCabinIds.isEmpty()) {
            throw CustomerApiException(422, "Добавьте хотя бы одну бытовку")
        }
        if (current.address.isBlank()) throw CustomerApiException(422, "Введите адрес доставки")
        val latitude = current.latitude ?: throw CustomerApiException(422, "Укажите точку доставки на карте")
        val longitude = current.longitude ?: throw CustomerApiException(422, "Укажите точку доставки на карте")
        if (!current.deliveryLocationConfirmed) {
            throw CustomerApiException(422, "Подтвердите соответствие адреса и точки доставки")
        }
        val inquiryId = requireNotNull(current.inquiryId)
        val slots = repository.searchSlots(
            DeliverySlotSearchRequest(
                inquiryId,
                current.address.trim(),
                latitude,
                longitude,
                siteCabinCapacity = current.siteCabinCapacity,
                privateSiteAccessConfirmed = current.privateSiteAccessConfirmed,
                failedTripChargeAcknowledged = current.failedTripChargeAcknowledged,
            ),
        )
        if (slots.any { slot -> slot.siteCabinCapacity != current.siteCabinCapacity }) {
            throw CustomerApiException(503, "Сервис вернул слот для другой вместимости объекта")
        }
        mutableWorkflow.value = current.copy(
            slots = slots,
            slotSearchCompleted = true,
            slotSearchGeneration = current.slotSearchGeneration + 1,
            selectedSlotId = null,
            heldSlot = null,
            booking = null,
        )
    }

    /** Selects one known server slot without implying that it is held yet. */
    fun selectSlot(slotId: String) {
        if (mutationGate.isActive()) return
        val current = mutableWorkflow.value
        val selected = DeliverySlotPolicy.select(current.slots.mapTo(mutableSetOf()) { it.slotId }, slotId)
        mutableWorkflow.value = current.copy(selectedSlotId = selected, heldSlot = null)
    }

    /** Rechecks capacity and holds the selected slot only with both customer attestations. */
    fun holdSelectedSlot() = launchMutation {
        val current = mutableWorkflow.value
        requireMutableCart(current)
        if (!current.privateSiteAccessConfirmed || !current.failedTripChargeAcknowledged) {
            throw CustomerApiException(422, "Подтвердите проезд на участок и ответственность за ложные сведения")
        }
        val slotId = requireNotNull(current.selectedSlotId)
        val slot = current.slots.single { it.slotId == slotId }
        if (slot.siteCabinCapacity != current.siteCabinCapacity) {
            throw CustomerApiException(409, "Вместимость объекта изменилась. Пересчитайте слоты")
        }
        val held = repository.holdSlot(
            slotId,
            slot.version,
            requireNotNull(current.inquiryId),
            current.selectionVersion,
            current.siteCabinCapacity,
            current.privateSiteAccessConfirmed,
            current.failedTripChargeAcknowledged,
        )
        mutableWorkflow.value = current.copy(
            selectionVersion = held.cartVersion,
            heldSlot = held,
            booking = null,
        )
    }

    /** Checks out only with the exact held slot/version and current optimistic selection version. */
    fun checkout() = launchMutation {
        val current = mutableWorkflow.value
        requireMutableCart(current)
        val held = current.heldSlot ?: throw CustomerApiException(409, "Сначала подтвердите выбранный слот")
        val inquiryId = requireNotNull(current.inquiryId)
        val booking = repository.checkout(
            inquiryId,
            CheckoutRequest(
                current.selectionVersion,
                held.slot.slotId,
                held.slot.version,
            ),
        )
        val bookings = repository.bookings()
        val reconciled = CustomerBookingPolicy.reconcile(booking, bookings)
        val rejected = reconciled.status == "REJECTED"
        mutableWorkflow.value = current.copy(
            booking = reconciled,
            bookings = bookings,
            slots = if (rejected) emptyList() else current.slots,
            slotSearchCompleted = if (rejected) false else current.slotSearchCompleted,
            selectedSlotId = if (rejected) null else current.selectedSlotId,
            heldSlot = if (rejected) null else current.heldSlot,
        )
    }

    /** Reloads real booking statuses from RWMS. */
    fun refreshBookings() = launchMutation {
        val current = mutableWorkflow.value
        val bookings = repository.bookings()
        val latest = current.booking?.let { CustomerBookingPolicy.reconcile(it, bookings) }
        mutableWorkflow.value = current.copy(booking = latest, bookings = bookings)
    }

    /** Accepts one arrived cabin and reloads its authoritative reception state. */
    fun acceptCabin(bookingId: String, cabinId: String, strokes: List<CustomerSignatureStroke>) = launchMutation {
        repository.acceptCabin(bookingId, cabinId, strokes)
        val bookings = repository.bookings()
        mutableWorkflow.value = mutableWorkflow.value.copy(bookings = bookings)
    }

    /** Uploads evidence to the exact shipment line and submits an arrived-cabin problem. */
    fun reportCabinProblem(
        bookingId: String,
        cabinId: String,
        category: String,
        description: String,
        evidence: List<CustomerEvidenceFile>,
    ) = launchMutation {
        if (description.isBlank()) throw CustomerApiException(422, "Опишите проблему")
        val booking = mutableWorkflow.value.bookings.firstOrNull { it.bookingId == bookingId }
            ?: throw CustomerApiException(404, "Заказ не найден")
        val cabin = booking.cabins.firstOrNull { it.cabinUnitId == cabinId }
            ?: throw CustomerApiException(404, "Бытовка не найдена")
        if (!cabin.arrivalEligible) throw CustomerApiException(409, "Бытовка ещё не прибыла")
        val owner = cabin.mediaOwner ?: throw CustomerApiException(409, "Медиа-владелец ещё не подготовлен")
        repository.reportProblem(bookingId, cabinId, owner, category, description, evidence)
        val bookings = repository.bookings()
        mutableWorkflow.value = mutableWorkflow.value.copy(bookings = bookings)
    }

    /** Clears a displayed failure without changing domain state. */
    fun dismissError() {
        mutableWorkflow.value = mutableWorkflow.value.copy(error = null)
    }

    private fun bootstrap() = launchMutation(showBusy = false) {
        val profile = repository.profileOrNull()
        val warehouses = if (profile != null) repository.warehouses() else emptyList()
        mutableWorkflow.value = mutableWorkflow.value.copy(
            bootstrapping = false,
            profile = profile,
            warehouses = warehouses,
        )
        if (profile == null) {
            workflowStore.clear()
            return@launchMutation
        }
        workflowStore.read()?.takeIf(CustomerWorkflowReference::rememberWarehouse)?.let { reference ->
            resumeWorkflow(reference, warehouses)
        }
    }

    private fun launchMutation(showBusy: Boolean = true, block: suspend () -> Unit) {
        if (!mutationGate.tryEnter()) return
        viewModelScope.launch {
            if (showBusy) mutableWorkflow.value = mutableWorkflow.value.copy(busy = true, error = null)
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CustomerApiException) {
                if (CustomerInquiryRecoveryPolicy.isArchivedInquiry(failure)) {
                    mutableWorkflow.value = mutableWorkflow.value.copy(error = recoverArchivedWorkflow())
                } else {
                    if (failure.status == 401) authRepository.invalidate(failure.message)
                    if (failure.status == 409) reloadAfterConflict()
                    mutableWorkflow.value = mutableWorkflow.value.copy(error = failure.message)
                }
            } catch (_: Throwable) {
                mutableWorkflow.value = mutableWorkflow.value.copy(error = "Не удалось выполнить действие")
            } finally {
                mutableWorkflow.value = mutableWorkflow.value.copy(busy = false, bootstrapping = false)
                mutationGate.leave()
            }
        }
    }

    private suspend fun reloadAfterConflict() {
        val inquiryId = mutableWorkflow.value.inquiryId ?: return
        runCatching { repository.cart(inquiryId) }.getOrNull()?.let { cart ->
            val selectedIds = cart.cabins.mapTo(mutableSetOf()) { it.unitId }
            mutableWorkflow.value = mutableWorkflow.value.copy(
                cart = cart,
                selectionVersion = cart.version,
                selectedCabinIds = selectedIds,
                siteCabinCapacity = normalizedSiteCabinCapacity(
                    selectedIds.size,
                    mutableWorkflow.value.siteCabinCapacity,
                ),
                equipmentDraft = cart.equipment.associate { selection ->
                    EquipmentKey(selection.cabinUnitId, selection.inventoryItemId) to selection.quantity
                },
                rentalTerms = cart.rentalTerms.associate { it.cabinUnitId to it.rentalMonths },
                slots = emptyList(),
                slotSearchCompleted = false,
                selectedSlotId = null,
                heldSlot = null,
            )
        }
    }

    private suspend fun resumeWorkflow(
        reference: CustomerWorkflowReference,
        warehouses: List<CustomerWarehouse>,
    ) {
        val warehouse = warehouses.firstOrNull { it.id == reference.warehouseId }
        if (warehouse == null) {
            workflowStore.clear()
            throw CustomerApiException(409, "Ранее выбранный склад больше недоступен")
        }
        val (activeReference, session) = try {
            reference.inquiryId?.let { inquiryId -> reference to repository.inquiry(inquiryId) }
                ?: repository.createInquiry(reference.warehouseId, reference.createIdempotencyKey).let { created ->
                    workflowStore.bind(reference, created) to created
                }
        } catch (failure: CustomerApiException) {
            if (failure.status == 404) workflowStore.clear()
            throw failure
        }
        validateRecoveredInquiry(activeReference, session)
        if (CustomerInquiryRecoveryPolicy.requiresFreshInquiry(session.state)) {
            restartWorkflow(activeReference, warehouse, warehouses)
            return
        }
        activateWorkflow(activeReference, session, warehouses)
    }

    /**
     * Replaces every cart-scoped UI field from one validated server session while retaining the
     * independent booking history returned by logistics.
     */
    private suspend fun activateWorkflow(
        reference: CustomerWorkflowReference,
        session: InquirySession,
        warehouses: List<CustomerWarehouse>,
    ) {
        validateRecoveredInquiry(reference, session)
        val warehouse = warehouses.firstOrNull { it.id == session.warehouseId }
            ?: throw CustomerApiException(409, "Ранее выбранный склад больше недоступен")
        mutableWorkflow.value = mutableWorkflow.value.copy(
            selectedWarehouse = warehouse,
            rememberWarehouseChoice = reference.rememberWarehouse,
            inquiryId = session.inquiryId,
            selectionVersion = session.selectionVersion,
            facets = CabinFacets(),
            cabins = emptyList(),
            cabinPage = 0,
            cabinTotalPages = 0,
            filters = CabinFilters(),
            selectedCabinIds = emptySet(),
            equipment = emptyList(),
            equipmentDraft = emptyMap(),
            rentalTerms = emptyMap(),
            selectedRentalTermCabinIds = emptySet(),
            cart = null,
            address = "",
            latitude = null,
            longitude = null,
            deliveryLocationConfirmed = false,
            siteCabinCapacity = 1,
            privateSiteAccessConfirmed = false,
            failedTripChargeAcknowledged = false,
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
            booking = null,
        )
        val cart = repository.cart(session.inquiryId)
        val facets = repository.facets(session.inquiryId)
        val page = repository.cabins(session.inquiryId, CabinFilters(), 0)
        val equipment = repository.equipment(session.inquiryId).filter { it.availableQuantity > 0 }
        val bookings = repository.bookings()
        val booking = bookings.firstOrNull { it.inquiryId == session.inquiryId }
        val selectedIds = cart.cabins.mapTo(mutableSetOf()) { it.unitId }
        mutableWorkflow.value = mutableWorkflow.value.copy(
            selectionVersion = cart.version,
            facets = facets,
            cabins = page.content,
            cabinPage = page.page,
            cabinTotalPages = page.totalPages,
            selectedCabinIds = selectedIds,
            siteCabinCapacity = normalizedSiteCabinCapacity(
                selectedIds.size,
                mutableWorkflow.value.siteCabinCapacity,
            ),
            equipment = equipment,
            equipmentDraft = cart.equipment.associate { selection ->
                EquipmentKey(selection.cabinUnitId, selection.inventoryItemId) to selection.quantity
            },
            rentalTerms = cart.rentalTerms.associate { it.cabinUnitId to it.rentalMonths },
            cart = cart,
            booking = booking,
            bookings = bookings,
        )
    }

    /**
     * Persists the replacement create intent before calling logistics, so an uncertain response
     * can be retried without creating duplicate carts.
     */
    private suspend fun restartWorkflow(
        reference: CustomerWorkflowReference?,
        warehouse: CustomerWarehouse,
        warehouses: List<CustomerWarehouse>,
    ) {
        val remembered = reference?.takeIf { it.warehouseId == warehouse.id }
        val pending = if (remembered?.inquiryId != null) {
            workflowStore.restart(remembered)
        } else {
            workflowStore.begin(warehouse.id, mutableWorkflow.value.rememberWarehouseChoice)
        }
        val session = repository.createInquiry(warehouse.id, pending.createIdempotencyKey)
        val bound = workflowStore.bind(pending, session)
        activateWorkflow(bound, session, warehouses)
    }

    /** Reconciles an explicit archived-inquiry conflict by opening a separate mutable cart. */
    private suspend fun recoverArchivedWorkflow(): String? {
        val current = mutableWorkflow.value
        val warehouse = current.selectedWarehouse ?: return "Выберите склад заново"
        return try {
            restartWorkflow(workflowStore.read(), warehouse, current.warehouses)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CustomerApiException) {
            if (failure.status == 401) authRepository.invalidate(failure.message)
            failure.message
        } catch (_: Throwable) {
            "Не удалось открыть новую корзину"
        }
    }

    private suspend fun validateRecoveredInquiry(
        reference: CustomerWorkflowReference,
        session: InquirySession,
    ) {
        if (session.warehouseId == reference.warehouseId &&
            (reference.inquiryId == null || session.inquiryId == reference.inquiryId)
        ) {
            return
        }
        workflowStore.clear()
        throw CustomerApiException(409, "Сохранённая корзина не соответствует выбранному складу")
    }

    private fun requireMutableCart(current: CustomerWorkflowState) {
        if (CustomerBookingPolicy.locksCart(current.booking, current.inquiryId)) {
            throw CustomerApiException(409, "Оформление уже выполняется; обновите статус заказа")
        }
    }
}

private fun validateProfile(profile: CustomerProfile): String? = when {
    profile.phone.trim().length < 7 -> "Укажите номер телефона"
    profile.entityType == CustomerEntityType.INDIVIDUAL && profile.firstName.isNullOrBlank() -> "Укажите имя"
    profile.entityType == CustomerEntityType.INDIVIDUAL && profile.lastName.isNullOrBlank() -> "Укажите фамилию"
    profile.entityType == CustomerEntityType.LEGAL && profile.companyName.isNullOrBlank() -> "Укажите компанию"
    else -> null
}

private fun Double.roundedCoordinate(): Double = round(this * 1_000_000.0) / 1_000_000.0
