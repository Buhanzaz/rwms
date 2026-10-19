package dev.buhanzaz.rwms.rentalmanager.auth

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the rental-manager OAuth/session boundary. Encrypted client state enables requests but never grants server authorization.
 */
sealed interface RentalManagerAuthState {
    data object Loading : RentalManagerAuthState
    data object SignedOut : RentalManagerAuthState
    data object Authenticating : RentalManagerAuthState
    data object SignedIn : RentalManagerAuthState
    data class Failure(val message: String) : RentalManagerAuthState
}

/**
 * Owns the rental-manager OAuth/session boundary. Encrypted client state enables requests but never grants server authorization.
 */
data class RentalManagerAuthConfiguration(
    val publicBaseUrl: Uri,
) {
    init {
        require(publicBaseUrl.scheme == "https" && !publicBaseUrl.host.isNullOrBlank()) {
            "Only an absolute HTTPS public gateway URL is allowed"
        }
        require(
            publicBaseUrl.encodedUserInfo == null &&
                publicBaseUrl.query == null &&
                publicBaseUrl.fragment == null &&
                (publicBaseUrl.path.isNullOrEmpty() || publicBaseUrl.path == "/"),
        ) {
            "Public gateway URL must be an HTTPS origin without path, query, fragment, or user info"
        }
    }

    val redirectUri: Uri
        get() = publicBaseUrl.buildUpon()
            .appendPath("auth")
            .appendPath("rental-manager")
            .appendPath("callback")
            .build()
    val authorizationEndpoint: Uri
        get() = publicBaseUrl.buildUpon()
            .appendPath("auth")
            .appendPath("oauth2")
            .appendPath("authorize")
            .build()
    val tokenEndpoint: Uri
        get() = publicBaseUrl.buildUpon()
            .appendPath("auth")
            .appendPath("oauth2")
            .appendPath("token")
            .build()
    val revocationEndpoint: Uri
        get() = publicBaseUrl.buildUpon()
            .appendPath("auth")
            .appendPath("oauth2")
            .appendPath("revoke")
            .build()
    val csrfEndpoint: Uri
        get() = publicBaseUrl.buildUpon()
            .appendPath("auth")
            .appendPath("api")
            .appendPath("auth")
            .appendPath("csrf")
            .build()
    val loginEndpoint: Uri
        get() = publicBaseUrl.buildUpon()
            .appendPath("auth")
            .appendPath("login")
            .build()
    val logoutEndpoint: Uri
        get() = publicBaseUrl.buildUpon()
            .appendPath("auth")
            .appendPath("logout")
            .build()
}

private const val RENTAL_MANAGER_CLIENT_ID = "rwms-rental-manager-android"
private const val RENTAL_MANAGER_SCOPE =
    "openid profile offline_access rental.manage"

/**
 * Reuses a valid token unless the caller explicitly rejected that same token. A token refreshed
 * by another request is therefore accepted without rotating the refresh token a second time.
 */
internal fun reusableRentalManagerAccessToken(
    accessToken: String?,
    needsTokenRefresh: Boolean,
    forceRefresh: Boolean,
    rejectedAccessToken: String?,
): String? {
    val usable = accessToken?.takeIf(String::isNotBlank) ?: return null
    if (needsTokenRefresh) return null
    if (!forceRefresh) return usable
    return usable.takeIf { rejectedAccessToken != null && it != rejectedAccessToken }
}

/**
 * Owns the rental-manager OAuth/session boundary. Encrypted client state enables requests but never grants server authorization.
 */
