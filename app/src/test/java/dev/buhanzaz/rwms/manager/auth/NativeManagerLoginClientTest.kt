package dev.buhanzaz.rwms.manager.auth

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
class NativeManagerLoginClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NativeManagerLoginClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NativeManagerLoginClient(server.url("/"), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `credentials are sent once and never enter authorization url`() = runTest {
        val request = request()
        enqueueCsrf()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "SESSION=authorized; Path=/; HttpOnly")
                .addHeader("Location", "/auth/"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "$CALLBACK?code=code&state=${request.state}"),
        )

        val session = client.login("admin", "top secret", request)

        assertThat(session.authorizationResponse.authorizationCode).isEqualTo("code")
        server.takeRequest()
        val login = server.takeRequest()
        val authorize = server.takeRequest()
        assertThat(login.path).isEqualTo("/auth/login")
        assertThat(login.body.readUtf8())
            .isEqualTo("username=admin&password=top+secret&_csrf=csrf")
        assertThat(authorize.path).startsWith("/auth/oauth2/authorize?")
        assertThat(authorize.requestUrl.toString()).doesNotContain("top")
        assertThat(authorize.requestUrl?.queryParameter("code_challenge_method"))
            .isEqualTo("S256")
    }

    @Test
    fun `access denial receives manager specific message`() = runTest {
        val request = request()
        enqueueCsrf()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/auth/"))
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "$CALLBACK?error=access_denied&state=${request.state}"),
        )

        val failure = runCatching {
            client.login("viewer", "password", request)
        }.exceptionOrNull()

        assertThat(failure)
            .isInstanceOf(NativeManagerLoginException.ManagerAccessRequired::class.java)
        assertThat((failure as NativeManagerLoginException).userMessage)
            .contains("Доступ к приложению не разрешён")
    }

    @Test
    fun `callback with another state is rejected`() = runTest {
        val request = request()
        enqueueCsrf()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/auth/"))
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "$CALLBACK?code=code&state=attacker"),
        )

        val failure = runCatching {
            client.login("admin", "password", request)
        }.exceptionOrNull()

        assertThat(failure)
            .isInstanceOf(NativeManagerLoginException.ProtocolFailure::class.java)
    }

    private fun enqueueCsrf() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "SESSION=initial; Path=/; HttpOnly")
                .setBody(
                    """{"parameterName":"_csrf","headerName":"X-CSRF-TOKEN","token":"csrf"}""",
                ),
        )
    }

    private fun request(): AuthorizationRequest {
        val service = AuthorizationServiceConfiguration(
            Uri.parse(server.url("/auth/oauth2/authorize").toString()),
            Uri.parse(server.url("/auth/oauth2/token").toString()),
        )
        return AuthorizationRequest.Builder(
            service,
            "rwms-manager-android",
            ResponseTypeValues.CODE,
            Uri.parse(CALLBACK),
        )
            .setScope("openid profile offline_access rwms.read rwms.write warehouse.read")
            .setState("expected-state")
            .setNonce("expected-nonce")
            .build()
    }
}

private const val CALLBACK = "https://rwms.example.org/auth/manager/callback"
