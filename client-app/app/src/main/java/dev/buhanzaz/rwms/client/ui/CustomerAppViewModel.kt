package dev.buhanzaz.rwms.client.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.client.auth.CustomerAuthRepository
import dev.buhanzaz.rwms.client.auth.CustomerAuthState
import dev.buhanzaz.rwms.client.data.AvailableEquipment
import dev.buhanzaz.rwms.client.data.BookingChangeApplicationState
import dev.buhanzaz.rwms.client.data.BookingChangeOperation
import dev.buhanzaz.rwms.client.data.BookingChangeSettlement
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CabinFilters
import dev.buhanzaz.rwms.client.data.CheckoutRequest
import dev.buhanzaz.rwms.client.data.CustomerApiException
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerBookingChangeQuote
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerCart
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerEvidenceFile
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerNotification
import dev.buhanzaz.rwms.client.notifications.CustomerNotifications
import dev.buhanzaz.rwms.client.data.CustomerSignatureStroke
import dev.buhanzaz.rwms.client.data.CustomerRepository
import dev.buhanzaz.rwms.client.data.PublicCustomerCatalogRepository
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import dev.buhanzaz.rwms.client.data.CustomerWorkflowReference
import dev.buhanzaz.rwms.client.data.CustomerWorkflowStore
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotSearchRequest
import dev.buhanzaz.rwms.client.data.HeldDeliverySlot
import dev.buhanzaz.rwms.client.data.InquirySession
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round

/** Contact values collected during account registration and reused by the mandatory profile step. */
data class CustomerRegistrationProfileDraft(
    val email: String,
    val phone: String,
)