class RentalManagerAuthRepository(
    context: Context,
    val configuration: RentalManagerAuthConfiguration,
    rawClient: OkHttpClient? = null,
) : Closeable {
    private val applicationContext = context.applicationContext
    /*
     * A signed-out launch only restores the encrypted state.  Do not construct an OkHttp client
     * or AppAuth's AuthorizationService on the UI thread before the user starts a login or an
     * authenticated request needs a refresh token.
     */
    private val stateStore = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedRentalManagerAuthStateStore(applicationContext)
    }
    private val rawClient = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttp.initialize(applicationContext)
        rawClient ?: OkHttpClient()
    }
    private val authorizationService = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AuthorizationService(applicationContext)
    }
    private val nativeLogin = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeRentalManagerLoginClient(configuration, this.rawClient.value)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadedState = AtomicReference<AuthState?>(null)
    private val nativeCookies = AtomicReference<EphemeralCookieJar?>(null)
    private val loginMutex = Mutex()
    private val mutableState = MutableStateFlow<RentalManagerAuthState>(RentalManagerAuthState.Loading)

    val state: StateFlow<RentalManagerAuthState> = mutableState.asStateFlow()

    init {
        scope.launch {
            val restored = stateStore.value.read()
            loadedState.set(restored)
            mutableState.value =
                if (restored?.isAuthorized == true) RentalManagerAuthState.SignedIn
                else RentalManagerAuthState.SignedOut
        }
    }

    suspend fun login(username: String, password: String) {
        loginMutex.withLock {
            if (username.isBlank() || password.isBlank()) {
                mutableState.value = RentalManagerAuthState.Failure("Введите логин и пароль")
                return
            }
            mutableState.value = RentalManagerAuthState.Authenticating
            var pending: NativeRentalManagerLoginSession? = null
            try {
                pending = withContext(Dispatchers.IO) {
                    nativeLogin.value.login(
                        username.trim(),
                        password,
                        createRentalManagerAuthorizationRequest(configuration),
                    )
                }
                val token = withContext(Dispatchers.IO) {
                    pending.authorizationResponse.performTokenExchange(authorizationService.value)
                }
                val next = AuthState(pending.authorizationResponse, token, null)
                persist(next)
                nativeCookies.getAndSet(pending.cookies)?.clear()
                pending = null
                mutableState.value = RentalManagerAuthState.SignedIn
            } catch (cancelled: CancellationException) {
                mutableState.value = RentalManagerAuthState.SignedOut
                throw cancelled
            } catch (failure: NativeRentalManagerLoginException) {
                mutableState.value = RentalManagerAuthState.Failure(failure.userMessage)
            } catch (failure: AuthorizationException) {
                mutableState.value = RentalManagerAuthState.Failure(
                    if (failure.error == "access_denied") {
                        MOBILE_ACCESS_MESSAGE
                    } else {
                        "Не удалось безопасно завершить вход. Повторите попытку"
                    },
                )
            } catch (_: Throwable) {
                mutableState.value = RentalManagerAuthState.Failure(
                    "Не удалось войти. Проверьте подключение и повторите попытку",
                )
            } finally {
                pending?.cookies?.clear()
            }
        }
    }

    /**
     * Returns a usable token, serializing refreshes and durably replacing the encrypted session.
     * Only terminal authorization failures clear the local session; transient refresh failures
     * remain transport errors so a temporary outage cannot sign the manager out.
     */
    suspend fun freshAccessToken(
        forceRefresh: Boolean = false,
        rejectedAccessToken: String? = null,
    ): String? {
        val current = currentState() ?: return null
        reusableRentalManagerAccessToken(
            accessToken = current.accessToken,
            needsTokenRefresh = current.needsTokenRefresh,
            forceRefresh = forceRefresh,
            rejectedAccessToken = rejectedAccessToken,
        )?.let { return it }
        return RentalManagerProcessRefreshCoordinator.withLock {
            // WorkManager and the foreground UI own separate repository instances backed by the
            // same encrypted DataStore. Re-read it after acquiring the process-wide rotation lock
            // so a second caller observes the token just rotated by the first one.
            val latest = persistedState() ?: return@withLock null
            reusableRentalManagerAccessToken(
                accessToken = latest.accessToken,
                needsTokenRefresh = latest.needsTokenRefresh,
                forceRefresh = forceRefresh,
                rejectedAccessToken = rejectedAccessToken,
            )?.let { return@withLock it }
            if (latest.refreshToken.isNullOrBlank()) {
                clearSession(RentalManagerAuthState.SignedOut)
                return@withLock null
            }
            val detached = AuthState.jsonDeserialize(latest.jsonSerializeString())
            if (forceRefresh) detached.needsTokenRefresh = true
            val exchange = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<RefreshExchange> { continuation ->
                    authorizationService.value.performTokenRequest(detached.createTokenRefreshRequest()) {
                            response,
                            exception,
                        ->
                        if (!continuation.isActive) return@performTokenRequest
                        if (response != null) {
                            continuation.resume(RefreshExchange.Success(response))
                        } else {
                            continuation.resume(RefreshExchange.Failure(exception))
                        }
                    }
                }
            }
            when (exchange) {
                is RefreshExchange.Success -> {
                    val accessToken = exchange.response.accessToken?.takeIf(String::isNotBlank)
                        ?: throw IOException("Token refresh returned no access token")
                    detached.update(exchange.response, null)
                    withContext(NonCancellable) { persist(detached) }
                    accessToken
                }
                is RefreshExchange.Failure -> {
                    if (exchange.exception?.error == "invalid_grant" ||
                        exchange.exception?.error == "access_denied"
                    ) {
                        clearSession(RentalManagerAuthState.Failure(MOBILE_ACCESS_MESSAGE))
                        null
                    } else {
                        throw IOException(
                            "Token refresh is temporarily unavailable",
                            exchange.exception,
                        )
                    }
                }
            }
        }
    }

    /**
     * Attempts remote revocation/logout, then always removes the local encrypted session and
     * ephemeral browser cookies even if the gateway is unavailable.
     */
    suspend fun logout() {
        val state = currentState()
        val refreshToken = state?.refreshToken
        val cookies = nativeCookies.getAndSet(null)
        if (!refreshToken.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val body = FormBody.Builder()
                        .add("client_id", RENTAL_MANAGER_CLIENT_ID)
                        .add("token", refreshToken)
                        .add("token_type_hint", "refresh_token")
                        .build()
                    rawClient.value.newCall(
                        Request.Builder()
                            .url(configuration.revocationEndpoint.toString())
                            .post(body)
                            .build(),
                    ).execute().use { }
                }
            }
        }
        if (cookies != null) {
            withContext(Dispatchers.IO) { runCatching { nativeLogin.value.logout(cookies) } }
        }
        clearSession(RentalManagerAuthState.SignedOut)
    }

    suspend fun invalidate(message: String) {
        clearSession(RentalManagerAuthState.Failure(message))
    }

    private suspend fun currentState(): AuthState? {
        loadedState.get()?.let { return it }
        return persistedState()
    }

    private suspend fun persistedState(): AuthState? = withContext(Dispatchers.IO) {
        stateStore.value.read().also(loadedState::set)
    }

    private suspend fun persist(state: AuthState) {
        withContext(Dispatchers.IO) { stateStore.value.write(state) }
        loadedState.set(state)
    }

    private suspend fun clearSession(next: RentalManagerAuthState) {
        nativeCookies.getAndSet(null)?.clear()
        loadedState.set(null)
        withContext(Dispatchers.IO) { stateStore.value.clear() }
        mutableState.value = next
    }

    override fun close() {
        nativeCookies.getAndSet(null)?.clear()
        if (authorizationService.isInitialized()) {
            authorizationService.value.dispose()
        }
    }
}

