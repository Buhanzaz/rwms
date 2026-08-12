package dev.buhanzaz.rwms.driver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.driver.core.auth.DriverAuthRepository
import dev.buhanzaz.rwms.driver.core.auth.DriverAuthUiState
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.sync.RealtimeHandles
import dev.buhanzaz.rwms.driver.core.sync.DriverProjectionWriter
import dev.buhanzaz.rwms.driver.core.sync.DriverRealtimeCoordinator
import dev.buhanzaz.rwms.driver.core.sync.DriverSyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
sealed interface DriverAppUiState {
    /** Encrypted session state is still being restored. */
    data object Loading : DriverAppUiState
    /** No usable session exists; an optional failure is safe to display. */
    data class SignedOut(
        val message: String? = null,
        val isSubmitting: Boolean = false,
    ) : DriverAppUiState
    /** Login succeeded and the first authoritative context is being fetched. */
    data class Connecting(val message: String) : DriverAppUiState
    /** Authoritative driver identity is ready for navigation and synchronization. */
    data class Ready(val userId: String, val displayName: String) : DriverAppUiState
}

@HiltViewModel
/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
class DriverAppViewModel @Inject constructor(
    private val auth: DriverAuthRepository,
    private val gateway: DriverGatewayClient,
    private val localStore: DriverLocalStore,
    private val projections: DriverProjectionWriter,
    private val scheduler: DriverSyncScheduler,
    private val realtime: DriverRealtimeCoordinator,
    private val push: DriverPushCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow<DriverAppUiState>(DriverAppUiState.Loading)
    val state: StateFlow<DriverAppUiState> = mutableState.asStateFlow()
    private var activeUserId: String? = null
    private var realtimeHandles: RealtimeHandles? = null

    init {
        viewModelScope.launch {
            auth.state.collect { authState ->
                when (authState) {
                    DriverAuthUiState.Loading -> mutableState.value = DriverAppUiState.Loading
                    DriverAuthUiState.SignedOut -> clearToSignedOut(null)
                    DriverAuthUiState.Authenticating -> {
                        mutableState.value = DriverAppUiState.SignedOut(isSubmitting = true)
                    }
                    is DriverAuthUiState.Failure -> clearToSignedOut(authState.message)
                    is DriverAuthUiState.SignedIn -> bootstrap()
                }
            }
        }
    }

    fun retry() = bootstrap()

    fun login(username: String, password: String) {
        viewModelScope.launch {
            auth.login(username.trim(), password)
        }
    }

    fun logout() {
        realtimeHandles?.cancel()
        realtimeHandles = null
        activeUserId = null
        viewModelScope.launch {
            push.unregisterBeforeLogout()
            auth.logout()
        }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val cachedDriverId = auth.cachedDriverIdentity()
            val cachedSession = cachedDriverId?.let { userId ->
                localStore.hideExpiredCacheIfNeeded(userId)
                localStore.cachedSession(userId)
            }
            if (cachedSession != null && !cachedSession.cacheHidden && activeUserId == null) {
                activeUserId = cachedSession.userId
                startRealtime(cachedSession.userId)
                scheduler.request(cachedSession.userId)
                mutableState.value = DriverAppUiState.Ready(cachedSession.userId, cachedSession.displayName)
            } else if (activeUserId == null) {
                mutableState.value = DriverAppUiState.Connecting("Подключаемся к RWMS…")
            }
            runCatching { gateway.context() }
                .onSuccess { context ->
                    projections.stageContextIdentity(context)
                    auth.bindDriverIdentity(context.driver.id)
                    push.registerAfterAuthenticatedContext(context.driver.id)
                    activeUserId = context.driver.id
                    scheduler.request(context.driver.id)
                    startRealtime(context.driver.id)
                    mutableState.value = DriverAppUiState.Ready(context.driver.id, context.driver.displayName)
                }
                .onFailure { error ->
                    if (activeUserId == null) {
                        mutableState.value = DriverAppUiState.Connecting(
                            error.message ?: "Не удалось связаться с RWMS. Проверьте сеть и повторите.",
                        )
                    }
                }
        }
    }

    private fun startRealtime(userId: String) {
        if (activeUserId != null && activeUserId != userId) realtimeHandles?.cancel()
        realtimeHandles?.cancel()
        realtimeHandles = realtime.start(viewModelScope, userId)
    }

    private fun clearToSignedOut(message: String?) {
        realtimeHandles?.cancel()
        realtimeHandles = null
        activeUserId = null
        mutableState.value = DriverAppUiState.SignedOut(message)
    }
}
