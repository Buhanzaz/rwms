package dev.buhanzaz.rwms.worker.core.auth

import dev.buhanzaz.rwms.worker.core.network.SessionCredentialStore
import dev.buhanzaz.rwms.worker.core.network.TokenRefreshResult
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the worker OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
sealed interface WorkerAuthUiState {
    data object Loading : WorkerAuthUiState
    data object SignedOut : WorkerAuthUiState
    data object Authenticating : WorkerAuthUiState
    data class SignedIn(val displayName: String? = null) : WorkerAuthUiState
    data class Failure(val message: String) : WorkerAuthUiState
}

/**
 * Owns the worker OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
interface WorkerAuthRepository {
    val state: StateFlow<WorkerAuthUiState>
    suspend fun login(username: String, password: String)
    suspend fun logout()
    suspend fun cachedWorkerIdentity(): String?
    /**
     * Persists only the worker ID returned by the authenticated server context; callers must not
     * derive it from a username, cached profile, or push payload.
     */
    suspend fun bindWorkerIdentity(workerId: String)
}

@Singleton
/**
 * Owns the worker OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
class AppAuthWorkerAuthRepository @Inject constructor(
    private val configuration: WorkerAuthConfiguration,
    private val authStateStore: EncryptedAuthStateStore,
    private val authorizationService: AuthorizationService,
    private val nativeLoginClient: NativeWorkerLoginClient,
    @param:RevocationClient
    private val revocationClient: OkHttpClient,
) : WorkerAuthRepository, SessionCredentialStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadedState = AtomicReference<AuthState?>(null)
    private val nativeSessionCookies = AtomicReference<EphemeralCookieJar?>(null)
    private val loginMutex = Mutex()
    private val mutableState = MutableStateFlow<WorkerAuthUiState>(WorkerAuthUiState.Loading)
    override val state: StateFlow<WorkerAuthUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            val state = authStateStore.read()
            loadedState.set(state)
            mutableState.value = if (state?.isAuthorized == true) WorkerAuthUiState.SignedIn() else WorkerAuthUiState.SignedOut
        }
    }

    override suspend fun login(username: String, password: String) {
        loginMutex.withLock {
            if (username.isBlank() || password.isBlank()) {
                mutableState.value = WorkerAuthUiState.Failure("Введите логин и пароль рабочего")
                return
            }

            mutableState.value = WorkerAuthUiState.Authenticating
            var pendingSession: NativeWorkerLoginSession? = null
            try {
                val request = createNativeWorkerAuthorizationRequest(configuration)
                pendingSession = withContext(Dispatchers.IO) {
                    nativeLoginClient.login(username, password, request)
                }
                val tokenResponse = pendingSession.authorizationResponse
                    .performTokenExchange(authorizationService)
                // A new authorization must never inherit another worker's
                // offline cache binding.
                authStateStore.clearBoundWorkerId()
                val state = AuthState(pendingSession.authorizationResponse, tokenResponse, null)
                persist(state)
                nativeSessionCookies.getAndSet(pendingSession.cookies)?.clear()
                pendingSession = null
                mutableState.value = WorkerAuthUiState.SignedIn()
            } catch (cancelled: CancellationException) {
                mutableState.value = WorkerAuthUiState.SignedOut
                throw cancelled
            } catch (failure: NativeWorkerLoginException) {
                mutableState.value = WorkerAuthUiState.Failure(failure.userMessage)
            } catch (_: Throwable) {
                mutableState.value = WorkerAuthUiState.Failure(
                    "Не удалось войти. Проверьте подключение и повторите попытку",
                )
            } finally {
                pendingSession?.cookies?.clear()
            }
        }
    }

    override suspend fun currentAccessToken(): String? = currentState()?.accessToken

    override suspend fun refreshAccessToken(): TokenRefreshResult {
        val state = currentState() ?: return TokenRefreshResult.InvalidGrant
        if (state.refreshToken.isNullOrBlank()) return TokenRefreshResult.InvalidGrant
        val exchange = suspendCancellableCoroutine<RefreshExchange> { continuation ->
            authorizationService.performTokenRequest(state.createTokenRefreshRequest()) { response, exception ->
                if (!continuation.isActive) return@performTokenRequest
                if (response != null) {
                    continuation.resume(RefreshExchange.Response(response))
                } else {
                    continuation.resume(RefreshExchange.Failure(exception))
                }
            }
        }
        return when (exchange) {
            is RefreshExchange.Response -> {
                // AuthState is mutable. Build a detached replacement so a
                // cancelled caller cannot publish a rotated refresh token in
                // memory before its encrypted durable copy exists.
                val rotatedState = AuthState.jsonDeserialize(state.jsonSerializeString())
                rotatedState.update(exchange.response, null)
                // A rotated refresh token must be durable before the new access
                // token is handed to OkHttp; otherwise process death can turn a
                // valid rotation into an unrecoverable replay on the next start.
                withContext(NonCancellable) { persist(rotatedState) }
                exchange.response.accessToken?.let(TokenRefreshResult::Refreshed)
                    ?: TokenRefreshResult.TransientFailure("Token response lacks access token")
            }
            is RefreshExchange.Failure -> {
                if (exchange.exception?.error == "invalid_grant") TokenRefreshResult.InvalidGrant
                else TokenRefreshResult.TransientFailure(exchange.exception?.errorDescription)
            }
        }
    }

    override suspend fun clearSession() {
        clearLocalSession(
            WorkerAuthUiState.Failure(
                "Сессия завершена. Войдите снова; если вход отключён, обратитесь к диспетчеру.",
            ),
        )
    }

    private suspend fun clearLocalSession(nextUiState: WorkerAuthUiState) {
        nativeSessionCookies.getAndSet(null)?.clear()
        loadedState.set(null)
        authStateStore.clear()
        mutableState.value = nextUiState
    }

    override suspend fun cachedWorkerIdentity(): String? = authStateStore.readBoundWorkerId()

    override suspend fun bindWorkerIdentity(workerId: String) {
        authStateStore.writeBoundWorkerId(workerId)
    }

    override suspend fun logout() {
        val state = currentState()
        val refreshToken = state?.refreshToken
        val cookies = nativeSessionCookies.getAndSet(null)
        if (!refreshToken.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val body = FormBody.Builder()
                        .add("client_id", WORKER_OAUTH_CLIENT_ID)
                        .add("token", refreshToken)
                        .add("token_type_hint", "refresh_token")
                        .build()
                    revocationClient.newCall(
                        Request.Builder().url(configuration.revocationEndpoint.toString()).post(body).build(),
                    ).execute().use { /* RFC 7009 logout remains local-safe even when the network is gone. */ }
                }
            }
        }
        if (cookies != null) {
            withContext(Dispatchers.IO) {
                runCatching { nativeLoginClient.logout(cookies) }
            }
        }
        clearLocalSession(WorkerAuthUiState.SignedOut)
    }

    private suspend fun currentState(): AuthState? {
        loadedState.get()?.let { return it }
        return authStateStore.read()?.also(loadedState::set)
    }

    private suspend fun persist(state: AuthState) {
        authStateStore.write(state)
        loadedState.set(state)
    }
}

