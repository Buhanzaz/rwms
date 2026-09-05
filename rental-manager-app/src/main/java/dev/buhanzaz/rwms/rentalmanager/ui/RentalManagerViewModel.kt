package dev.buhanzaz.rwms.rentalmanager.ui

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.buhanzaz.rwms.rentalmanager.auth.RentalManagerAuthState
import dev.buhanzaz.rwms.rentalmanager.data.RentalManagerRepository
import dev.buhanzaz.rwms.rentalmanager.data.RentalManagerSession
import dev.buhanzaz.rwms.rentalmanager.network.CreateOrderRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateRentalClientRequest
import dev.buhanzaz.rwms.rentalmanager.network.OrderDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerBackend
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class RentalManagerPhase {
    LOADING,
    SIGNED_OUT,
    AUTHENTICATING,
    CONNECTING,
    READY,
}

data class RentalManagerNotice(
    val message: String,
    val isError: Boolean,
)

/** Server evidence plus a process-local monotonic timer anchor, never persisted as payment state. */
data class ObservedOrderPayment(
    val payment: OrderPaymentDto,
    val observedElapsedRealtimeMillis: Long,
)

data class RentalManagerUiState(
    val phase: RentalManagerPhase = RentalManagerPhase.LOADING,
    val authMessage: String? = null,
    val session: RentalManagerSession? = null,
    val clientSearch: String = "",
    val clients: List<RentalClientDto> = emptyList(),
    val clientsLoading: Boolean = false,
    val clientsPage: Int = 0,
    val clientsHasMore: Boolean = false,
    val orderSearch: String = "",
    val orders: List<OrderDto> = emptyList(),
    val ordersLoading: Boolean = false,
    val ordersPage: Int = 0,
    val ordersHasMore: Boolean = false,
    val selectedClientId: String? = null,
    val selectedClient: RentalClientDto? = null,
    val selectedClientOrders: List<OrderDto> = emptyList(),
    val selectedClientOrdersPage: Int = 0,
    val selectedClientOrdersHasMore: Boolean = false,
    val selectedClientLoading: Boolean = false,
    val selectedOrderId: String? = null,
    val selectedOrder: OrderDto? = null,
    val selectedOrderPayment: ObservedOrderPayment? = null,
    val selectedOrderLoading: Boolean = false,
    val commandRunning: Boolean = false,
    val notice: RentalManagerNotice? = null,
)

sealed interface RentalManagerNavigationEvent {
    data class OpenClient(val clientId: String) : RentalManagerNavigationEvent
    data class OpenOrder(val orderId: String) : RentalManagerNavigationEvent
    data object OpenOrders : RentalManagerNavigationEvent
}

/**
 * Coordinates only Android presentation state. Authorization and order transitions remain owned by
 * auth-service and logistics-service; every retriable command keeps its idempotency key in saved state.
 */
