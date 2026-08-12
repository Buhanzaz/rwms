package dev.buhanzaz.rwms.driver.core.auth

import android.net.Uri
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Owns the driver OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
internal sealed class NativeDriverLoginException(
    val userMessage: String,
) : IOException(userMessage) {
    /** Credentials were rejected without exposing authorization-server details. */
    class InvalidCredentials :
        NativeDriverLoginException("Неверный логин или пароль либо вход для водителя отключён")

    /** The account is not eligible for the driver principal contract. */
    class DriverAccountRequired :
        NativeDriverLoginException("Используйте логин водителя из настроек доски")

    /** The native PKCE response violated the expected same-origin protocol. */
    class ProtocolFailure :
        NativeDriverLoginException("Не удалось безопасно выполнить вход. Повторите попытку")
}

/**
 * Owns the driver OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
internal data class NativeDriverLoginSession(
    val authorizationResponse: AuthorizationResponse,
    val cookies: EphemeralCookieJar,
)

/**
 * Performs the authorization server's driver login ceremony without a browser.
 *
 * Credentials are placed only in the one form request body. The client has no
 * logging interceptor and its cookie jar is memory-only, scoped to one login
 * session, and explicitly wiped at logout or on every failed attempt.
 */
@Singleton
class NativeDriverLoginClient private constructor(
    private val csrfEndpoint: HttpUrl,
    private val loginEndpoint: HttpUrl,
    private val logoutEndpoint: HttpUrl,
    private val authorizationEndpoint: Uri,
    private val clientTemplate: OkHttpClient,
) {
    @Inject
    constructor(
        configuration: DriverAuthConfiguration,
        @NativeLoginClient clientTemplate: OkHttpClient,
    ) : this(
        csrfEndpoint = configuration.csrfEndpoint.toString().toHttpUrl(),
        loginEndpoint = configuration.loginEndpoint.toString().toHttpUrl(),
        logoutEndpoint = configuration.logoutEndpoint.toString().toHttpUrl(),
        authorizationEndpoint = configuration.authorizationEndpoint,
        clientTemplate = clientTemplate,
    )

    internal constructor(
        publicBaseUrl: HttpUrl,
        clientTemplate: OkHttpClient,
    ) : this(
        csrfEndpoint = requireNotNull(publicBaseUrl.resolve("/auth/api/auth/csrf")),
        loginEndpoint = requireNotNull(publicBaseUrl.resolve("/auth/login")),
        logoutEndpoint = requireNotNull(publicBaseUrl.resolve("/auth/logout")),
        authorizationEndpoint = Uri.parse(
            requireNotNull(publicBaseUrl.resolve("/auth/oauth2/authorize")).toString(),
        ),
        clientTemplate = clientTemplate,
    )

    internal suspend fun login(
        username: String,
        password: String,
        authorizationRequest: AuthorizationRequest,
    ): NativeDriverLoginSession {
        if (authorizationRequest.configuration.authorizationEndpoint != authorizationEndpoint) {
            throw NativeDriverLoginException.ProtocolFailure()
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
            val response = authorize(client, authorizationRequest)
            return NativeDriverLoginSession(response, cookies)
        } catch (failure: Throwable) {
            cookies.clear()
            throw failure
        }
    }

    internal suspend fun logout(cookies: EphemeralCookieJar) {
        val client = clientTemplate.newBuilder()
            .cookieJar(cookies)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        try {
            // Spring Security rotates the session at login, invalidating the
            // original CSRF token. Always request one tied to the live session.
            val csrf = fetchCsrf(client)
            val body = FormBody.Builder()
                .add(csrf.parameterName, csrf.token)
                .build()
            client.newCall(
                Request.Builder()
                    .url(logoutEndpoint)
                    .post(body)
                    .build(),
            ).execute().use { /* Logout is best effort; local state is authoritative here. */ }
        } finally {
            cookies.clear()
        }
    }

    private fun fetchCsrf(client: OkHttpClient): CsrfToken {
        val response = client.newCall(Request.Builder().url(csrfEndpoint).get().build()).execute()
        response.use {
            if (!it.isSuccessful) throw NativeDriverLoginException.ProtocolFailure()
            val payload = it.body.string()
            if (payload.length > MAX_CSRF_RESPONSE_LENGTH) {
                throw NativeDriverLoginException.ProtocolFailure()
            }
            val token = runCatching { JSON.decodeFromString<CsrfToken>(payload) }
                .getOrElse { throw NativeDriverLoginException.ProtocolFailure() }
            if (token.parameterName.isBlank() || token.token.isBlank()) {
                throw NativeDriverLoginException.ProtocolFailure()
            }
            return token
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
        client.newCall(
            Request.Builder()
                .url(loginEndpoint)
                .post(body)
                .build(),
        ).execute().use { response ->
            val location = response.redirectLocationOrNull()
                ?: throw NativeDriverLoginException.ProtocolFailure()
            if (!location.hasSameOrigin(loginEndpoint)) {
                throw NativeDriverLoginException.ProtocolFailure()
            }
            if (location.encodedPath == loginEndpoint.encodedPath &&
                location.queryParameterNames.contains("error")
            ) {
                throw NativeDriverLoginException.InvalidCredentials()
            }
        }
    }

    private fun authorize(
        client: OkHttpClient,
        authorizationRequest: AuthorizationRequest,
    ): AuthorizationResponse {
        val response = client.newCall(
            Request.Builder()
                .url(authorizationRequest.toUri().toString())
                .get()
                .build(),
        ).execute()
        response.use {
            if (it.code !in 300..399) {
                throw NativeDriverLoginException.ProtocolFailure()
            }
            val location = it.header("Location")
                ?.let(Uri::parse)
                ?: throw NativeDriverLoginException.ProtocolFailure()
            return parseAuthorizationCallback(authorizationRequest, location)
        }
    }
}

/**
 * Owns the driver OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
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
        throw NativeDriverLoginException.ProtocolFailure()
    }

    val expectedState = request.state
    val states = callback.getQueryParameters("state")
    if (expectedState.isNullOrBlank() || states.size != 1 || states.single() != expectedState) {
        throw NativeDriverLoginException.ProtocolFailure()
    }

    val errors = callback.getQueryParameters("error")
    if (errors.size > 1) throw NativeDriverLoginException.ProtocolFailure()
    if (errors.singleOrNull() == "access_denied") {
        throw NativeDriverLoginException.DriverAccountRequired()
    }
    if (errors.isNotEmpty()) throw NativeDriverLoginException.ProtocolFailure()

    val codes = callback.getQueryParameters("code")
    if (codes.size != 1 || codes.single().isBlank()) {
        throw NativeDriverLoginException.ProtocolFailure()
    }
    return AuthorizationResponse.Builder(request)
        .setAuthorizationCode(codes.single())
        .build()
}

private fun Response.redirectLocationOrNull(): HttpUrl? {
    if (code !in 300..399) return null
    return header("Location")?.let(request.url::resolve)
}

private fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

@Serializable
/**
 * Owns the driver OAuth/session boundary. Credentials and cookies remain client-local and never grant server authorization.
 */
private data class CsrfToken(
    val parameterName: String,
    val token: String,
)

private val JSON = Json { ignoreUnknownKeys = true }
private const val MAX_CSRF_RESPONSE_LENGTH = 16_384