/** Complete UI state for the signed-in customer funnel; no field is authoritative outside the server. */
data class CustomerWorkflowState(
    val bootstrapping: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val profile: CustomerProfile? = null,
    val registrationProfileDraft: CustomerRegistrationProfileDraft? = null,
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
    val estimatedDeliveryDates: List<String> = emptyList(),
    val selectedCabinIds: Set<String> = emptySet(),
    val equipment: List<AvailableEquipment> = emptyList(),
    val equipmentDraft: Map<EquipmentKey, Long> = emptyMap(),
    val rentalTerms: Map<String, Long> = emptyMap(),
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
    val payments: Map<String, CustomerObservedPayment> = emptyMap(),
    val paymentErrors: Map<String, String> = emptyMap(),
    val notifications: List<CustomerNotification> = emptyList(),
    val updatesError: String? = null,
    val automaticUpdatesPaused: Boolean = false,
    val bookingRescheduleId: String? = null,
    val bookingRescheduleVersion: Long? = null,
    val bookingRescheduleSourceSlotId: String? = null,
    val bookingRescheduleSlots: List<DeliverySlot> = emptyList(),
    val selectedBookingRescheduleSlotId: String? = null,
    val bookingChangeQuote: CustomerBookingChangeQuote? = null,
    val bookingChangeDialogVisible: Boolean = false,
    val bookingChangeNeedsRefresh: Boolean = false,
    val bookingChangeUnavailableReason: String? = null,
    val bookingChangeReadError: String? = null,
    val bookingChangeReferences: Map<String, String> = emptyMap(),
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

/**
 * Chooses the media warehouse without making the profile screen depend on a selected rental
 * catalog. A previously bound avatar keeps its immutable warehouse; otherwise the current catalog
 * choice wins, followed by an already authorized warehouse returned by the service.
 */
internal fun CustomerWorkflowState.avatarUploadWarehouseId(): String? =
    profile?.avatar?.warehouseId
        ?: selectedWarehouse?.id
        ?: warehouses.firstOrNull()?.id

/** Applies a confirmed atomic slot swap and discards every offer calculated for the old booking. */
internal fun CustomerWorkflowState.withSuccessfulBookingReschedule(
    updated: CustomerBooking,
): CustomerWorkflowState {
    val latest = booking?.let { current ->
        if (current.bookingId == updated.bookingId || current.inquiryId == updated.inquiryId) {
            updated
        } else {
            current
        }
    }
    return copy(
        booking = latest,
        bookings = CustomerBookingPolicy.replace(updated, bookings),
        bookingRescheduleId = null,
        bookingRescheduleVersion = null,
        bookingRescheduleSourceSlotId = null,
        bookingRescheduleSlots = emptyList(),
        selectedBookingRescheduleSlotId = null,
    )
}

/** Keeps replacement offers only while the refreshed booking matches their exact search source. */
internal fun CustomerWorkflowState.withReconciledBookings(
    refreshedBookings: List<CustomerBooking>,
): CustomerWorkflowState {
    val latest = booking?.let { current -> CustomerBookingPolicy.reconcile(current, refreshedBookings) }
    val rescheduleId = bookingRescheduleId?.takeIf { bookingId ->
        bookingRescheduleVersion != null && refreshedBookings.any { refreshed ->
            refreshed.bookingId == bookingId &&
                CustomerBookingLifecyclePolicy.canChange(refreshed) &&
                refreshed.version == bookingRescheduleVersion &&
                refreshed.slotId == bookingRescheduleSourceSlotId
        }
    }
    return copy(
        booking = latest,
        bookings = refreshedBookings,
        bookingRescheduleId = rescheduleId,
        bookingRescheduleVersion = if (rescheduleId == null) null else bookingRescheduleVersion,
        bookingRescheduleSourceSlotId = if (rescheduleId == null) null else bookingRescheduleSourceSlotId,
        bookingRescheduleSlots = if (rescheduleId == null) emptyList() else bookingRescheduleSlots,
        selectedBookingRescheduleSlotId = if (rescheduleId == null) null else selectedBookingRescheduleSlotId,
    )
}

/** Refreshes expired/unconfigured offered terms, but never silently replaces a manager waiver. */
internal fun CustomerBookingChangeQuote.requiresFreshBookingChangeTerms(now: Instant): Boolean =
    applicationState == BookingChangeApplicationState.OFFERED && settlement != BookingChangeSettlement.WAIVED &&
        (settlement == BookingChangeSettlement.POLICY_UNCONFIGURED || !now.isBefore(Instant.parse(expiresAt)))

/** Applies only exact owner observations; reading cannot reopen a dialog or replace protected terms. */
internal fun CustomerWorkflowState.withObservedBookingChange(
    quote: CustomerBookingChangeQuote,
    bookingsReloaded: Boolean,
    now: Instant,
    reveal: Boolean = false,
): CustomerWorkflowState {
    val previous = bookingChangeQuote?.takeIf { it.quoteId == quote.quoteId && it.bookingId == quote.bookingId }
    val restoringReference = bookingChangeQuote == null && bookingChangeReferences[quote.bookingId] == quote.quoteId
    if (!reveal && previous == null && !restoringReference) return this
    val offered = quote.applicationState == BookingChangeApplicationState.OFFERED
    val waived = quote.settlement == BookingChangeSettlement.WAIVED
    val source = CustomerBookingPolicy.visible(booking, bookings).firstOrNull { it.bookingId == quote.bookingId }
    val sourceMatches = source != null && CustomerBookingLifecyclePolicy.canChange(source) &&
        source.version == quote.bookingVersion && source.slotId == quote.oldSlotId
    val unavailable = when {
        !offered -> null
        waived && previous?.settlement == BookingChangeSettlement.WAIVED && bookingChangeUnavailableReason != null ->
            bookingChangeUnavailableReason
        !now.isBefore(Instant.parse(quote.expiresAt)) -> if (waived) {
            "Выбранное время устарело. Свяжитесь с менеджером, чтобы сохранить освобождение от неустойки."
        } else {
            "Срок действия условий истёк. Вернитесь к заказу и снова выберите отмену или перенос."
        }
        bookingsReloaded && !sourceMatches -> if (waived) {
            "Заказ изменился. Свяжитесь с менеджером, чтобы сохранить освобождение от неустойки."
        } else {
            "Заказ изменился. Вернитесь к заказу и снова выберите отмену или перенос."
        }
        !bookingsReloaded && previous != null -> bookingChangeUnavailableReason
        else -> null
    }
    return copy(
        bookingChangeQuote = quote,
        bookingChangeDialogVisible = bookingChangeDialogVisible || reveal,
        bookingChangeNeedsRefresh = offered && !bookingsReloaded,
        bookingChangeUnavailableReason = unavailable,
        bookingChangeReadError = null,
        bookingChangeReferences = bookingChangeReferences + (quote.bookingId to quote.quoteId),
        error = error.takeUnless { previous != null && bookingChangeNeedsRefresh && !offered },
    )
}

/** Only a newer exact waiver may replace a rejected payment consent with a fresh free confirmation. */
internal fun CustomerWorkflowState.canConfirmRecoveredBookingChangeWaiver(
    previous: CustomerBookingChangeQuote?,
    recovered: CustomerBookingChangeQuote?,
    failureCode: String?,
    bookingReloaded: Boolean,
    now: Instant,
): Boolean {
    if (failureCode != "CUSTOMER_CHANGE_QUOTE_STALE" || !bookingReloaded || previous == null || recovered == null) {
        return false
    }
    val currentBooking = bookings.firstOrNull { it.bookingId == recovered.bookingId } ?: return false
    return previous.applicationState == BookingChangeApplicationState.OFFERED &&
        previous.settlement == BookingChangeSettlement.PAYMENT_REQUIRED &&
        recovered.applicationState == BookingChangeApplicationState.OFFERED &&
        recovered.settlement == BookingChangeSettlement.WAIVED && recovered.amountRubles == "0" &&
        recovered.quoteId == previous.quoteId && recovered.version > previous.version &&
        recovered.bookingId == previous.bookingId && recovered.bookingVersion == previous.bookingVersion &&
        recovered.oldSlotId == previous.oldSlotId && recovered.operation == previous.operation &&
        recovered.slotId == previous.slotId && recovered.slotVersion == previous.slotVersion &&
        recovered.targetDeliveryDate == previous.targetDeliveryDate &&
        recovered.targetWindowStart == previous.targetWindowStart && recovered.targetWindowEnd == previous.targetWindowEnd &&
        recovered.noticeDays == previous.noticeDays && recovered.deliveryDate == previous.deliveryDate &&
        recovered.warehouseTimeZone == previous.warehouseTimeZone && recovered.expiresAt == previous.expiresAt &&
        now.isBefore(Instant.parse(recovered.expiresAt)) && CustomerBookingLifecyclePolicy.canChange(currentBooking) &&
        currentBooking.version == recovered.bookingVersion && currentBooking.slotId == recovered.oldSlotId
}

/** App-level conditional state consumed by Navigation 3. */
sealed interface CustomerAppState {
    /** Encrypted session initialization. */
    data object Loading : CustomerAppState

    /** Login/register graph with an optional safe error. */
    data class SignedOut(
        val message: String? = null,
        val submitting: Boolean = false,
        val showLogin: Boolean = false,
    ) : CustomerAppState

    /** Anonymous catalog reads, structurally separate from the customer workflow. */
    data class GuestCatalog(val catalog: CustomerGuestCatalogState) : CustomerAppState

    /** Signed-in customer workflow graph. */
    data class Ready(val workflow: CustomerWorkflowState) : CustomerAppState
}

/** Orchestrates UI calls while the logistics service owns every booking transition. */
@HiltViewModel
class CustomerAppViewModel @Inject constructor(
    private val authRepository: CustomerAuthRepository,
    private val repository: CustomerRepository,
    private val workflowStore: CustomerWorkflowStore,
    private val notifications: CustomerNotifications,
    private val publicCatalog: PublicCustomerCatalogRepository,
) : ViewModel() {
    private val mutableWorkflow = MutableStateFlow(CustomerWorkflowState())
    private val mutableState = MutableStateFlow<CustomerAppState>(CustomerAppState.Loading)
    private val mutationGate = CustomerMutationGate()
    private var bootstrappedSession = false
    private var mutationJob: Job? = null
    private var sessionGeneration = 0L
    private var activeSessionMarker: String? = null
    private var pendingFilters: Pair<String, CabinFilters>? = null
    private val automaticReadPolicy = CustomerAutomaticReadPolicy()
    private var guestReadJob: Job? = null
    private var guestReadGeneration = 0L
    private var showLoginRequested = false

    /** Conditional signed-out/signed-in state. */
    val state: StateFlow<CustomerAppState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.state.collectLatest { auth ->
                val marker = authRepository.notificationSession()
                if (auth != CustomerAuthState.SignedIn || marker != activeSessionMarker) {
                    cancelGuestRead()
                    val registrationDraft = mutableWorkflow.value.registrationProfileDraft
                        .takeUnless { auth is CustomerAuthState.SignedOut }
                    sessionGeneration += 1
                    activeSessionMarker = null
                    pendingFilters = null
                    automaticReadPolicy.reset()
                    bootstrappedSession = false
                    mutableState.value = when (auth) {
                        is CustomerAuthState.SignedOut -> CustomerAppState.SignedOut(auth.message, showLogin = showLoginRequested)
                        CustomerAuthState.Authenticating -> CustomerAppState.SignedOut(submitting = true, showLogin = showLoginRequested)
                        else -> CustomerAppState.Loading
                    }
                    // Keep the old gate owned until its job has finished every cleanup path.
                    mutationJob?.cancelAndJoin()
                    mutationJob = null
                    mutableWorkflow.value = CustomerWorkflowState(registrationProfileDraft = registrationDraft)
                    activeSessionMarker = marker
                }
                when (auth) {
                    CustomerAuthState.Loading -> mutableState.value = CustomerAppState.Loading
                    CustomerAuthState.Authenticating -> mutableState.value = CustomerAppState.SignedOut(submitting = true, showLogin = showLoginRequested)
                    is CustomerAuthState.SignedOut -> {
                        runCatching { workflowStore.clear() }
                        bootstrappedSession = false
                        mutableWorkflow.value = CustomerWorkflowState()
                        // Public reads can start while the old protected workflow finishes clearing.
                        if (mutableState.value !is CustomerAppState.GuestCatalog) {
                            mutableState.value = CustomerAppState.SignedOut(auth.message, showLogin = showLoginRequested)
                        }
                    }
                    CustomerAuthState.SignedIn -> {
                        showLoginRequested = false
                        mutableState.value = CustomerAppState.Ready(mutableWorkflow.value)
                        if (!bootstrappedSession) {
                            bootstrap()
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            mutableWorkflow.collectLatest { workflow ->
                if (authRepository.state.value == CustomerAuthState.SignedIn &&
                    activeSessionMarker != null && activeSessionMarker == authRepository.notificationSession()
                ) {
                    mutableState.value = CustomerAppState.Ready(workflow)
                }
            }
        }
    }

    /** Opens public warehouse selection without authenticating or creating a rental inquiry. */
    fun continueAsGuest() {
        val signedOut = mutableState.value as? CustomerAppState.SignedOut ?: return
        if (signedOut.submitting || authRepository.state.value !is CustomerAuthState.SignedOut) return
        showLoginRequested = false
        loadGuestCatalog(CustomerGuestCatalogState()) { it.copy(warehouses = publicCatalog.warehouses()) }
    }

    /** A warehouse change replaces the whole public projection and fences the preceding request. */
    fun selectGuestWarehouse(warehouse: CustomerWarehouse) {
        val current = guestCatalog() ?: return
        val available = current.warehouses.firstOrNull { it.id == warehouse.id } ?: return
        readGuestFirstPage(CustomerGuestCatalogState(warehouses = current.warehouses, selectedWarehouse = available))
    }

    /** Applies only server catalog filters; no selection or hold is created. */
    fun applyGuestFilters(filters: CabinFilters) {
        val current = guestCatalog() ?: return
        if (current.selectedWarehouse == null) return
        readGuestFirstPage(current.copy(filters = filters, cabins = emptyList(), cabinPage = 0, cabinTotalPages = 0))
    }

    /** Appends the next server page only while it still belongs to this warehouse and filter request. */
    fun loadMoreGuestCabins() {
        val current = guestCatalog() ?: return
        val warehouse = current.selectedWarehouse ?: return
        if (current.busy || current.cabinPage + 1 >= current.cabinTotalPages) return
        loadGuestCatalog(current) { snapshot ->
            val page = publicCatalog.cabins(warehouse.id, snapshot.filters, (snapshot.cabinPage + 1).toInt())
            snapshot.copy(
                cabins = (snapshot.cabins + page.content).distinctBy(CustomerCabin::unitId),
                cabinPage = page.page,
                cabinTotalPages = page.totalPages,
            )
        }
    }

    /** Refreshes once on foreground/network return, without a retry loop or a local success fallback. */
    fun refreshGuestCatalog() {
        val current = guestCatalog() ?: return
        if (current.busy) return
        if (current.selectedWarehouse == null) {
            loadGuestCatalog(current) { it.copy(warehouses = publicCatalog.warehouses()) }
        } else {
            readGuestFirstPage(current)
        }
    }

    /** Discards the ephemeral guest projection when returning to entry. */
    fun leaveGuestCatalog() {
        if (guestCatalog() == null) return
        cancelGuestRead()
        showLoginRequested = false
        mutableState.value = CustomerAppState.SignedOut()
    }

    /** Ordering from a public card opens the real login form before any customer mutation is possible. */
    fun requestGuestLogin() {
        if (guestCatalog() == null) return
        cancelGuestRead()
        showLoginRequested = true
        mutableState.value = CustomerAppState.SignedOut(showLogin = true)
    }

    private fun guestCatalog(): CustomerGuestCatalogState? =
        (mutableState.value as? CustomerAppState.GuestCatalog)?.catalog
            ?.takeIf { authRepository.state.value is CustomerAuthState.SignedOut }

    private fun readGuestFirstPage(current: CustomerGuestCatalogState) {
        val warehouse = current.selectedWarehouse ?: return
        loadGuestCatalog(current) { snapshot ->
            val facets = publicCatalog.facets(warehouse.id)
            val page = publicCatalog.cabins(warehouse.id, snapshot.filters, 0)
            snapshot.copy(facets = facets, cabins = page.content, cabinPage = page.page, cabinTotalPages = page.totalPages)
        }
    }

    private fun cancelGuestRead() {
        guestReadGeneration += 1
        guestReadJob?.cancel()
        guestReadJob = null
    }

    private fun loadGuestCatalog(
        initial: CustomerGuestCatalogState,
        read: suspend (CustomerGuestCatalogState) -> CustomerGuestCatalogState,
    ) {
        cancelGuestRead()
        val generation = guestReadGeneration
        mutableState.value = CustomerAppState.GuestCatalog(initial.copy(busy = true, error = null))
        guestReadJob = viewModelScope.launch {
            try {
                val result = read(initial)
                ensureActive()
                if (generation == guestReadGeneration && guestCatalog() != null) {
                    mutableState.value = CustomerAppState.GuestCatalog(result.copy(busy = false, error = null))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == guestReadGeneration && guestCatalog() != null) {
                    mutableState.value = CustomerAppState.GuestCatalog(initial.copy(
                        busy = false,
                        error = (failure as? CustomerApiException)?.message ?: "Не удалось загрузить каталог.",
                    ))
                }
            }
        }
    }

    /** Starts the PKCE customer login with the explicit session persistence choice. */
    fun login(username: String, password: String, rememberMe: Boolean) {
        mutableWorkflow.value = mutableWorkflow.value.copy(registrationProfileDraft = null)
        viewModelScope.launch { authRepository.login(username, password, rememberMe) }
    }

    /** Registers an individual account and carries its contact values into profile completion. */
    fun register(
        username: String,
        email: String,
        password: String,
        confirmation: String,
        phone: String,
    ) {
        mutableWorkflow.value = mutableWorkflow.value.copy(
            registrationProfileDraft = CustomerRegistrationProfileDraft(
                email = email.trim(),
                phone = phone.trim(),
            ),
        )
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
        mutableWorkflow.value = mutableWorkflow.value.copy(
            profile = saved,
            registrationProfileDraft = null,
            warehouses = warehouses,
        )
    }

    /**
     * Uploads and binds a locally cropped JPEG through an existing profile media scope. Catalog selection is
     * optional: the immutable avatar scope or an authorized warehouse supplies the required owner
     * warehouse when the customer opens the profile directly.
     */
    fun uploadProfileAvatar(jpegBytes: ByteArray) = launchMutation {
        val current = mutableWorkflow.value
        val profile = current.profile
            ?: throw CustomerApiException(409, "Сначала сохраните профиль")
        val warehouses = current.warehouses.ifEmpty {
            withContext(Dispatchers.IO) { repository.warehouses() }
        }
        val warehouseId = current.copy(warehouses = warehouses).avatarUploadWarehouseId()
            ?: throw CustomerApiException(409, "Для загрузки аватара пока нет доступного склада")
        val updated = withContext(Dispatchers.IO) {
            repository.uploadProfileAvatar(profile, warehouseId, jpegBytes)
        }
        mutableWorkflow.value = mutableWorkflow.value.copy(profile = updated, warehouses = warehouses)
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

    /** Keeps the latest filter intent until the current request releases the shared command lane. */
    fun applyFilters(filters: CabinFilters) {
        val current = mutableWorkflow.value
        val inquiryId = current.inquiryId ?: return
        if (current.bootstrapping || authRepository.state.value != CustomerAuthState.SignedIn) return
        pendingFilters = inquiryId to filters
        drainPendingFilters()
    }

    private fun drainPendingFilters() {
        if (mutationGate.isActive()) return
        val request = pendingFilters ?: return
        val current = mutableWorkflow.value
        if (current.bootstrapping || current.inquiryId != request.first ||
            authRepository.state.value != CustomerAuthState.SignedIn
        ) {
            pendingFilters = null
            return
        }
        val marker = activeSessionMarker ?: return
        val generation = sessionGeneration
        launchMutation(onAdmitted = { pendingFilters = null }) {
            val (inquiryId, filters) = request
            val page = repository.cabins(inquiryId, filters, 0)
            if (!isCurrentMutationSession(generation, marker) ||
                mutableWorkflow.value.inquiryId != inquiryId || pendingFilters != null
            ) return@launchMutation
            mutableWorkflow.value = mutableWorkflow.value.copy(
                filters = filters,
                cabins = page.content,
                cabinPage = page.page,
                cabinTotalPages = page.totalPages,
                estimatedDeliveryDates = page.estimatedDeliveryDates,
            )
        }
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

    /** Applies a duration override to exactly one selected cabin. */
    fun setCabinRentalMonths(cabinUnitId: String, months: Long) = launchMutation {
        require(months in 1..120)
        val current = mutableWorkflow.value
        require(cabinUnitId in current.selectedCabinIds)
        val next = CustomerRentalTermPolicy.withCabinTerm(
            selectedCabins = current.selectedCabinIds,
            existingTerms = current.rentalTerms,
            cabinUnitId = cabinUnitId,
            months = months,
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
        // Background order reads must not drop text edits or leave a stale confirmed address.
        if (mutableWorkflow.value.busy || mutableWorkflow.value.bootstrapping) return
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
        refreshCustomerUpdatesOwned()
    }

    /** Foreground/connectivity restoration allows new reads, never another customer command. */
    fun resumeAutomaticUpdates() {
        val marker = activeSessionMarker ?: return
        if (authRepository.state.value != CustomerAuthState.SignedIn || authRepository.notificationSession() != marker) return
        automaticReadPolicy.reset()
        mutableWorkflow.value = mutableWorkflow.value.copy(automaticUpdatesPaused = false)
    }

    /** Admits bounded foreground reads; a busy command lane does not consume a failed attempt. */
    fun refreshCustomerUpdates() {
        if (mutableWorkflow.value.bootstrapping || mutableWorkflow.value.profile == null ||
            !automaticReadPolicy.canAttempt(SystemClock.elapsedRealtime())
        ) return
        val marker = activeSessionMarker ?: return
        val generation = sessionGeneration
        launchMutation(showBusy = false) {
            val outcome = refreshCustomerUpdatesOwned()
            if (!isCurrentMutationSession(generation, marker)) return@launchMutation
            automaticReadPolicy.record(outcome, SystemClock.elapsedRealtime())
            mutableWorkflow.value = mutableWorkflow.value.copy(automaticUpdatesPaused = automaticReadPolicy.paused)
        }
    }

    /** Independent exact-quote reads still recover an accepted effect when the booking list is unavailable. */
    private suspend fun refreshCustomerUpdatesOwned(
        changeToReveal: Pair<String, String>? = null,
    ): CustomerReadOutcome {
        val marker = activeSessionMarker ?: return CustomerReadOutcome.SKIPPED
        val generation = sessionGeneration
        if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
        val initial = mutableWorkflow.value
        val observedChange = changeToReveal ?: initial.bookingChangeQuote?.takeIf { quote ->
            quote.applicationState != BookingChangeApplicationState.APPLIED &&
                (initial.bookingChangeDialogVisible || initial.bookingChangeNeedsRefresh ||
                    quote.applicationState == BookingChangeApplicationState.APPLYING)
        }?.let { it.bookingId to it.quoteId }
            ?: initial.bookingChangeReferences.entries.firstOrNull()?.takeIf { initial.bookingChangeQuote == null }
                ?.let { it.key to it.value }
        var bookingsReloaded = false
        var readError: String? = null
        try {
            val refreshed = repository.bookings()
            if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
            reconcileBookings(refreshed)
            bookingsReloaded = true
            val current = mutableWorkflow.value
            val payments = current.payments.toMutableMap()
            val errors = current.paymentErrors.toMutableMap()
            CustomerBookingPolicy.visible(current.booking, refreshed).forEach { booking ->
                val bookingId = booking.bookingId ?: return@forEach
                if (booking.orderId == null) return@forEach
                val previous = payments[bookingId]?.payment
                val terminalUnchanged = previous?.state in setOf("EXPIRED", "CANCELLED") ||
                    (previous?.state == "CONFIRMED" && booking.status != "CANCELLED")
                if (terminalUnchanged && bookingId !in errors) return@forEach
                try {
                    val started = SystemClock.elapsedRealtime()
                    val payment = repository.payment(booking)
                    if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
                    val observed = CustomerObservedPayment(payment, started)
                    payments[bookingId] = observed
                    errors.remove(bookingId)
                    if (payment.state in setOf("PENDING", "EXPIRING")) {
                        notifications.schedule(payment.orderId, observed.remainingMillis(SystemClock.elapsedRealtime()))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val message = (failure as? CustomerApiException)?.message ?: "Не удалось загрузить счёт"
                    errors[bookingId] = message
                    readError = readError ?: message
                }
            }
            if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
            mutableWorkflow.value = mutableWorkflow.value.copy(payments = payments, paymentErrors = errors)
            val inbox = notifications.fetchAndPublish()
            if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
            mutableWorkflow.value = mutableWorkflow.value.copy(notifications = inbox)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            readError = (failure as? CustomerApiException)?.message ?: "Не удалось обновить заказы и уведомления"
        }
        if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
        if (observedChange != null) {
            val (bookingId, quoteId) = observedChange
            try {
                val quote = repository.bookingChangeQuote(bookingId, quoteId)
                if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
                val previous = mutableWorkflow.value.bookingChangeQuote?.takeIf { it.quoteId == quoteId }
                if (previous != null && quote.version < previous.version) {
                    throw CustomerApiException(502, "Не удалось получить актуальные условия изменения заказа")
                }
                mutableWorkflow.value = mutableWorkflow.value.withObservedBookingChange(
                    quote = quote,
                    bookingsReloaded = bookingsReloaded,
                    now = Instant.now(),
                    reveal = changeToReveal != null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
                val message = (failure as? CustomerApiException)?.message ?: "Не удалось проверить результат изменения заказа"
                readError = readError ?: message
                if (mutableWorkflow.value.bookingChangeQuote?.quoteId == quoteId) {
                    mutableWorkflow.value = mutableWorkflow.value.copy(
                        bookingChangeNeedsRefresh = mutableWorkflow.value.bookingChangeQuote?.applicationState !=
                            BookingChangeApplicationState.APPLIED,
                        bookingChangeReadError = message,
                    )
                }
            }
        }
        if (!isCurrentMutationSession(generation, marker)) return CustomerReadOutcome.SKIPPED
        mutableWorkflow.value = mutableWorkflow.value.copy(updatesError = readError)
        return if (readError == null) CustomerReadOutcome.SUCCESS else CustomerReadOutcome.FAILED
    }

    /** Explicit test payment uses the frozen displayed receipt/version and then reloads server facts. */
    fun confirmInitialPayment(bookingId: String) = launchMutation {
        val current = mutableWorkflow.value
        val booking = CustomerBookingPolicy.visible(current.booking, current.bookings)
            .firstOrNull { it.bookingId == bookingId }
            ?: throw CustomerApiException(409, "Данные заказа пока недоступны")
        val observed = current.payments[bookingId] ?: throw CustomerApiException(409, "Счёт пока недоступен")
        if (current.paymentErrors[bookingId] != null || observed.remainingMillis(SystemClock.elapsedRealtime()) <= 0) {
            throw CustomerApiException(409, "Срок оплаты истёк или счёт недоступен")
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val payment = repository.confirmTestPayment(booking, observed.payment)
            mutableWorkflow.value = mutableWorkflow.value.copy(
                payments = mutableWorkflow.value.payments + (bookingId to CustomerObservedPayment(payment, started)),
            )
        } finally {
            // A lost response or conflict is followed by a read, never automatic consent to a new bill.
            mutableWorkflow.value = mutableWorkflow.value.copy(
                paymentErrors = mutableWorkflow.value.paymentErrors + (bookingId to "Проверяем результат оплаты…"),
            )
            refreshCustomerUpdatesOwned()
        }
    }

    /** Read state is changed only by the user's explicit inbox action. */
    fun readNotification(id: String) = launchMutation {
        val result = repository.readNotification(id)
        if (result.id != id || result.readAt == null) throw CustomerApiException(503, "Уведомление не подтверждено")
        notifications.acknowledged(id)
        mutableWorkflow.value = mutableWorkflow.value.copy(notifications = mutableWorkflow.value.notifications.filterNot { it.id == id })
        refreshCustomerUpdatesOwned()
    }

    /** Requests server terms before allowing cancellation or consent to a test charge. */
    fun cancelBooking(bookingId: String) = launchMutation {
        if (showExistingBookingChange(bookingId)) return@launchMutation
        val booking = requireChangeableBooking(bookingId)
        val quote = repository.createBookingChangeQuote(booking, BookingChangeOperation.CANCEL)
        showBookingChangeQuote(quote)
    }

    /** Loads replacement slots derived solely from the server-owned booking contents. */
    fun openBookingReschedule(bookingId: String) = launchMutation {
        if (showExistingBookingChange(bookingId)) return@launchMutation
        val booking = requireChangeableBooking(bookingId)
        val slots = repository.searchBookingRescheduleSlots(booking)
        mutableWorkflow.value = mutableWorkflow.value.copy(
            bookingRescheduleId = bookingId,
            bookingRescheduleVersion = booking.version,
            bookingRescheduleSourceSlotId = booking.slotId,
            bookingRescheduleSlots = CustomerBookingLifecyclePolicy.orderedSlots(slots),
            selectedBookingRescheduleSlotId = null,
        )
    }

    /** Selects one exact replacement offer returned by the booking-scoped search. */
    fun selectBookingRescheduleSlot(slotId: String) {
        if (mutationGate.isActive()) return
        val current = mutableWorkflow.value
        if (current.bookingRescheduleSlots.none { slot -> slot.slotId == slotId }) return
        mutableWorkflow.value = current.copy(selectedBookingRescheduleSlotId = slotId)
    }

    /** Closes the local offer dialog without changing the booking or its original slot. */
    fun dismissBookingReschedule() {
        if (mutationGate.isActive()) return
        mutableWorkflow.value = mutableWorkflow.value.copy(
            bookingRescheduleId = null,
            bookingRescheduleVersion = null,
            bookingRescheduleSourceSlotId = null,
            bookingRescheduleSlots = emptyList(),
            selectedBookingRescheduleSlotId = null,
        )
    }

    /** Quotes the selected replacement offer; this does not move the booking or settle a charge. */
    fun confirmBookingReschedule() = launchMutation {
        val current = mutableWorkflow.value
        val bookingId = current.bookingRescheduleId
            ?: throw CustomerApiException(409, "Сначала выберите заказ для переноса")
        val booking = requireChangeableBooking(bookingId)
        val slotId = current.selectedBookingRescheduleSlotId
            ?: throw CustomerApiException(422, "Выберите новое время доставки")
        val slot = current.bookingRescheduleSlots.singleOrNull { offer -> offer.slotId == slotId }
            ?: throw CustomerApiException(409, "Выбранное время устарело. Рассчитайте варианты заново")
        val quote = repository.createBookingChangeQuote(booking, BookingChangeOperation.RESCHEDULE, slot)
        showBookingChangeQuote(quote)
    }

    /** Atomically applies the quoted change; TEST_PAID is shown only from the subsequent exact GET. */
    fun applyBookingChange(testPaymentRequested: Boolean) = launchMutation {
        val current = mutableWorkflow.value
        val quote = current.bookingChangeQuote
            ?: throw CustomerApiException(409, "Сначала рассчитайте условия изменения")
        if (current.bookingChangeNeedsRefresh || quote.applicationState != BookingChangeApplicationState.OFFERED) {
            throw CustomerApiException(409, "Ожидаем подтверждение изменения от сервиса")
        }
        current.bookingChangeUnavailableReason?.let { throw CustomerApiException(409, it) }
        val paymentRequired = quote.settlement == BookingChangeSettlement.PAYMENT_REQUIRED
        if (quote.settlement == BookingChangeSettlement.POLICY_UNCONFIGURED ||
            testPaymentRequested != paymentRequired || (paymentRequired && !quote.testPaymentAvailable)
        ) {
            throw CustomerApiException(409, "Изменение пока недоступно. Свяжитесь с менеджером.")
        }
        mutableWorkflow.value = mutableWorkflow.value.copy(bookingChangeNeedsRefresh = true)
        val booking = requireChangeableBooking(quote.bookingId)
        val result = when (quote.operation) {
            BookingChangeOperation.CANCEL -> repository.cancelBooking(booking, quote, testPaymentRequested)
            BookingChangeOperation.RESCHEDULE -> repository.rescheduleBooking(booking, quote, testPaymentRequested)
        }
        showBookingChangeQuote(result.quote)
        result.booking?.let { updated ->
            if (quote.operation == BookingChangeOperation.RESCHEDULE) {
                mutableWorkflow.value = mutableWorkflow.value.withSuccessfulBookingReschedule(updated)
            } else {
                applyBookingResponse(updated)
            }
        }
        reconcileBookings(repository.bookings())
    }

    /** Opens exact change details with GETs only; new terms require an explicit change action. */
    fun refreshBookingChange(bookingId: String? = null) = launchMutation {
        val current = mutableWorkflow.value
        val id = bookingId ?: current.bookingChangeQuote?.bookingId ?: return@launchMutation
        val quoteId = current.bookingChangeReferences[id] ?: return@launchMutation
        refreshCustomerUpdatesOwned(changeToReveal = id to quoteId)
    }

    /** Hides pending owner work without discarding its exact reference or implying a reversal. */
    fun dismissBookingChange() = launchMutation {
        val current = mutableWorkflow.value
        val keepReference = current.bookingChangeNeedsRefresh ||
            current.bookingChangeQuote?.applicationState == BookingChangeApplicationState.APPLYING ||
            (current.bookingChangeQuote?.applicationState == BookingChangeApplicationState.OFFERED &&
                current.bookingChangeQuote.settlement == BookingChangeSettlement.WAIVED)
        val discardOffers = keepReference ||
            current.bookingChangeQuote?.applicationState == BookingChangeApplicationState.APPLIED
        if (!keepReference) current.bookingChangeQuote?.let { workflowStore.forgetBookingChange(it.quoteId) }
        mutableWorkflow.value = current.copy(
            bookingChangeQuote = current.bookingChangeQuote.takeIf { keepReference },
            bookingChangeDialogVisible = false,
            error = null,
            bookingChangeReferences = workflowStore.bookingChangeReferences().associate { it.bookingId to it.quoteId },
            bookingRescheduleId = if (discardOffers) null else current.bookingRescheduleId,
            bookingRescheduleVersion = if (discardOffers) null else current.bookingRescheduleVersion,
            bookingRescheduleSourceSlotId = if (discardOffers) null else current.bookingRescheduleSourceSlotId,
            bookingRescheduleSlots = if (discardOffers) emptyList() else current.bookingRescheduleSlots,
            selectedBookingRescheduleSlotId = if (discardOffers) null else current.selectedBookingRescheduleSlotId,
        )
    }

    /** Reports a missing dialer as presentation failure, never as an attempted phone call. */
    fun reportBookingChangeContactError(message: String) {
        mutableWorkflow.value = mutableWorkflow.value.copy(error = message)
    }

    private fun showBookingChangeQuote(quote: CustomerBookingChangeQuote) {
        mutableWorkflow.value = mutableWorkflow.value.copy(
            bookingChangeQuote = quote,
            bookingChangeDialogVisible = true,
            bookingChangeNeedsRefresh = false,
            bookingChangeReadError = null,
            bookingChangeUnavailableReason = if (quote.applicationState == BookingChangeApplicationState.OFFERED &&
                quote.settlement == BookingChangeSettlement.WAIVED && !Instant.now().isBefore(Instant.parse(quote.expiresAt))
            ) "Выбранное время устарело. Свяжитесь с менеджером, чтобы сохранить освобождение от неустойки."
            else null,
            bookingChangeReferences = mutableWorkflow.value.bookingChangeReferences + (quote.bookingId to quote.quoteId),
        )
    }

    private suspend fun showExistingBookingChange(bookingId: String): Boolean {
        val quoteId = mutableWorkflow.value.bookingChangeReferences[bookingId] ?: return false
        val quote = repository.bookingChangeQuote(bookingId, quoteId)
        showBookingChangeQuote(quote)
        reconcileBookings(repository.bookings())
        presentRecoveredBookingChange(quote)
        return true
    }

    private suspend fun presentRecoveredBookingChange(quote: CustomerBookingChangeQuote) {
        if (quote.applicationState != BookingChangeApplicationState.OFFERED) return
        val current = mutableWorkflow.value
        val booking = CustomerBookingPolicy.visible(current.booking, current.bookings)
            .firstOrNull { it.bookingId == quote.bookingId }
        val sourceUnchanged = booking != null && CustomerBookingLifecyclePolicy.canChange(booking) &&
            booking.version == quote.bookingVersion && booking.slotId == quote.oldSlotId
        if (quote.settlement == BookingChangeSettlement.WAIVED) {
            if (!sourceUnchanged) {
                mutableWorkflow.value = current.copy(
                    bookingChangeUnavailableReason = "Заказ изменился. Свяжитесь с менеджером, чтобы сохранить освобождение от неустойки.",
                )
            }
            return
        }
        if (sourceUnchanged && !quote.requiresFreshBookingChangeTerms(Instant.now())) return
        // An exact OFFERED response proves there is no accepted effect to preserve or retry.
        workflowStore.forgetBookingChange(quote.quoteId)
        mutableWorkflow.value = current.copy(
            bookingChangeQuote = null,
            bookingChangeDialogVisible = false,
            bookingChangeNeedsRefresh = false,
            bookingChangeReferences = workflowStore.bookingChangeReferences().associate { it.bookingId to it.quoteId },
        )
        val editable = requireChangeableBooking(quote.bookingId)
        if (quote.operation == BookingChangeOperation.CANCEL) {
            showBookingChangeQuote(repository.createBookingChangeQuote(editable, BookingChangeOperation.CANCEL))
        } else {
            val slots = repository.searchBookingRescheduleSlots(editable)
            mutableWorkflow.value = mutableWorkflow.value.copy(
                bookingRescheduleId = quote.bookingId,
                bookingRescheduleVersion = editable.version,
                bookingRescheduleSourceSlotId = editable.slotId,
                bookingRescheduleSlots = CustomerBookingLifecyclePolicy.orderedSlots(slots),
                selectedBookingRescheduleSlotId = null,
            )
        }
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

    private fun bootstrap() = launchMutation(showBusy = false, onAdmitted = { bootstrappedSession = true }) {
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
        val changeReferences = workflowStore.bookingChangeReferences()
        mutableWorkflow.value = mutableWorkflow.value.copy(
            bookingChangeReferences = changeReferences.associate { it.bookingId to it.quoteId },
        )
        refreshCustomerUpdatesOwned(
            changeToReveal = changeReferences.firstOrNull()?.let { it.bookingId to it.quoteId },
        )
    }

    private fun launchMutation(
        showBusy: Boolean = true,
        onAdmitted: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        val marker = activeSessionMarker ?: return
        if (authRepository.state.value != CustomerAuthState.SignedIn ||
            authRepository.notificationSession() != marker
        ) return
        if (!mutationGate.tryEnter()) return
        val generation = sessionGeneration
        onAdmitted()
        // Enter try/finally before exposing the job to session cancellation, even before dispatch.
        mutationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (showBusy) mutableWorkflow.value = mutableWorkflow.value.copy(busy = true, error = null)
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CustomerApiException) {
                if (!isCurrentMutationSession(generation, marker)) return@launch
                if (CustomerInquiryRecoveryPolicy.isArchivedInquiry(failure)) {
                    mutableWorkflow.value = mutableWorkflow.value.copy(error = recoverArchivedWorkflow())
                } else {
                    if (failure.status == 401) authRepository.invalidate(failure.message)
                    val quote = mutableWorkflow.value.bookingChangeQuote
                    val recoveredQuote = if (quote != null && mutableWorkflow.value.bookingChangeNeedsRefresh) {
                        try {
                            repository.bookingChangeQuote(quote.bookingId, quote.quoteId).also(::showBookingChangeQuote)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    } else null
                    val acceptedChange = recoveredQuote != null &&
                        recoveredQuote.applicationState != BookingChangeApplicationState.OFFERED
                    val bookingReloaded = if (failure.status == 409) reloadAfterConflict() else false
                    val recoveredWaiver = mutableWorkflow.value.canConfirmRecoveredBookingChangeWaiver(
                        previous = quote,
                        recovered = recoveredQuote,
                        failureCode = failure.code,
                        bookingReloaded = bookingReloaded,
                        now = Instant.now(),
                    )
                    val staleChange = failure.code in setOf(
                        "CUSTOMER_CHANGE_QUOTE_STALE", "CUSTOMER_BOOKING_VERSION_CONFLICT",
                        "CUSTOMER_DELIVERY_SLOT_EXPIRED", "CUSTOMER_DELIVERY_SLOT_NOT_FOUND", "CUSTOMER_DELIVERY_SLOT_TAKEN",
                    )
                    if (staleChange && mutableWorkflow.value.bookingChangeQuote?.settlement == BookingChangeSettlement.WAIVED) {
                        mutableWorkflow.value = mutableWorkflow.value.copy(
                            bookingChangeUnavailableReason = if (recoveredWaiver) null
                            else "Условия изменились. Свяжитесь с менеджером, чтобы сохранить освобождение от неустойки.",
                        )
                    } else if (staleChange && !mutableWorkflow.value.bookingChangeNeedsRefresh && !acceptedChange
                    ) {
                        mutableWorkflow.value.bookingChangeQuote?.let { workflowStore.forgetBookingChange(it.quoteId) }
                        mutableWorkflow.value = mutableWorkflow.value.copy(
                            bookingChangeQuote = null,
                            bookingChangeDialogVisible = false,
                            bookingChangeNeedsRefresh = false,
                            bookingRescheduleId = null,
                            bookingRescheduleVersion = null,
                            bookingRescheduleSourceSlotId = null,
                            bookingRescheduleSlots = emptyList(),
                            selectedBookingRescheduleSlotId = null,
                            bookingChangeReferences = workflowStore.bookingChangeReferences().associate { it.bookingId to it.quoteId },
                        )
                    }
                    mutableWorkflow.value = mutableWorkflow.value.copy(error = failure.message.takeUnless { acceptedChange || recoveredWaiver })
                }
            } catch (_: Throwable) {
                if (!isCurrentMutationSession(generation, marker)) return@launch
                mutableWorkflow.value = mutableWorkflow.value.copy(error = "Не удалось выполнить действие")
            } finally {
                if (isCurrentMutationSession(generation, marker)) {
                    mutableWorkflow.value = mutableWorkflow.value.copy(busy = false, bootstrapping = false)
                    if (showBusy) resumeAutomaticUpdates()
                }
                mutationGate.leave()
                if (pendingFilters != null && isCurrentMutationSession(generation, marker)) {
                    viewModelScope.launch {
                        // Let the current undispatched launch finish assigning its tracked job first.
                        kotlinx.coroutines.yield()
                        drainPendingFilters()
                    }
                }
            }
        }
    }

    private fun isCurrentMutationSession(generation: Long, marker: String): Boolean =
        sessionGeneration == generation && activeSessionMarker == marker &&
            authRepository.notificationSession() == marker

    private suspend fun reloadAfterConflict(): Boolean {
        val bookings = runCatching { repository.bookings() }.getOrNull()
        bookings?.let(::reconcileBookings)
        val inquiryId = mutableWorkflow.value.inquiryId ?: return bookings != null
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
        return bookings != null
    }

    private fun requireChangeableBooking(bookingId: String): CustomerBooking {
        val booking = CustomerBookingPolicy.visible(
            mutableWorkflow.value.booking,
            mutableWorkflow.value.bookings,
        ).firstOrNull { candidate -> candidate.bookingId == bookingId }
            ?: throw CustomerApiException(404, "Заказ не найден")
        if (!CustomerBookingLifecyclePolicy.canChange(booking)) {
            throw CustomerApiException(
                409,
                "Заказ уже передан в работу. Отменить или перенести доставку больше нельзя.",
                "CUSTOMER_BOOKING_NOT_EDITABLE",
            )
        }
        return booking
    }

    private fun applyBookingResponse(updated: CustomerBooking) {
        val current = mutableWorkflow.value
        val latest = current.booking?.let { booking ->
            if (booking.bookingId == updated.bookingId || booking.inquiryId == updated.inquiryId) {
                updated
            } else {
                booking
            }
        }
        mutableWorkflow.value = current.copy(
            booking = latest,
            bookings = CustomerBookingPolicy.replace(updated, current.bookings),
        )
    }

    private fun reconcileBookings(bookings: List<CustomerBooking>) {
        mutableWorkflow.value = mutableWorkflow.value.withReconciledBookings(bookings)
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
            estimatedDeliveryDates = emptyList(),
            filters = CabinFilters(),
            selectedCabinIds = emptySet(),
            equipment = emptyList(),
            equipmentDraft = emptyMap(),
            rentalTerms = emptyMap(),
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
            estimatedDeliveryDates = page.estimatedDeliveryDates,
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
            throw CustomerApiException(409, "Оформление уже выполняется. Дождитесь результата")
        }
    }
}

private fun validateProfile(profile: CustomerProfile): String? = when {
    profile.phone.isBlank() -> "Укажите номер телефона"
    profile.entityType == CustomerEntityType.INDIVIDUAL && profile.firstName.isNullOrBlank() -> "Укажите имя"
    profile.entityType == CustomerEntityType.INDIVIDUAL && profile.lastName.isNullOrBlank() -> "Укажите фамилию"
    profile.entityType == CustomerEntityType.LEGAL && profile.companyName.isNullOrBlank() -> "Укажите компанию"
    else -> null
}

private fun Double.roundedCoordinate(): Double = round(this * 1_000_000.0) / 1_000_000.0
