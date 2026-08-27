package dev.buhanzaz.rwms.client.ui

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
import dev.buhanzaz.rwms.client.data.CustomerProfile
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.round

/** Complete UI state for the signed-in customer funnel; no field is authoritative outside the server. */
data class CustomerWorkflowState(
    val bootstrapping: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val profile: CustomerProfile? = null,
    val warehouses: List<CustomerWarehouse> = emptyList(),
    val selectedWarehouse: CustomerWarehouse? = null,
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
    val cart: CustomerCart? = null,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val deliveryLocationConfirmed: Boolean = false,
    val slots: List<DeliverySlot> = emptyList(),
    val slotSearchCompleted: Boolean = false,
    val selectedSlotId: String? = null,
    val heldSlot: HeldDeliverySlot? = null,
    val rentalMonths: Long = 1,
    val booking: CustomerBooking? = null,
    val bookings: List<CustomerBooking> = emptyList(),
)

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
        val saved = repository.createProfile(profile)
        val warehouses = repository.warehouses()
        mutableWorkflow.value = mutableWorkflow.value.copy(profile = saved, warehouses = warehouses)
    }

    /** Creates an inquiry for the selected warehouse and loads free cabins/facets. */
    fun selectWarehouse(warehouse: CustomerWarehouse) = launchMutation {
        val reference = workflowStore.begin(warehouse.id)
        val session = repository.createInquiry(warehouse.id, reference.createIdempotencyKey)
        workflowStore.bind(reference, session)
        mutableWorkflow.value = mutableWorkflow.value.copy(
            selectedWarehouse = warehouse,
            inquiryId = session.inquiryId,
            selectionVersion = session.selectionVersion,
            facets = CabinFacets(),
            cabins = emptyList(),
            cabinPage = 0,
            cabinTotalPages = 0,
            filters = CabinFilters(),
            selectedCabinIds = emptySet(),
            equipmentDraft = emptyMap(),
            cart = null,
            address = "",
            latitude = null,
            longitude = null,
            deliveryLocationConfirmed = false,
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
        )
        val facets = repository.facets(session.inquiryId)
        val page = repository.cabins(session.inquiryId, CabinFilters(), 0)
        mutableWorkflow.value = mutableWorkflow.value.copy(
            facets = facets,
            cabins = page.content,
            cabinPage = page.page,
            cabinTotalPages = page.totalPages,
        )
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
        mutableWorkflow.value = current.copy(
            selectionVersion = cart.version,
            selectedCabinIds = selection.cabins.mapTo(mutableSetOf()) { it.unitId },
            equipmentDraft = draft.equipment,
            equipment = available,
            cart = cart,
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

    /** Stores an address draft and invalidates any earlier address-to-point confirmation. */
    fun setAddress(address: String) {
        if (mutationGate.isActive()) return
        mutableWorkflow.value = mutableWorkflow.value.copy(
            address = address,
            deliveryLocationConfirmed = false,
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
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
        )
    }

    /** Atomically confirms the address and coordinates returned by Yandex or entered manually. */
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

    /** Invalidates an earlier confirmation while the customer edits manual coordinate drafts. */
    fun invalidateDeliveryLocation() {
        if (mutationGate.isActive()) return
        mutableWorkflow.value = mutableWorkflow.value.copy(
            deliveryLocationConfirmed = false,
            slots = emptyList(),
            slotSearchCompleted = false,
            selectedSlotId = null,
            heldSlot = null,
        )
    }

    /** Searches only server-calculated slots for the entered address and map point. */
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
            DeliverySlotSearchRequest(inquiryId, current.address.trim(), latitude, longitude),
        )
        mutableWorkflow.value = current.copy(
            slots = slots,
            slotSearchCompleted = true,
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

    /** Rechecks capacity and places an expiring server hold on the selected slot. */
    fun holdSelectedSlot() = launchMutation {
        val current = mutableWorkflow.value
        requireMutableCart(current)
        val slotId = requireNotNull(current.selectedSlotId)
        val slot = current.slots.single { it.slotId == slotId }
        val held = repository.holdSlot(
            slotId,
            slot.version,
            requireNotNull(current.inquiryId),
            current.selectionVersion,
        )
        mutableWorkflow.value = current.copy(
            selectionVersion = held.cartVersion,
            heldSlot = held,
            booking = null,
        )
    }

    /** Changes the requested rental term within the server-supported positive range. */
    fun setRentalMonths(months: Long) {
        if (mutationGate.isActive()) return
        require(months in 1..120)
        mutableWorkflow.value = mutableWorkflow.value.copy(rentalMonths = months)
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
                current.rentalMonths,
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
        workflowStore.read()?.let { reference ->
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
                if (failure.status == 401) authRepository.invalidate(failure.message)
                if (failure.status == 409) reloadAfterConflict()
                mutableWorkflow.value = mutableWorkflow.value.copy(error = failure.message)
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
            mutableWorkflow.value = mutableWorkflow.value.copy(
                cart = cart,
                selectionVersion = cart.version,
                selectedCabinIds = cart.cabins.mapTo(mutableSetOf()) { it.unitId },
                equipmentDraft = cart.equipment.associate { selection ->
                    EquipmentKey(selection.cabinUnitId, selection.inventoryItemId) to selection.quantity
                },
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
        val session = try {
            reference.inquiryId?.let { repository.inquiry(it) }
                ?: repository.createInquiry(reference.warehouseId, reference.createIdempotencyKey).also { created ->
                    workflowStore.bind(reference, created)
                }
        } catch (failure: CustomerApiException) {
            if (failure.status == 404) workflowStore.clear()
            throw failure
        }
        validateRecoveredInquiry(reference, session)
        mutableWorkflow.value = mutableWorkflow.value.copy(
            selectedWarehouse = warehouse,
            inquiryId = session.inquiryId,
            selectionVersion = session.selectionVersion,
            facets = CabinFacets(),
            cabins = emptyList(),
            cabinPage = 0,
            cabinTotalPages = 0,
            filters = CabinFilters(),
        )
        val cart = repository.cart(session.inquiryId)
        val facets = repository.facets(session.inquiryId)
        val page = repository.cabins(session.inquiryId, CabinFilters(), 0)
        val equipment = repository.equipment(session.inquiryId).filter { it.availableQuantity > 0 }
        val bookings = repository.bookings()
        val booking = bookings.firstOrNull { it.inquiryId == session.inquiryId }
        mutableWorkflow.value = mutableWorkflow.value.copy(
            selectionVersion = cart.version,
            facets = facets,
            cabins = page.content,
            cabinPage = page.page,
            cabinTotalPages = page.totalPages,
            selectedCabinIds = cart.cabins.mapTo(mutableSetOf()) { it.unitId },
            equipment = equipment,
            equipmentDraft = cart.equipment.associate { selection ->
                EquipmentKey(selection.cabinUnitId, selection.inventoryItemId) to selection.quantity
            },
            cart = cart,
            booking = booking,
            bookings = bookings,
        )
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
        if (CustomerBookingPolicy.locksCart(current.booking)) {
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
