package dev.buhanzaz.rwms.client.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.buhanzaz.rwms.client.BuildConfig
import dev.buhanzaz.rwms.client.data.CsrfPayload
import dev.buhanzaz.rwms.client.data.OAuthTokenPayload
import dev.buhanzaz.rwms.client.data.ProblemDetails
import dev.buhanzaz.rwms.client.data.RegistrationRequest
import dev.buhanzaz.rwms.client.data.RegistrationResponse
import dev.buhanzaz.rwms.client.data.customerProblemMessage
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/** Observable authorization state that drives the signed-out/signed-in Navigation 3 graph. */
sealed interface CustomerAuthState {
    /** Encrypted session restoration is still running off the main thread. */
    data object Loading : CustomerAuthState

    /** No usable customer session exists. */
    data class SignedOut(val message: String? = null) : CustomerAuthState

    /** A credential or registration exchange is currently in progress. */
    data object Authenticating : CustomerAuthState

    /** A usable access or refresh token exists in memory or encrypted local storage. */
    data object SignedIn : CustomerAuthState
}

/** Validates registration fields consistently before any credential leaves the device. */
object RegistrationValidator {
    /** Returns a localized error or `null` when the request can be submitted. */
    fun validate(username: String, password: String, confirmation: String): String? = when {
        username.trim().length !in 3..64 -> "Логин должен содержать от 3 до 64 символов"
        !CustomerUsernamePattern.matches(username.trim()) ->
            "Логин должен начинаться с латинской буквы или цифры и содержать только " +
                "латинские буквы, цифры, точку, дефис и подчёркивание"
        password.length !in 8..128 -> "Пароль должен содержать от 8 до 128 символов"
        password != confirmation -> "Пароли не совпадают"
        else -> null
    }
}

private val CustomerUsernamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

/** Encrypted token record stored in DataStore; credentials and cookies are never represented here. */
@Serializable
internal data class StoredCustomerSession(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String? = null,
    @SerialName("expiresAtEpochMs") val expiresAtEpochMs: Long,
    @SerialName("scope") val scope: String? = null,
) {
    /** Treats the token as expired slightly early to avoid racing a request in transit. */
    fun isAccessTokenUsable(nowEpochMs: Long): Boolean = expiresAtEpochMs - TOKEN_LEEWAY_MS > nowEpochMs
}

/** Configuration for the public HTTPS OAuth and registration surface. */
@Singleton
class CustomerAuthConfiguration @Inject constructor() {
    /** Validated gateway origin selected at build time. */
    val publicOrigin: HttpUrl = BuildConfig.PUBLIC_BASE_URL.toHttpUrl().also { url ->
        require(url.isHttps && url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "CustomerApp requires an HTTPS gateway origin"
        }
    }

    /** Customer authorization endpoint behind the same public origin. */
    val authorizeUrl: HttpUrl = requireNotNull(publicOrigin.resolve("/auth/oauth2/authorize"))

    /** OAuth token endpoint behind the public gateway. */
    val tokenUrl: HttpUrl = requireNotNull(publicOrigin.resolve("/auth/oauth2/token"))

    /** Spring Security login form target used with ephemeral cookies. */
    val loginUrl: HttpUrl = requireNotNull(publicOrigin.resolve("/auth/login"))

    /** CSRF metadata endpoint shared by form login and JSON registration. */
    val csrfUrl: HttpUrl = requireNotNull(publicOrigin.resolve("/auth/api/auth/csrf"))

    /** Anonymous customer registration endpoint. */
    val registrationUrl: HttpUrl = requireNotNull(publicOrigin.resolve("/auth/api/customer/v1/registrations"))

    /** OAuth revocation endpoint used during best-effort logout. */
    val revocationUrl: HttpUrl = requireNotNull(publicOrigin.resolve("/auth/oauth2/revoke"))

    /** Exact registered native callback; the native flow validates but never opens it. */
    val redirectUri: String = "https://localhost/auth/customer/callback"
}

