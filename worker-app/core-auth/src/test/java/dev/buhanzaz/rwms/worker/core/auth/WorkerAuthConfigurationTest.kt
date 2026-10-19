package dev.buhanzaz.rwms.worker.core.auth

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WorkerAuthConfigurationTest {
    @Test
    fun `uses only same host https public routes`() {
        val configuration = WorkerAuthConfiguration(
            publicBaseUrl = Uri.parse("https://rwms.example.org"),
        )

        assertThat(configuration.authorizationEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/oauth2/authorize")
        assertThat(configuration.csrfEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/api/auth/csrf")
        assertThat(configuration.loginEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/login")
        assertThat(configuration.logoutEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/logout")
        assertThat(configuration.revocationEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/oauth2/revoke")
        assertThat(configuration.redirectUri.toString())
            .isEqualTo("https://rwms.example.org/auth/worker/callback")
    }

    @Test
    fun `native authorization uses fresh nonce state PKCE and no browser prompt`() {
        val configuration = WorkerAuthConfiguration(
            publicBaseUrl = Uri.parse("https://rwms.example.org"),
        )

        val first = createNativeWorkerAuthorizationRequest(configuration)
        val second = createNativeWorkerAuthorizationRequest(configuration)

        assertThat(first.prompt).isNull()
        assertThat(first.state).isNotEmpty()
        assertThat(first.nonce).isNotEmpty()
        assertThat(first.codeVerifier).isNotEmpty()
        assertThat(first.codeVerifierChallenge).isNotEmpty()
        assertThat(first.codeVerifierChallengeMethod).isEqualTo("S256")
        assertThat(first.toUri().getQueryParameter("prompt")).isNull()
        assertThat(second.state).isNotEqualTo(first.state)
        assertThat(second.nonce).isNotEqualTo(first.nonce)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects gateway with an extra path`() {
        WorkerAuthConfiguration(
            publicBaseUrl = Uri.parse("https://rwms.example.org/other"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects gateway with a query`() {
        WorkerAuthConfiguration(
            publicBaseUrl = Uri.parse("https://rwms.example.org?other=true"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non https gateway`() {
        WorkerAuthConfiguration(
            publicBaseUrl = Uri.parse("http://localhost:8080"),
        )
    }
}