/** Serializes rotating rental-manager refresh tokens across foreground and WorkManager repositories. */
internal object RentalManagerProcessRefreshCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Owns the rental-manager OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
internal sealed class NativeRentalManagerLoginException(
    val userMessage: String,
) : IOException(userMessage) {
    class InvalidCredentials :
        NativeRentalManagerLoginException("Неверный логин или пароль")

    class ManagerAccessRequired :
        NativeRentalManagerLoginException(MOBILE_ACCESS_MESSAGE)

    class ProtocolFailure :
        NativeRentalManagerLoginException("Не удалось безопасно выполнить вход. Повторите попытку")
}

/**
 * Owns the rental-manager OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
internal data class NativeRentalManagerLoginSession(
    val authorizationResponse: AuthorizationResponse,
    val cookies: EphemeralCookieJar,
)

/**
 * Owns the rental-manager OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
internal class NativeRentalManagerLoginClient private constructor(
    private val csrfEndpoint: HttpUrl,
    private val loginEndpoint: HttpUrl,
    private val logoutEndpoint: HttpUrl,
    private val authorizationEndpoint: Uri,
    private val clientTemplate: OkHttpClient,
) {
    constructor(
        configuration: RentalManagerAuthConfiguration,
        clientTemplate: OkHttpClient,
    ) : this(
        configuration.csrfEndpoint.toString().toHttpUrl(),
        configuration.loginEndpoint.toString().toHttpUrl(),
        configuration.logoutEndpoint.toString().toHttpUrl(),
        configuration.authorizationEndpoint,
        clientTemplate,
    )

    internal constructor(
        publicBaseUrl: HttpUrl,
        clientTemplate: OkHttpClient,
    ) : this(
        requireNotNull(publicBaseUrl.resolve("/auth/api/auth/csrf")),
        requireNotNull(publicBaseUrl.resolve("/auth/login")),
        requireNotNull(publicBaseUrl.resolve("/auth/logout")),
        Uri.parse(requireNotNull(publicBaseUrl.resolve("/auth/oauth2/authorize")).toString()),
        clientTemplate,
    )

    suspend fun login(
        username: String,
        password: String,
        authorizationRequest: AuthorizationRequest,
    ): NativeRentalManagerLoginSession {
        if (authorizationRequest.configuration.authorizationEndpoint != authorizationEndpoint) {
            throw NativeRentalManagerLoginException.ProtocolFailure()
        }
        val cookies = EphemeralCookieJar()
        val client = clientTemplate.newBuilder()
            .cookieJar(cookies)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        try {
            val csrf = fetchCsrf(client)
            submitCredentials(client, username, password, csrf)
            return NativeRentalManagerLoginSession(authorize(client, authorizationRequest), cookies)
        } catch (failure: Throwable) {
            cookies.clear()
            throw failure
        }
    }

    suspend fun logout(cookies: EphemeralCookieJar) {
        val client = clientTemplate.newBuilder()
            .cookieJar(cookies)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        try {
            val csrf = fetchCsrf(client)
            val body = FormBody.Builder()
                .add(csrf.parameterName, csrf.token)
                .build()
            client.newCall(Request.Builder().url(logoutEndpoint).post(body).build())
                .execute()
                .use { }
        } finally {
            cookies.clear()
        }
    }

    private fun fetchCsrf(client: OkHttpClient): CsrfToken {
        client.newCall(Request.Builder().url(csrfEndpoint).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw NativeRentalManagerLoginException.ProtocolFailure()
            val payload = response.body.string()
            if (payload.length > MAX_CSRF_RESPONSE_LENGTH) {
                throw NativeRentalManagerLoginException.ProtocolFailure()
            }
            val json = runCatching { JSONObject(payload) }
                .getOrElse { throw NativeRentalManagerLoginException.ProtocolFailure() }
            val parameterName = json.optString("parameterName")
            val token = json.optString("token")
            if (parameterName.isBlank() || token.isBlank()) {
                throw NativeRentalManagerLoginException.ProtocolFailure()
            }
            return CsrfToken(parameterName, token)
        }
    }

    private fun submitCredentials(
        client: OkHttpClient,
        username: String,
        password: String,
        csrf: CsrfToken,
    ) {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add(csrf.parameterName, csrf.token)
            .build()
        client.newCall(Request.Builder().url(loginEndpoint).post(body).build()).execute().use { response ->
            val location = response.redirectLocationOrNull()
                ?: throw NativeRentalManagerLoginException.ProtocolFailure()
            if (!location.hasSameOrigin(loginEndpoint)) {
                throw NativeRentalManagerLoginException.ProtocolFailure()
            }
            if (location.encodedPath == loginEndpoint.encodedPath &&
                location.queryParameterNames.contains("error")
            ) {
                throw NativeRentalManagerLoginException.InvalidCredentials()
            }
        }
    }

    private fun authorize(
        client: OkHttpClient,
        request: AuthorizationRequest,
    ): AuthorizationResponse {
        client.newCall(Request.Builder().url(request.toUri().toString()).get().build())
            .execute()
            .use { response ->
                if (response.code !in 300..399) {
                    throw NativeRentalManagerLoginException.ProtocolFailure()
                }
                val location = response.header("Location")?.let(Uri::parse)
                    ?: throw NativeRentalManagerLoginException.ProtocolFailure()
                return parseAuthorizationCallback(request, location)
            }
    }
}

/**
 * Owns the rental-manager OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
internal class EphemeralCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { stored ->
            stored.expiresAt <= now ||
                cookies.any { incoming ->
                    stored.name == incoming.name &&
                        stored.domain == incoming.domain &&
                        stored.path == incoming.path
                }
        }
        this.cookies += cookies.filter { it.expiresAt > now }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
    }
}

internal fun parseAuthorizationCallback(
    request: AuthorizationRequest,
    callback: Uri,
): AuthorizationResponse {
    val expected = request.redirectUri
    if (callback.fragment != null ||
        callback.scheme != expected.scheme ||
        callback.encodedAuthority != expected.encodedAuthority ||
        callback.encodedPath != expected.encodedPath
    ) {
        throw NativeRentalManagerLoginException.ProtocolFailure()
    }
    val states = callback.getQueryParameters("state")
    if (request.state.isNullOrBlank() || states.size != 1 || states.single() != request.state) {
        throw NativeRentalManagerLoginException.ProtocolFailure()
    }
    val errors = callback.getQueryParameters("error")
    if (errors.size > 1) throw NativeRentalManagerLoginException.ProtocolFailure()
    if (errors.singleOrNull() == "access_denied") {
        throw NativeRentalManagerLoginException.ManagerAccessRequired()
    }
    if (errors.isNotEmpty()) throw NativeRentalManagerLoginException.ProtocolFailure()
    val codes = callback.getQueryParameters("code")
    if (codes.size != 1 || codes.single().isBlank()) {
        throw NativeRentalManagerLoginException.ProtocolFailure()
    }
    return AuthorizationResponse.Builder(request)
        .setAuthorizationCode(codes.single())
        .build()
}

internal fun createRentalManagerAuthorizationRequest(
    configuration: RentalManagerAuthConfiguration,
): AuthorizationRequest {
    val service = AuthorizationServiceConfiguration(
        configuration.authorizationEndpoint,
        configuration.tokenEndpoint,
    )
    return AuthorizationRequest.Builder(
        service,
        RENTAL_MANAGER_CLIENT_ID,
        ResponseTypeValues.CODE,
        configuration.redirectUri,
    )
        .setScope(RENTAL_MANAGER_SCOPE)
        .setState(randomOAuthToken())
        .setNonce(randomOAuthToken())
        .build()
}

private suspend fun AuthorizationResponse.performTokenExchange(
    service: AuthorizationService,
): TokenResponse = suspendCancellableCoroutine { continuation ->
    service.performTokenRequest(createTokenExchangeRequest()) { response, exception ->
        when {
            response != null -> continuation.resume(response)
            exception != null -> continuation.resumeWithException(exception)
            else -> continuation.resumeWithException(
                IllegalStateException("Token endpoint returned no response"),
            )
        }
    }
}

private val Context.rentalManagerAuthDataStore by preferencesDataStore(name = "rental_manager_auth_state")
private val encryptedAuthStateKey = stringPreferencesKey("encrypted_auth_state")

/**
 * Owns the rental-manager OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
private class EncryptedRentalManagerAuthStateStore(
    private val context: Context,
) {
    suspend fun read(): AuthState? {
        val encrypted = context.rentalManagerAuthDataStore.data.first()[encryptedAuthStateKey] ?: return null
        return runCatching { AuthState.jsonDeserialize(decrypt(encrypted)) }.getOrNull()
    }

    suspend fun write(state: AuthState) {
        val encrypted = encrypt(state.jsonSerializeString())
        context.rentalManagerAuthDataStore.edit { it[encryptedAuthStateKey] = encrypted }
    }

    suspend fun clear() {
        context.rentalManagerAuthDataStore.edit { it.remove(encryptedAuthStateKey) }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(":")
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(":", limit = 2)
        require(parts.size == 2) { "Malformed encrypted auth state" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            javax.crypto.spec.GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            StandardCharsets.UTF_8,
        )
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getKey(AUTH_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    AUTH_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}

/**
 * Owns the rental-manager OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
private data class CsrfToken(
    val parameterName: String,
    val token: String,
)

/**
 * Owns the rental-manager OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
private sealed interface RefreshExchange {
    data class Success(val response: TokenResponse) : RefreshExchange
    data class Failure(val exception: AuthorizationException?) : RefreshExchange
}

private fun randomOAuthToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun Response.redirectLocationOrNull(): HttpUrl? {
    if (code !in 300..399) return null
    return header("Location")?.let(request.url::resolve)
}

private fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

private const val MAX_CSRF_RESPONSE_LENGTH = 16_384
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val AUTH_KEY_ALIAS = "rwms-rental-manager-auth-state-v1"
private const val MOBILE_ACCESS_MESSAGE =
    "Доступ к приложению менеджера аренды не разрешён. Обратитесь к администратору"