/** Securely performs customer registration and OAuth Authorization Code + S256 PKCE exchanges. */
@Singleton
class CustomerAuthRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val configuration: CustomerAuthConfiguration,
    @param:Named("raw") private val rawClient: OkHttpClient,
    private val json: Json,
) {
    private val sessionStore = EncryptedCustomerSessionStore(context, json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exchangeMutex = Mutex()
    private val registrationMutex = Mutex()
    private val mutableState = MutableStateFlow<CustomerAuthState>(CustomerAuthState.Loading)
    @Volatile private var cachedSession: StoredCustomerSession? = null
    @Volatile private var persistSessionAcrossRestarts = false

    /** Current session state consumed by the app-level conditional graph. */
    val state: StateFlow<CustomerAuthState> = mutableState.asStateFlow()

    init {
        scope.launch {
            val restored = sessionStore.read()
            cachedSession = restored
            persistSessionAcrossRestarts = restored != null
            mutableState.value = if (restored != null) {
                CustomerAuthState.SignedIn
            } else {
                CustomerAuthState.SignedOut()
            }
        }
    }

    /** Registers the account with CSRF protection, then signs in through the same PKCE flow. */
    suspend fun register(username: String, password: String, confirmation: String) {
        if (!registrationMutex.tryLock()) return
        try {
            RegistrationValidator.validate(username, password, confirmation)?.let { message ->
                mutableState.value = CustomerAuthState.SignedOut(message)
                return
            }
            mutableState.value = CustomerAuthState.Authenticating
            try {
                withContext(Dispatchers.IO) {
                    val cookies = EphemeralCustomerCookieJar()
                    val client = ephemeralClient(cookies)
                    try {
                        val csrf = fetchCsrf(client)
                        val body = json.encodeToString(
                            RegistrationRequest(username.trim(), password, confirmation),
                        ).toRequestBodyJson()
                        client.newCall(
                            Request.Builder()
                                .url(configuration.registrationUrl)
                                .header(csrf.headerName, csrf.token)
                                .post(body)
                                .build(),
                        ).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw response.asAuthFailure("Не удалось зарегистрироваться")
                            }
                            response.body.string().takeIf(String::isNotBlank)?.let {
                                json.decodeFromString<RegistrationResponse>(it)
                            }
                        }
                    } finally {
                        cookies.clear()
                    }
                }
                login(username, password, rememberMe = true)
            } catch (cancelled: CancellationException) {
                mutableState.value = CustomerAuthState.SignedOut()
                throw cancelled
            } catch (failure: CustomerAuthException) {
                mutableState.value = CustomerAuthState.SignedOut(failure.userMessage)
            } catch (_: Throwable) {
                mutableState.value = CustomerAuthState.SignedOut("Регистрация временно недоступна")
            }
        } finally {
            registrationMutex.unlock()
        }
    }

    /**
     * Exchanges credentials inside an ephemeral cookie session. Remembered sessions are encrypted
     * in DataStore; unchecked sessions remain usable only until this app process ends.
     */
    suspend fun login(username: String, password: String, rememberMe: Boolean = true) {
        if (username.isBlank() || password.isBlank()) {
            mutableState.value = CustomerAuthState.SignedOut("Введите логин и пароль")
            return
        }
        exchangeMutex.withLock {
            mutableState.value = CustomerAuthState.Authenticating
            try {
                val token = withContext(Dispatchers.IO) { nativePkceLogin(username.trim(), password) }
                persistSessionAcrossRestarts = rememberMe
                persist(token.toStoredSession())
                mutableState.value = CustomerAuthState.SignedIn
            } catch (cancelled: CancellationException) {
                mutableState.value = CustomerAuthState.SignedOut()
                throw cancelled
            } catch (failure: CustomerAuthException) {
                mutableState.value = CustomerAuthState.SignedOut(failure.userMessage)
            } catch (_: Throwable) {
                mutableState.value = CustomerAuthState.SignedOut(
                    "Не удалось войти. Проверьте подключение и повторите попытку",
                )
            }
        }
    }

    /** Returns a valid token, serializing refresh-token rotation across concurrent HTTP calls. */
    suspend fun accessToken(forceRefresh: Boolean = false, rejectedToken: String? = null): String? =
        exchangeMutex.withLock {
            val current = cachedSession ?: sessionStore.read()?.also {
                cachedSession = it
                persistSessionAcrossRestarts = true
            } ?: return@withLock null
            if (!forceRefresh && current.isAccessTokenUsable(System.currentTimeMillis())) {
                return@withLock current.accessToken
            }
            if (forceRefresh && rejectedToken != null && current.accessToken != rejectedToken &&
                current.isAccessTokenUsable(System.currentTimeMillis())
            ) {
                return@withLock current.accessToken
            }
            val refresh = current.refreshToken ?: run {
                clear("Сессия завершена. Войдите снова")
                return@withLock null
            }
            try {
                val payload = withContext(Dispatchers.IO) { refresh(refresh) }
                val updated = payload.toStoredSession(fallbackRefreshToken = refresh)
                persist(updated)
                updated.accessToken
            } catch (failure: CustomerAuthException) {
                if (failure.terminal) clear("Сессия завершена. Войдите снова")
                null
            }
        }

    /** Revokes the refresh token when possible and always clears the encrypted local session. */
    suspend fun logout() {
        exchangeMutex.withLock {
            val refresh = cachedSession?.refreshToken
            if (!refresh.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        rawClient.newCall(
                            Request.Builder()
                                .url(configuration.revocationUrl)
                                .post(
                                    FormBody.Builder()
                                        .add("client_id", CUSTOMER_CLIENT_ID)
                                        .add("token", refresh)
                                        .add("token_type_hint", "refresh_token")
                                        .build(),
                                )
                                .build(),
                        ).execute().close()
                    }
                }
            }
            clear(null)
        }
    }

    /** Invalidates a server-rejected session without manufacturing an offline success state. */
    suspend fun invalidate(message: String) = exchangeMutex.withLock { clear(message) }

    private fun nativePkceLogin(username: String, password: String): OAuthTokenPayload {
        val cookies = EphemeralCustomerCookieJar()
        val client = ephemeralClient(cookies)
        val verifier = randomUrlToken(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlToken(32)
        val nonce = randomUrlToken(32)
        try {
            val csrf = fetchCsrf(client)
            val form = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add(csrf.parameterName, csrf.token)
                .build()
            client.newCall(Request.Builder().url(configuration.loginUrl).post(form).build())
                .execute().use { response ->
                    val location = response.redirectLocation()
                        ?: throw CustomerAuthException("Не удалось безопасно выполнить вход")
                    if (location.encodedPath == configuration.loginUrl.encodedPath &&
                        location.queryParameterNames.contains("error")
                    ) {
                        throw CustomerAuthException("Неверный логин или пароль", terminal = true)
                    }
                    if (!location.sameOrigin(configuration.loginUrl)) {
                        throw CustomerAuthException("Сервер вернул недоверенное перенаправление")
                    }
                }
            val authorizationUrl = configuration.authorizeUrl.newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", CUSTOMER_CLIENT_ID)
                .addQueryParameter("redirect_uri", configuration.redirectUri)
                .addQueryParameter("scope", CUSTOMER_SCOPE)
                .addQueryParameter("state", state)
                .addQueryParameter("nonce", nonce)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .build()
            val callback = client.newCall(Request.Builder().url(authorizationUrl).get().build())
                .execute().use { response ->
                    response.redirectLocation()
                        ?: throw CustomerAuthException("Сервер не завершил безопасный вход")
                }
            val expectedCallback = configuration.redirectUri.toHttpUrl()
            if (!callback.sameOrigin(expectedCallback) || callback.encodedPath != expectedCallback.encodedPath ||
                callback.fragment != null || callback.queryParameterValues("state") != listOf(state)
            ) {
                throw CustomerAuthException("Проверка ответа авторизации не пройдена")
            }
            callback.queryParameter("error")?.let { error ->
                throw CustomerAuthException(
                    if (error == "access_denied") "Доступ клиента не разрешён" else "Авторизация отклонена",
                    terminal = true,
                )
            }
            val code = callback.queryParameterValues("code").singleOrNull()?.takeIf(String::isNotBlank)
                ?: throw CustomerAuthException("Код авторизации отсутствует")
            return exchangeCode(code, verifier)
        } finally {
            cookies.clear()
        }
    }

    private fun exchangeCode(code: String, verifier: String): OAuthTokenPayload {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CUSTOMER_CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", configuration.redirectUri)
            .add("code_verifier", verifier)
            .build()
        return tokenRequest(body)
    }

    private fun refresh(refreshToken: String): OAuthTokenPayload = tokenRequest(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CUSTOMER_CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build(),
    )

    private fun tokenRequest(body: FormBody): OAuthTokenPayload {
        rawClient.newCall(Request.Builder().url(configuration.tokenUrl).post(body).build())
            .execute().use { response ->
                if (!response.isSuccessful) {
                    throw CustomerAuthException(
                        "Не удалось обновить безопасную сессию",
                        terminal = response.code in 400..499,
                    )
                }
                return json.decodeFromString(response.body.string())
            }
    }

    private fun fetchCsrf(client: OkHttpClient): CsrfPayload {
        client.newCall(Request.Builder().url(configuration.csrfUrl).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw CustomerAuthException("Не удалось получить защитный токен")
            val body = response.body.string()
            if (body.length > MAX_AUTH_RESPONSE_BYTES) throw CustomerAuthException("Некорректный ответ авторизации")
            return json.decodeFromString(body)
        }
    }

    private fun ephemeralClient(cookies: EphemeralCustomerCookieJar): OkHttpClient = rawClient.newBuilder()
        .cookieJar(cookies)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private suspend fun persist(session: StoredCustomerSession) {
        if (persistSessionAcrossRestarts) {
            sessionStore.write(session)
        } else {
            sessionStore.clear()
        }
        cachedSession = session
    }

    private suspend fun clear(message: String?) {
        cachedSession = null
        persistSessionAcrossRestarts = false
        sessionStore.clear()
        mutableState.value = CustomerAuthState.SignedOut(message)
    }

    private fun OAuthTokenPayload.toStoredSession(fallbackRefreshToken: String? = null) = StoredCustomerSession(
        accessToken = access_token,
        refreshToken = refresh_token ?: fallbackRefreshToken,
        expiresAtEpochMs = System.currentTimeMillis() + expires_in.coerceAtLeast(1) * 1_000,
        scope = scope,
    )

    private fun Response.asAuthFailure(defaultMessage: String): CustomerAuthException {
        val payload = body.string().take(MAX_AUTH_RESPONSE_BYTES)
        val problem = runCatching { json.decodeFromString<ProblemDetails>(payload) }.getOrNull()
        return CustomerAuthException(
            customerProblemMessage(code, problem, defaultMessage),
            terminal = code in 400..499,
        )
    }
}

