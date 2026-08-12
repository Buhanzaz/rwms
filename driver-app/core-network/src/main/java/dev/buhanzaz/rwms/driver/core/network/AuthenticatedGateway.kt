package dev.buhanzaz.rwms.driver.core.network

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Implemented by core-auth; networking intentionally never owns token storage. */
interface SessionCredentialStore {
    suspend fun currentAccessToken(): String?
    suspend fun refreshAccessToken(): TokenRefreshResult
    suspend fun clearSession()
}

/**
 * Encapsulates driver public-gateway transport/failure handling; it is never backend persistence.
 */
sealed interface TokenRefreshResult {
    /** Refresh produced a new bearer access token. */
    data class Refreshed(val accessToken: String) : TokenRefreshResult
    /** Authorization server rejected the refresh grant permanently. */
    data object InvalidGrant : TokenRefreshResult
    /** Refresh failed for a potentially transient transport or server reason. */
    data class TransientFailure(val reason: String? = null) : TokenRefreshResult
}

/**
 * Encapsulates driver public-gateway transport/failure handling; it is never backend persistence.
 */
data class AuthenticatedGatewayState(
    val online: Boolean,
    val lastAuthenticatedContact: Instant?,
    val lastFailure: String? = null,
)

/**
 * Encapsulates driver public-gateway transport/failure handling; it is never backend persistence.
 */
class AuthenticatedGatewayMonitor {
    private val mutableState = MutableStateFlow(AuthenticatedGatewayState(false, null))
    val state: StateFlow<AuthenticatedGatewayState> = mutableState.asStateFlow()

    fun authenticatedContact() {
        mutableState.value = AuthenticatedGatewayState(online = true, lastAuthenticatedContact = Instant.now())
    }

    fun failed(message: String) {
        mutableState.value = mutableState.value.copy(online = false, lastFailure = message)
    }
}

/**
 * Encapsulates driver public-gateway transport/failure handling; it is never backend persistence.
 */
class BearerTokenInterceptor(
    private val credentials: SessionCredentialStore,
    private val monitor: AuthenticatedGatewayMonitor,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { credentials.currentAccessToken() }
        val original = chain.request()
        val builder = original.newBuilder()
        if (original.header("Accept") == null) {
            builder.header("Accept", "application/json, application/problem+json")
        }
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val request = builder.build()
        return try {
            chain.proceed(request).also { response ->
                if (response.isSuccessful || response.code == 304) monitor.authenticatedContact()
                if (response.code >= 500) monitor.failed("HTTP ${response.code}")
            }
        } catch (error: Throwable) {
            monitor.failed(error.message ?: "network")
            throw error
        }
    }
}

/**
 * Encapsulates driver public-gateway transport/failure handling; it is never backend persistence.
 */
class RefreshingAuthenticator(
    private val credentials: SessionCredentialStore,
) : Authenticator {
    private val refreshLock = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        val replacement = runBlocking {
            refreshLock.withLock {
                val current = credentials.currentAccessToken()
                if (!current.isNullOrBlank() && current != sentToken) {
                    return@withLock current
                }
                when (val refresh = credentials.refreshAccessToken()) {
                    is TokenRefreshResult.Refreshed -> refresh.accessToken
                    TokenRefreshResult.InvalidGrant -> {
                        credentials.clearSession()
                        null
                    }
                    is TokenRefreshResult.TransientFailure -> null
                }
            }
        }
        return replacement?.takeIf { it != sentToken }?.let { token ->
            response.request.newBuilder().header("Authorization", "Bearer $token").build()
        }
    }

    private fun responseCount(response: Response): Int {
        var current: Response? = response
        var count = 0
        while (current != null) {
            count += 1
            current = current.priorResponse
        }
        return count
    }
}