internal fun createNativeWorkerAuthorizationRequest(
    configuration: WorkerAuthConfiguration,
): AuthorizationRequest {
    return createWorkerAuthorizationRequestBuilder(configuration)
        .setState(randomOAuthToken())
        .setNonce(randomOAuthToken())
        // No prompt=login: the native form has already authenticated this
        // in-memory session, and a forced prompt would reopen browser login.
        .build()
}

private fun createWorkerAuthorizationRequestBuilder(
    configuration: WorkerAuthConfiguration,
): AuthorizationRequest.Builder {
    val serviceConfiguration = AuthorizationServiceConfiguration(
        configuration.authorizationEndpoint,
        configuration.tokenEndpoint,
    )
    return AuthorizationRequest.Builder(
        serviceConfiguration,
        WORKER_OAUTH_CLIENT_ID,
        ResponseTypeValues.CODE,
        configuration.redirectUri,
    )
        .setScope(WORKER_OAUTH_SCOPE)
}

private fun randomOAuthToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

/**
 * Owns the worker OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
private sealed interface RefreshExchange {
    data class Response(val response: TokenResponse) : RefreshExchange
    data class Failure(val exception: AuthorizationException?) : RefreshExchange
}

private suspend fun AuthorizationResponse.performTokenExchange(service: AuthorizationService): TokenResponse =
    suspendCancellableCoroutine { continuation ->
        service.performTokenRequest(createTokenExchangeRequest()) { response, exception ->
            when {
                response != null -> continuation.resume(response)
                exception != null -> continuation.resumeWithException(exception)
                else -> continuation.resumeWithException(IllegalStateException("Token endpoint returned no response"))
            }
        }
    }
