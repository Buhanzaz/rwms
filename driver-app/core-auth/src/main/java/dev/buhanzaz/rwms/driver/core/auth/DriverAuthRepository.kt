package dev.buhanzaz.rwms.driver.core.auth

import dev.buhanzaz.rwms.driver.core.network.SessionCredentialStore
import dev.buhanzaz.rwms.driver.core.network.TokenRefreshResult
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
 * Owns the driver OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
sealed interface DriverAuthUiState {
    /** Encrypted authorization state is still loading. */
    data object Loading : DriverAuthUiState
    /** No valid local authorization state exists. */
    data object SignedOut : DriverAuthUiState
    /** The PKCE login ceremony is in progress. */
    data object Authenticating : DriverAuthUiState
    /** A bearer session exists; display name remains server-projected metadata. */
    data class SignedIn(val displayName: String? = null) : DriverAuthUiState
    /** Authentication failed with a sanitized user-visible reason. */
    data class Failure(val message: String) : DriverAuthUiState
}

/**
 * Owns the driver OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
interface DriverAuthRepository {
    val state: StateFlow<DriverAuthUiState>
    suspend fun login(username: String, password: String)
    suspend fun logout()
    suspend fun cachedDriverIdentity(): String?
    /**
     * Persists only the driver ID returned by the authenticated server context; callers must not
     * derive it from a username, cached profile, or push payload.
     */
    suspend fun bindDriverIdentity(driverId: String)
}

@Singleton
/**
 * Owns the driver OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
class AppAuthDriverAuthRepository @Inject constructor(
    private val configuration: DriverAuthConfiguration,
    private val authStateStore: EncryptedAuthStateStore,
    private val authorizationService: AuthorizationService,
    private val nativeLoginClient: NativeDriverLoginClient,
    @param:RevocationClient
    private val revocationClient: OkHttpClient,
) : DriverAuthRepository, SessionCredentialStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadedState = AtomicReference<AuthState?>(null)
    private val nativeSessionCookies = AtomicReference<EphemeralCookieJar?>(null)
    private val loginMutex = Mutex()
    private val mutableState = MutableStateFlow<DriverAuthUiState>(DriverAuthUiState.Loading)
    override val state: StateFlow<DriverAuthUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            val state = authStateStore.read()
            loadedState.set(state)
            mutableState.value = if (state?.isAuthorized == true) DriverAuthUiState.SignedIn() else DriverAuthUiState.SignedOut
        }
    }

    override suspend fun login(username: String, password: String) {
        loginMutex.withLock {
            if (username.isBlank() || password.isBlank()) {
                mutableState.value = DriverAuthUiState.Failure("Введите логин и пароль водителя")
                return
            }

            mutableState.value = DriverAuthUiState.Authenticating
            var pendingSession: NativeDriverLoginSession? = null
            try {
                val request = createNativeDriverAuthorizationRequest(configuration)
                pendingSession = withContext(Dispatchers.IO) {
                    nativeLoginClient.login(username, password, request)
                }
                val tokenResponse = pendingSession.authorizationResponse
                    .performTokenExchange(authorizationService)
                // A new authorization must never inherit another driver's
                // offline cache binding.
                authStateStore.clearBoundDriverId()
                val state = AuthState(pendingSession.authorizationResponse, tokenResponse, null)
                persist(state)
                nativeSessionCookies.getAndSet(pendingSession.cookies)?.clear()
                pendingSession = null
                mutableState.value = DriverAuthUiState.SignedIn()
            } catch (cancelled: CancellationException) {
                mutableState.value = DriverAuthUiState.SignedOut
                throw cancelled
            } catch (failure: NativeDriverLoginException) {
                mutableState.value = DriverAuthUiState.Failure(failure.userMessage)
            } catch (_: Throwable) {
                mutableState.value = DriverAuthUiState.Failure(
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
            DriverAuthUiState.Failure(
                "Сессия завершена. Войдите снова; если вход отключён, обратитесь к диспетчеру.",
            ),
        )
    }

    private suspend fun clearLocalSession(nextUiState: DriverAuthUiState) {
        nativeSessionCookies.getAndSet(null)?.clear()
        loadedState.set(null)
        authStateStore.clear()
        mutableState.value = nextUiState
    }

    override suspend fun cachedDriverIdentity(): String? = authStateStore.readBoundDriverId()

    override suspend fun bindDriverIdentity(driverId: String) {
        authStateStore.writeBoundDriverId(driverId)
    }

    override suspend fun logout() {
        val state = currentState()
        val refreshToken = state?.refreshToken
        val cookies = nativeSessionCookies.getAndSet(null)
        if (!refreshToken.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val body = FormBody.Builder()
                        .add("client_id", DRIVER_OAUTH_CLIENT_ID)
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
        clearLocalSession(DriverAuthUiState.SignedOut)
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

internal fun createNativeDriverAuthorizationRequest(
    configuration: DriverAuthConfiguration,
): AuthorizationRequest {
    return createDriverAuthorizationRequestBuilder(configuration)
        .setState(randomOAuthToken())
        .setNonce(randomOAuthToken())
        // No prompt=login: the native form has already authenticated this
        // in-memory session, and a forced prompt would reopen browser login.
        .build()
}

private fun createDriverAuthorizationRequestBuilder(
    configuration: DriverAuthConfiguration,
): AuthorizationRequest.Builder {
    val serviceConfiguration = AuthorizationServiceConfiguration(
        configuration.authorizationEndpoint,
        configuration.tokenEndpoint,
    )
    return AuthorizationRequest.Builder(
        serviceConfiguration,
        DRIVER_OAUTH_CLIENT_ID,
        ResponseTypeValues.CODE,
        configuration.redirectUri,
    )
        .setScope(DRIVER_OAUTH_SCOPE)
}

private fun randomOAuthToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

/**
 * Owns the driver OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
private sealed interface RefreshExchange {
    /** Authorization server returned a successful token response. */
    data class Response(val response: TokenResponse) : RefreshExchange
    /** AppAuth returned a classified token exchange exception. */
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
