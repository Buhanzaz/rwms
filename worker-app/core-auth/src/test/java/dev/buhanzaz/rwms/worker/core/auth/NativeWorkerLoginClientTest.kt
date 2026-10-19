package dev.buhanzaz.rwms.worker.core.auth

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeWorkerLoginClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NativeWorkerLoginClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NativeWorkerLoginClient(server.url("/"), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success sends credentials only to login and returns validated code`() = runTest {
        val request = authorizationRequest()
        enqueueCsrf(token = "csrf-value", parameter = "_custom_csrf")
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "SESSION=after-login; Path=/; HttpOnly")
                .addHeader("Location", "/auth/"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader(
                    "Location",
                    "$CALLBACK?code=worker-code&state=${request.state}",
                ),
        )

        val session = client.login("worker.login", "top secret", request)

        assertThat(session.authorizationResponse.authorizationCode).isEqualTo("worker-code")
        val csrfRequest = server.takeRequest()
        val loginRequest = server.takeRequest()
        val authorizeRequest = server.takeRequest()
        assertThat(csrfRequest.path).isEqualTo("/auth/api/auth/csrf")
        assertThat(loginRequest.path).isEqualTo("/auth/login")
        assertThat(loginRequest.method).isEqualTo("POST")
        assertThat(loginRequest.body.readUtf8()).isEqualTo(
            "username=worker.login&password=top+secret&_custom_csrf=csrf-value",
        )
        assertThat(loginRequest.getHeader("Cookie")).contains("SESSION=before-login")
        assertThat(authorizeRequest.path).startsWith("/auth/oauth2/authorize?")
        assertThat(authorizeRequest.requestUrl?.queryParameter("state")).isEqualTo(request.state)
        assertThat(authorizeRequest.requestUrl?.queryParameter("nonce")).isEqualTo(request.nonce)
        assertThat(authorizeRequest.requestUrl?.queryParameter("code_challenge_method"))
            .isEqualTo("S256")
        assertThat(authorizeRequest.requestUrl?.queryParameter("prompt")).isNull()
        assertThat(authorizeRequest.requestUrl.toString()).doesNotContain("top")
        assertThat(authorizeRequest.getHeader("Cookie")).contains("SESSION=after-login")
        assertThat(authorizeRequest.getHeader("Cookie")).doesNotContain("top secret")
        assertThat(
            session.cookies.loadForRequest(server.url("/")).joinToString(),
        ).doesNotContain("top secret")
    }

    @Test
    fun `bad credentials stop before authorization`() = runTest {
        val request = authorizationRequest()
        enqueueCsrf()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/auth/login?error"),
        )

        val failure = runCatching {
            client.login("worker.login", "wrong", request)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(NativeWorkerLoginException.InvalidCredentials::class.java)
        assertThat((failure as NativeWorkerLoginException).userMessage)
            .isEqualTo("Неверный логин или пароль либо вход для рабочего отключён")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `panel account gets worker-specific message`() = runTest {
        val request = authorizationRequest()
        enqueueSuccessfulFormLogin()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader(
                    "Location",
                    "$CALLBACK?error=access_denied&state=${request.state}",
                ),
        )

        val failure = runCatching {
            client.login("panel.user", "password", request)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(NativeWorkerLoginException.WorkerAccountRequired::class.java)
        assertThat((failure as NativeWorkerLoginException).userMessage)
            .isEqualTo("Используйте логин рабочего из настроек доски")
    }

    @Test
    fun `rejects mismatched state`() = runTest {
        val request = authorizationRequest()
        enqueueSuccessfulFormLogin()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "$CALLBACK?code=value&state=attacker"),
        )

        val failure = runCatching {
            client.login("worker", "password", request)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(NativeWorkerLoginException.ProtocolFailure::class.java)
    }

    @Test
    fun `rejects callback on another host`() = runTest {
        val request = authorizationRequest()
        enqueueSuccessfulFormLogin()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader(
                    "Location",
                    "https://attacker.example/auth/worker/callback" +
                        "?code=value&state=${request.state}",
                ),
        )

        val failure = runCatching {
            client.login("worker", "password", request)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(NativeWorkerLoginException.ProtocolFailure::class.java)
    }

    @Test
    fun `logout refreshes csrf after session rotation then wipes cookies`() = runTest {
        val request = authorizationRequest()
        enqueueSuccessfulFormLogin()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "$CALLBACK?code=value&state=${request.state}"),
        )
        val session = client.login("worker", "password", request)
        enqueueCsrf(token = "csrf-after-login", setSessionCookie = false)
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/auth/login?logout"),
        )

        client.logout(session.cookies)

        repeat(3) { server.takeRequest() }
        val refreshedCsrf = server.takeRequest()
        val logout = server.takeRequest()
        assertThat(refreshedCsrf.path).isEqualTo("/auth/api/auth/csrf")
        assertThat(refreshedCsrf.getHeader("Cookie")).contains("SESSION=after-login")
        assertThat(logout.path).isEqualTo("/auth/logout")
        assertThat(logout.body.readUtf8()).isEqualTo("_csrf=csrf-after-login")
        assertThat(session.cookies.loadForRequest(server.url("/"))).isEmpty()
    }

    private fun enqueueSuccessfulFormLogin() {
        enqueueCsrf()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "SESSION=after-login; Path=/; HttpOnly")
                .addHeader("Location", "/auth/"),
        )
    }

    private fun enqueueCsrf(
        token: String = "csrf-value",
        parameter: String = "_csrf",
        setSessionCookie: Boolean = true,
    ) {
        val response = MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody(
                """{"parameterName":"$parameter","headerName":"X-CSRF-TOKEN","token":"$token"}""",
            )
        if (setSessionCookie) {
            response.addHeader("Set-Cookie", "SESSION=before-login; Path=/; HttpOnly")
        }
        server.enqueue(response)
    }

    private fun authorizationRequest(): AuthorizationRequest {
        val service = AuthorizationServiceConfiguration(
            Uri.parse(server.url("/auth/oauth2/authorize").toString()),
            Uri.parse(server.url("/auth/oauth2/token").toString()),
        )
        return AuthorizationRequest.Builder(
            service,
            WORKER_OAUTH_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(CALLBACK),
        )
            .setScope(WORKER_OAUTH_SCOPE)
            .setState("expected-state")
            .setNonce("expected-nonce")
            .build()
    }
}

private const val CALLBACK = "https://rwms.example.org/auth/worker/callback"