/** Adds the current Bearer token to customer API requests without logging token material. */
@Singleton
class CustomerBearerInterceptor @Inject constructor(
    private val authRepository: CustomerAuthRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking(Dispatchers.IO) { authRepository.accessToken() }
        val request = if (token.isNullOrBlank()) chain.request() else chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(request)
    }
}

/** Refreshes a rejected token once; repeated 401 responses terminate authentication normally. */
@Singleton
class CustomerTokenAuthenticator @Inject constructor(
    private val authRepository: CustomerAuthRepository,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.responseCount() >= 2) {
            runBlocking(Dispatchers.IO) {
                authRepository.invalidate("Сессия завершена. Войдите снова")
            }
            return null
        }
        val rejected = response.request.header("Authorization")?.removePrefix("Bearer ")
        val refreshed = runBlocking(Dispatchers.IO) {
            authRepository.accessToken(forceRefresh = true, rejectedToken = rejected)
        } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $refreshed").build()
    }
}

/** In-memory cookie jar whose contents are destroyed after one registration or login attempt. */
internal class EphemeralCustomerCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { stored ->
            stored.expiresAt <= now || cookies.any { incoming ->
                incoming.name == stored.name && incoming.domain == stored.domain && incoming.path == stored.path
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

    /** Erases all native-login cookies as soon as the attempt ends. */
    @Synchronized
    fun clear() = cookies.clear()
}

/** Localized authentication error with a flag for invalid/revoked credentials. */
internal class CustomerAuthException(
    val userMessage: String,
    val terminal: Boolean = false,
) : IOException(userMessage)

private val Context.customerSessionDataStore by preferencesDataStore(name = "customer_secure_session")
private val encryptedSessionKey = stringPreferencesKey("oauth_session_v1")

/** Persists only AES-GCM ciphertext; the non-exportable key remains in Android Keystore. */
internal class EncryptedCustomerSessionStore(
    private val context: Context,
    private val json: Json,
) {
    /** Decrypts a valid session or safely treats corrupted local state as signed out. */
    suspend fun read(): StoredCustomerSession? {
        val encoded = context.customerSessionDataStore.data.first()[encryptedSessionKey] ?: return null
        return runCatching { json.decodeFromString<StoredCustomerSession>(decrypt(encoded)) }.getOrNull()
    }

    /** Atomically replaces the encrypted OAuth session. */
    suspend fun write(session: StoredCustomerSession) {
        val encrypted = encrypt(json.encodeToString(session))
        context.customerSessionDataStore.edit { preferences -> preferences[encryptedSessionKey] = encrypted }
    }

    /** Removes the encrypted session after logout or terminal authorization failure. */
    suspend fun clear() {
        context.customerSessionDataStore.edit { preferences -> preferences.remove(encryptedSessionKey) }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(cipherText, Base64.NO_WRAP)}"
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2) { "Malformed encrypted customer session" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
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

private fun String.toRequestBodyJson() = toRequestBody(JSON_MEDIA_TYPE)

private fun Response.redirectLocation(): HttpUrl? = if (code in 300..399) {
    header("Location")?.let(request.url::resolve)
} else {
    null
}

private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

private fun Response.responseCount(): Int {
    var current: Response? = this
    var count = 0
    while (current != null) {
        count += 1
        current = current.priorResponse
    }
    return count
}

private fun randomUrlToken(size: Int): String = ByteArray(size).also(SecureRandom()::nextBytes).let(::base64Url)

private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(
    bytes,
    Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
)

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private const val CUSTOMER_CLIENT_ID = "rwms-customer-android"
private const val CUSTOMER_SCOPE = "openid profile offline_access customer.rental"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "rwms-customer-oauth-v1"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val TOKEN_LEEWAY_MS = 30_000L
private const val MAX_AUTH_RESPONSE_BYTES = 16_384