class RentalManagerViewModel(
    private val backend: RentalManagerBackend,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val repository = RentalManagerRepository(backend)
    private val mutableState = MutableStateFlow(RentalManagerUiState())
    private val mutableEvents = MutableSharedFlow<RentalManagerNavigationEvent>(extraBufferCapacity = 1)
    private var sessionJob: Job? = null
    private var clientSearchJob: Job? = null
    private var orderSearchJob: Job? = null
    private var clientDetailJob: Job? = null
    private var orderDetailJob: Job? = null

    val state = mutableState.asStateFlow()
    val events: SharedFlow<RentalManagerNavigationEvent> = mutableEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            backend.auth.state.collectLatest(::handleAuthState)
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch { backend.auth.login(username, password) }
    }

    fun logout() {
        viewModelScope.launch {
            mutableState.update { it.copy(phase = RentalManagerPhase.CONNECTING, authMessage = null) }
            backend.auth.logout()
        }
    }

    fun retrySession() {
        loadSession(force = true)
    }

    fun dismissNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    fun searchClients(search: String) {
        clientSearchJob?.cancel()
        val normalized = search.trim()
        mutableState.update { it.copy(clientSearch = search, clientsLoading = true, notice = null) }
        clientSearchJob = viewModelScope.launch {
            runCatching { repository.clients(normalized, page = 0) }
                .onSuccess { page ->
                    if (mutableState.value.clientSearch.trim() == normalized) {
                        mutableState.update {
                            it.copy(
                                clients = page.content,
                                clientsLoading = false,
                                clientsPage = page.page.toInt(),
                                clientsHasMore = page.page + 1 < page.totalPages,
                            )
                        }
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (mutableState.value.clientSearch.trim() == normalized) {
                        mutableState.update {
                            it.copy(
                                clientsLoading = false,
                                notice = RentalManagerNotice(handleFailure(failure), true),
                            )
                        }
                    }
                }
        }
    }

    fun loadMoreClients() {
        val current = mutableState.value
        if (current.clientsLoading || !current.clientsHasMore) return
        val normalized = current.clientSearch.trim()
        val nextPage = current.clientsPage + 1
        mutableState.update { it.copy(clientsLoading = true, notice = null) }
        clientSearchJob?.cancel()
        clientSearchJob = viewModelScope.launch {
            runCatching { repository.clients(normalized, page = nextPage) }
                .onSuccess { page ->
                    if (mutableState.value.clientSearch.trim() == normalized) {
                        mutableState.update {
                            it.copy(
                                clients = (it.clients + page.content).distinctBy(RentalClientDto::id),
                                clientsLoading = false,
                                clientsPage = page.page.toInt(),
                                clientsHasMore = page.page + 1 < page.totalPages,
                            )
                        }
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (mutableState.value.clientSearch.trim() == normalized) {
                        mutableState.update {
                            it.copy(
                                clientsLoading = false,
                                notice = RentalManagerNotice(handleFailure(failure), true),
                            )
                        }
                    }
                }
        }
    }

    fun searchOrders(search: String) {
        orderSearchJob?.cancel()
        val normalized = search.trim()
        mutableState.update { it.copy(orderSearch = search, ordersLoading = true, notice = null) }
        orderSearchJob = viewModelScope.launch {
            runCatching { repository.orders(normalized, page = 0) }
                .onSuccess { page ->
                    if (mutableState.value.orderSearch.trim() == normalized) {
                        mutableState.update {
                            it.copy(
                                orders = page.content,
                                ordersLoading = false,
                                ordersPage = page.page.toInt(),
                                ordersHasMore = page.page + 1 < page.totalPages,
                            )
                        }
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (mutableState.value.orderSearch.trim() == normalized) {
                        mutableState.update {
                            it.copy(
                                ordersLoading = false,
                                notice = RentalManagerNotice(handleFailure(failure), true),
                            )
                        }
                    }
                }
        }
    }

    fun loadMoreOrders() {
        val current = mutableState.value
        if (current.ordersLoading || !current.ordersHasMore) return
        val normalized = current.orderSearch.trim()
        val nextPage = current.ordersPage + 1
        mutableState.update { it.copy(ordersLoading = true, notice = null) }
        orderSearchJob?.cancel()
        orderSearchJob = viewModelScope.launch {
            runCatching { repository.orders(normalized, page = nextPage) }
                .onSuccess { page ->
                    if (mutableState.value.orderSearch.trim() == normalized) {
                        mutableState.update {
                            it.copy(
                                orders = (it.orders + page.content).distinctBy(OrderDto::id),
                                ordersLoading = false,
                                ordersPage = page.page.toInt(),
                                ordersHasMore = page.page + 1 < page.totalPages,
                            )
                        }
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (mutableState.value.orderSearch.trim() == normalized) {
                        mutableState.update {
                            it.copy(
                                ordersLoading = false,
                                notice = RentalManagerNotice(handleFailure(failure), true),
                            )
                        }
                    }
                }
        }
    }

    fun openClient(clientId: String) {
        clientDetailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedClientId = clientId,
                selectedClient = null,
                selectedClientOrders = emptyList(),
                selectedClientOrdersPage = 0,
                selectedClientOrdersHasMore = false,
                selectedClientLoading = true,
                notice = null,
            )
        }
        clientDetailJob = viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val client = async { repository.client(clientId) }
                    val orders = async { repository.clientOrders(clientId) }
                    client.await() to orders.await()
                }
            }.onSuccess { (client, ordersPage) ->
                if (mutableState.value.selectedClientId == clientId) {
                    mutableState.update {
                        it.copy(
                            selectedClient = client,
                            selectedClientOrders = ordersPage.content,
                            selectedClientOrdersPage = ordersPage.page.toInt(),
                            selectedClientOrdersHasMore =
                                ordersPage.page + 1 < ordersPage.totalPages,
                            selectedClientLoading = false,
                        )
                    }
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (mutableState.value.selectedClientId == clientId) {
                    mutableState.update {
                        it.copy(
                            selectedClientLoading = false,
                            notice = RentalManagerNotice(handleFailure(failure), true),
                        )
                    }
                }
            }
        }
    }

    fun loadMoreClientOrders() {
        val current = mutableState.value
        val clientId = current.selectedClientId ?: return
        if (current.selectedClientLoading || !current.selectedClientOrdersHasMore) return
        val nextPage = current.selectedClientOrdersPage + 1
        mutableState.update { it.copy(selectedClientLoading = true, notice = null) }
        clientDetailJob = viewModelScope.launch {
            runCatching { repository.clientOrders(clientId, page = nextPage) }
                .onSuccess { page ->
                    if (mutableState.value.selectedClientId == clientId) {
                        mutableState.update {
                            it.copy(
                                selectedClientOrders =
                                    (it.selectedClientOrders + page.content).distinctBy(OrderDto::id),
                                selectedClientOrdersPage = page.page.toInt(),
                                selectedClientOrdersHasMore = page.page + 1 < page.totalPages,
                                selectedClientLoading = false,
                            )
                        }
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (mutableState.value.selectedClientId == clientId) {
                        mutableState.update {
                            it.copy(
                                selectedClientLoading = false,
                                notice = RentalManagerNotice(handleFailure(failure), true),
                            )
                        }
                    }
                }
        }
    }

    fun openOrder(orderId: String) {
        orderDetailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedOrderId = orderId,
                selectedOrder = null,
                selectedOrderPayment = null,
                selectedOrderLoading = true,
                notice = null,
            )
        }
        orderDetailJob = viewModelScope.launch {
            loadOrderDetail(orderId, requireSelected = true)
            loadOrderPayment(orderId)
        }
    }

    /** Reloads the selected order and its list entry after a payment transition or deadline. */
    fun refreshCurrentOrder() {
        val orderId = mutableState.value.selectedOrderId ?: return
        if (orderDetailJob?.isActive == true) return
        orderDetailJob = viewModelScope.launch {
            loadOrderDetail(orderId, requireSelected = true)
            loadOrderPayment(orderId)
            searchOrders(mutableState.value.orderSearch)
        }
    }

    fun createClient(request: CreateRentalClientRequest) {
        val fingerprint = listOf(
            request.clientType,
            request.displayName,
            request.phone,
            request.contactPerson,
            request.email,
            request.comment,
            request.source,
        ).joinToString("\u001f")
        runCommand(CREATE_CLIENT_COMMAND, fingerprint) { key ->
            val client = repository.createClient(key, request)
            searchClients(mutableState.value.clientSearch)
            mutableEvents.emit(RentalManagerNavigationEvent.OpenClient(client.id))
            "Клиент создан"
        }
    }

    fun createOrder(clientId: String, contactPhone: String?, comment: String?) {
        val request = CreateOrderRequest(
            clientId = clientId,
            contactPhone = contactPhone.normalizedOrNull(),
            comment = comment.normalizedOrNull(),
        )
        val fingerprint = listOf(clientId, request.contactPhone, request.comment).joinToString("\u001f")
        runCommand(CREATE_ORDER_COMMAND, fingerprint) { key ->
            val order = repository.createOrder(key, request)
            searchOrders(mutableState.value.orderSearch)
            mutableEvents.emit(RentalManagerNavigationEvent.OpenOrder(order.id))
            "Заказ создан"
        }
    }

    fun updateOrder(contactPhone: String?, comment: String?) {
        val current = mutableState.value.selectedOrder ?: return
        if (current.permissions?.canEdit != true) {
            mutableState.update {
                it.copy(notice = RentalManagerNotice("Этот заказ недоступен для редактирования.", true))
            }
            return
        }
        val fingerprint = listOf(
            current.id,
            current.version,
            contactPhone.normalizedOrNull(),
            comment.normalizedOrNull(),
        ).joinToString("\u001f")
        runCommand(UPDATE_ORDER_COMMAND, fingerprint, conflictOrderId = current.id) { key ->
            val updated = repository.updateOrder(current, key, contactPhone, comment)
            mutableState.update { it.copy(selectedOrder = updated) }
            searchOrders(mutableState.value.orderSearch)
            "Изменения сохранены"
        }
    }

    fun cancelOrder() {
        val current = mutableState.value.selectedOrder ?: return
        if (current.permissions?.canEdit != true || current.status != "DRAFT") {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Удалить можно только редактируемый черновик.",
                        true,
                    ),
                )
            }
            return
        }
        val fingerprint = "${current.id}\u001f${current.version}"
        runCommand(CANCEL_ORDER_COMMAND, fingerprint, conflictOrderId = current.id) { key ->
            val cancelled = repository.cancelOrder(current, key)
            mutableState.update { it.copy(selectedOrder = cancelled) }
            searchOrders(mutableState.value.orderSearch)
            mutableEvents.emit(RentalManagerNavigationEvent.OpenOrders)
            "Черновик удалён: резервирования бытовок освобождены."
        }
    }

    fun saveOrder() {
        val current = mutableState.value.selectedOrder ?: return
        if (current.permissions?.canEdit != true || current.status != "DRAFT") {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Сохранить можно только редактируемый черновик.",
                        true,
                    ),
                )
            }
            return
        }
        val fingerprint = "${current.id}\u001f${current.version}"
        runCommand(SAVE_ORDER_COMMAND, fingerprint, conflictOrderId = current.id) { key ->
            val saved = repository.saveOrder(current, key)
            mutableState.update { it.copy(selectedOrder = saved) }
            loadOrderPayment(saved.id)
            searchOrders(mutableState.value.orderSearch)
            "Заказ сохранён. Счёт выставлен; подтвердите оплату до истечения срока."
        }
    }

    fun confirmOrderPayment() {
        val payment = mutableState.value.selectedOrderPayment?.payment ?: return
        if (!payment.canConfirm) {
            mutableState.update {
                it.copy(notice = RentalManagerNotice("Подтверждение оплаты сейчас недоступно.", true))
            }
            return
        }
        val fingerprint = "${payment.orderId}\u001f${payment.orderVersion}"
        runCommand(PAYMENT_CONFIRM_COMMAND, fingerprint, conflictOrderId = payment.orderId) { key ->
            val confirmed = repository.confirmOrderPayment(payment, key)
            mutableState.update {
                it.copy(
                    selectedOrderPayment = ObservedOrderPayment(
                        payment = confirmed,
                        observedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    ),
                )
            }
            loadOrderDetail(confirmed.orderId, requireSelected = true)
            searchOrders(mutableState.value.orderSearch)
            "Подтверждение оплаты сохранено. Отгрузку можно планировать по статусу заказа."
        }
    }

    private suspend fun handleAuthState(authState: RentalManagerAuthState) {
        when (authState) {
            RentalManagerAuthState.Loading ->
                mutableState.update { it.copy(phase = RentalManagerPhase.LOADING, authMessage = null) }
            RentalManagerAuthState.SignedOut -> {
                sessionJob?.cancel()
                clientSearchJob?.cancel()
                orderSearchJob?.cancel()
                clientDetailJob?.cancel()
                orderDetailJob?.cancel()
                mutableState.value = RentalManagerUiState(phase = RentalManagerPhase.SIGNED_OUT)
            }
            RentalManagerAuthState.Authenticating ->
                mutableState.update {
                    it.copy(phase = RentalManagerPhase.AUTHENTICATING, authMessage = null)
                }
            RentalManagerAuthState.SignedIn -> loadSession(force = false)
            is RentalManagerAuthState.Failure -> {
                sessionJob?.cancel()
                clientSearchJob?.cancel()
                orderSearchJob?.cancel()
                clientDetailJob?.cancel()
                orderDetailJob?.cancel()
                mutableState.value = RentalManagerUiState(
                    phase = RentalManagerPhase.SIGNED_OUT,
                    authMessage = authState.message,
                )
            }
        }
    }

    private fun loadSession(force: Boolean) {
        if (!force && (sessionJob?.isActive == true || mutableState.value.phase == RentalManagerPhase.READY)) {
            return
        }
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            mutableState.update {
                it.copy(phase = RentalManagerPhase.CONNECTING, authMessage = null, notice = null)
            }
            runCatching {
                val session = repository.loadSession()
                coroutineScope {
                    val clients = async { repository.clients("") }
                    val orders = async { repository.orders("") }
                    Triple(session, clients.await(), orders.await())
                }
            }.onSuccess { (session, clientsPage, ordersPage) ->
                mutableState.value = RentalManagerUiState(
                    phase = RentalManagerPhase.READY,
                    session = session,
                    clients = clientsPage.content,
                    clientsPage = clientsPage.page.toInt(),
                    clientsHasMore = clientsPage.page + 1 < clientsPage.totalPages,
                    orders = ordersPage.content,
                    ordersPage = ordersPage.page.toInt(),
                    ordersHasMore = ordersPage.page + 1 < ordersPage.totalPages,
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                mutableState.update {
                    it.copy(
                        phase = RentalManagerPhase.CONNECTING,
                        authMessage = handleFailure(failure),
                    )
                }
            }
        }
    }

    private suspend fun loadOrderDetail(orderId: String, requireSelected: Boolean) {
        runCatching { repository.order(orderId) }
            .onSuccess { order ->
                if (!requireSelected || mutableState.value.selectedOrderId == orderId) {
                    mutableState.update {
                        it.copy(
                            selectedOrderId = orderId,
                            selectedOrder = order,
                            selectedOrderLoading = false,
                        )
                    }
                }
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (!requireSelected || mutableState.value.selectedOrderId == orderId) {
                    mutableState.update {
                        it.copy(
                            selectedOrderLoading = false,
                            notice = RentalManagerNotice(handleFailure(failure), true),
                        )
                    }
                }
            }
    }

    private suspend fun loadOrderPayment(orderId: String) {
        runCatching { repository.orderPayment(orderId) }
            .onSuccess { payment ->
                if (mutableState.value.selectedOrderId == orderId) {
                    mutableState.update {
                        it.copy(
                            selectedOrderPayment = ObservedOrderPayment(
                                payment = payment,
                                observedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                            ),
                        )
                    }
                }
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (mutableState.value.selectedOrderId == orderId) {
                    mutableState.update {
                        it.copy(
                            selectedOrderPayment = null,
                            notice = RentalManagerNotice(handleFailure(failure), true),
                        )
                    }
                }
            }
    }

    private fun runCommand(
        commandName: String,
        fingerprint: String,
        conflictOrderId: String? = null,
        operation: suspend (UUID) -> String,
    ) {
        if (mutableState.value.commandRunning) return
        val actorId = mutableState.value.session?.user?.id
        if (actorId == null) {
            mutableState.update {
                it.copy(notice = RentalManagerNotice("Сессия завершена. Войдите снова.", true))
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(commandRunning = true, notice = null) }
            val key = commandKey(
                commandName,
                actorScopedCommandFingerprint(actorId, fingerprint),
            )
            runCatching { operation(key) }
                .onSuccess { message ->
                    clearCommandKey(commandName)
                    mutableState.update {
                        it.copy(commandRunning = false, notice = RentalManagerNotice(message, false))
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (conflictOrderId != null) {
                        loadOrderDetail(conflictOrderId, requireSelected = true)
                        loadOrderPayment(conflictOrderId)
                    }
                    mutableState.update {
                        it.copy(
                            commandRunning = false,
                            notice = RentalManagerNotice(handleFailure(failure), true),
                        )
                    }
                }
        }
    }

    private fun commandKey(commandName: String, fingerprint: String): UUID {
        val digest = commandFingerprintDigest(fingerprint)
        val storedFingerprint = savedStateHandle.get<String>("$commandName.fingerprint")
        val storedKey = savedStateHandle.get<String>("$commandName.key")
        if (storedFingerprint == digest && storedKey != null) {
            runCatching { UUID.fromString(storedKey) }.getOrNull()?.let { return it }
        }
        val random = UUID.randomUUID()
        savedStateHandle["$commandName.fingerprint"] = digest
        savedStateHandle["$commandName.key"] = random.toString()
        return random
    }

    private fun clearCommandKey(commandName: String) {
        savedStateHandle.remove<String>("$commandName.fingerprint")
        savedStateHandle.remove<String>("$commandName.key")
    }

    private suspend fun handleFailure(failure: Throwable): String {
        return handleRentalManagerFailure(
            failure = failure,
            messageFor = repository::userMessage,
            invalidate = backend.auth::invalidate,
        )
    }

    companion object {
        private const val CREATE_CLIENT_COMMAND = "createClient"
        private const val CREATE_ORDER_COMMAND = "createOrder"
        private const val UPDATE_ORDER_COMMAND = "updateOrder"
        private const val CANCEL_ORDER_COMMAND = "cancelOrder"
        private const val SAVE_ORDER_COMMAND = "saveOrder"
        private const val PAYMENT_CONFIRM_COMMAND = "confirmOrderPayment"

        fun factory(backend: RentalManagerBackend) = viewModelFactory {
            initializer {
                RentalManagerViewModel(backend, createSavedStateHandle())
            }
        }
    }
}

private fun String?.normalizedOrNull(): String? = this?.trim()?.ifEmpty { null }

internal fun commandFingerprintDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

internal fun actorScopedCommandFingerprint(actorId: String, value: String): String =
    "$actorId\u001f$value"

internal fun paymentRemainingMillis(
    payment: OrderPaymentDto,
    observedElapsedRealtimeMillis: Long,
    nowElapsedRealtimeMillis: Long,
): Long? {
    val serverTime = runCatching { OffsetDateTime.parse(payment.serverTime) }.getOrNull() ?: return null
    val expiresAt = payment.expiresAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?: return null
    val initialRemaining = Duration.between(serverTime, expiresAt).toMillis().coerceAtLeast(0L)
    val elapsed = (nowElapsedRealtimeMillis - observedElapsedRealtimeMillis).coerceAtLeast(0L)
    return (initialRemaining - elapsed).coerceAtLeast(0L)
}

internal suspend fun handleRentalManagerFailure(
    failure: Throwable,
    messageFor: (Throwable) -> String,
    invalidate: suspend (String) -> Unit,
): String {
    val message = messageFor(failure)
    if (failure is HttpException && failure.code() == 401) invalidate(message)
    return message
}
