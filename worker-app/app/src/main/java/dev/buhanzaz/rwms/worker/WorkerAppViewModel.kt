package dev.buhanzaz.rwms.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.auth.WorkerAuthRepository
import dev.buhanzaz.rwms.worker.core.auth.WorkerAuthUiState
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.sync.RealtimeHandles
import dev.buhanzaz.rwms.worker.core.sync.WorkerProjectionWriter
import dev.buhanzaz.rwms.worker.core.sync.WorkerRealtimeCoordinator
import dev.buhanzaz.rwms.worker.core.sync.WorkerSyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
sealed interface WorkerAppUiState {
    data object Loading : WorkerAppUiState
    data class SignedOut(
        val message: String? = null,
        val isSubmitting: Boolean = false,
    ) : WorkerAppUiState
    data class Connecting(val message: String) : WorkerAppUiState
    data class Ready(val userId: String, val displayName: String) : WorkerAppUiState
}

@HiltViewModel
/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
class WorkerAppViewModel @Inject constructor(
    private val auth: WorkerAuthRepository,
    private val gateway: WorkerGatewayClient,
    private val localStore: WorkerLocalStore,
    private val projections: WorkerProjectionWriter,
    private val scheduler: WorkerSyncScheduler,
    private val realtime: WorkerRealtimeCoordinator,
    private val push: WorkerPushCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow<WorkerAppUiState>(WorkerAppUiState.Loading)
    val state: StateFlow<WorkerAppUiState> = mutableState.asStateFlow()
    private var activeUserId: String? = null
    private var realtimeHandles: RealtimeHandles? = null

    init {
        viewModelScope.launch {
            auth.state.collect { authState ->
                when (authState) {
                    WorkerAuthUiState.Loading -> mutableState.value = WorkerAppUiState.Loading
                    WorkerAuthUiState.SignedOut -> clearToSignedOut(null)
                    WorkerAuthUiState.Authenticating -> {
                        mutableState.value = WorkerAppUiState.SignedOut(isSubmitting = true)
                    }
                    is WorkerAuthUiState.Failure -> clearToSignedOut(authState.message)
                    is WorkerAuthUiState.SignedIn -> bootstrap()
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
            val cachedWorkerId = auth.cachedWorkerIdentity()
            val cachedSession = cachedWorkerId?.let { userId ->
                localStore.hideExpiredCacheIfNeeded(userId)
                localStore.cachedSession(userId)
            }
            if (cachedSession != null && !cachedSession.cacheHidden && activeUserId == null) {
                activeUserId = cachedSession.userId
                startRealtime(cachedSession.userId)
                scheduler.request(cachedSession.userId)
                mutableState.value = WorkerAppUiState.Ready(cachedSession.userId, cachedSession.displayName)
            } else if (activeUserId == null) {
                mutableState.value = WorkerAppUiState.Connecting("Подключаемся к RWMS…")
            }
            runCatching { gateway.context() }
                .onSuccess { context ->
                    projections.stageContextIdentity(context)
                    auth.bindWorkerIdentity(context.worker.id)
                    push.registerAfterAuthenticatedContext(context.worker.id)
                    activeUserId = context.worker.id
                    scheduler.request(context.worker.id)
                    startRealtime(context.worker.id)
                    mutableState.value = WorkerAppUiState.Ready(context.worker.id, context.worker.displayName)
                }
                .onFailure { error ->
                    if (activeUserId == null) {
                        mutableState.value = WorkerAppUiState.Connecting(
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
        mutableState.value = WorkerAppUiState.SignedOut(message)
    }
}
