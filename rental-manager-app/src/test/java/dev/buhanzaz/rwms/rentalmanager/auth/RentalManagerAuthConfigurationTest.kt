package dev.buhanzaz.rwms.rentalmanager.auth

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import net.openid.appauth.AuthorizationRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RentalManagerAuthConfigurationTest {
    private val configuration = RentalManagerAuthConfiguration(
        Uri.parse("https://rwms.example.org"),
    )

    @Test
    fun `all login endpoints stay on the public gateway origin`() {
        assertThat(configuration.authorizationEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/oauth2/authorize")
        assertThat(configuration.tokenEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/oauth2/token")
        assertThat(configuration.csrfEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/api/auth/csrf")
        assertThat(configuration.loginEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/login")
        assertThat(configuration.redirectUri.toString())
            .isEqualTo("https://rwms.example.org/auth/rental-manager/callback")
    }

    @Test
    fun `authorization request uses dedicated exact scope PKCE state and nonce`() {
        val first = createRentalManagerAuthorizationRequest(configuration)
        val second = createRentalManagerAuthorizationRequest(configuration)

        assertThat(first.clientId).isEqualTo("rwms-rental-manager-android")
        assertThat(first.scope).isEqualTo("openid profile offline_access rental.manage")
        assertThat(first.codeVerifier).isNotEmpty()
        assertThat(first.codeVerifierChallenge).isNotEmpty()
        assertThat(first.codeVerifierChallengeMethod).isEqualTo("S256")
        assertThat(first.state).isNotEmpty()
        assertThat(first.nonce).isNotEmpty()
        assertThat(second.state).isNotEqualTo(first.state)
        assertThat(second.nonce).isNotEqualTo(first.nonce)
    }

    @Test
    fun `callback accepts only exact origin path state and one code`() {
        val request = createRentalManagerAuthorizationRequest(configuration)
        val state = requireNotNull(request.state)

        val response = parseAuthorizationCallback(
            request,
            Uri.parse(
                "https://rwms.example.org/auth/rental-manager/callback?code=code-1&state=$state",
            ),
        )

        assertThat(response.authorizationCode).isEqualTo("code-1")
    }

    @Test
    fun `callback rejects wrong origin fragment and duplicated state`() {
        val request = createRentalManagerAuthorizationRequest(configuration)
        val state = requireNotNull(request.state)
        val callbacks = listOf(
            "https://attacker.example/auth/rental-manager/callback?code=x&state=$state",
            "https://rwms.example.org/auth/rental-manager/callback?code=x&state=$state#fragment",
            "https://rwms.example.org/auth/rental-manager/callback?code=x&state=$state&state=$state",
            "https://rwms.example.org/auth/rental-manager/callback?code=x&code=y&state=$state",
            "https://rwms.example.org/auth/rental-manager/callback?code=x&state=attacker",
        )

        callbacks.forEach { callback ->
            assertThat(
                runCatching {
                    parseAuthorizationCallback(request, Uri.parse(callback))
                }.exceptionOrNull(),
            ).isInstanceOf(NativeRentalManagerLoginException.ProtocolFailure::class.java)
        }
    }

    @Test
    fun `forced refresh reuses only a token rotated by another request`() {
        assertThat(
            reusableRentalManagerAccessToken(
                accessToken = "new-token",
                needsTokenRefresh = false,
                forceRefresh = true,
                rejectedAccessToken = "old-token",
            ),
        ).isEqualTo("new-token")
        assertThat(
            reusableRentalManagerAccessToken(
                accessToken = "old-token",
                needsTokenRefresh = false,
                forceRefresh = true,
                rejectedAccessToken = "old-token",
            ),
        ).isNull()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non https origin`() {
        RentalManagerAuthConfiguration(Uri.parse("http://rwms.example.org"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects origin with path`() {
        RentalManagerAuthConfiguration(Uri.parse("https://rwms.example.org/api"))
    }
}
