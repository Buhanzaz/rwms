package dev.buhanzaz.rwms.manager.auth

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ManagerAuthConfigurationTest {
    @Test
    fun `all login endpoints stay on the public gateway origin`() {
        val configuration = ManagerAuthConfiguration(
            Uri.parse("https://rwms.example.org"),
        )

        assertThat(configuration.authorizationEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/oauth2/authorize")
        assertThat(configuration.tokenEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/oauth2/token")
        assertThat(configuration.csrfEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/api/auth/csrf")
        assertThat(configuration.loginEndpoint.toString())
            .isEqualTo("https://rwms.example.org/auth/login")
        assertThat(configuration.redirectUri.toString())
            .isEqualTo("https://rwms.example.org/auth/manager/callback")
    }

    @Test
    fun `authorization request uses manager client PKCE state and nonce`() {
        val configuration = ManagerAuthConfiguration(
            Uri.parse("https://rwms.example.org"),
        )

        val first = createManagerAuthorizationRequest(configuration)
        val second = createManagerAuthorizationRequest(configuration)

        assertThat(first.clientId).isEqualTo("rwms-manager-android")
        assertThat(first.codeVerifier).isNotEmpty()
        assertThat(first.codeVerifierChallenge).isNotEmpty()
        assertThat(first.codeVerifierChallengeMethod).isEqualTo("S256")
        assertThat(first.state).isNotEmpty()
        assertThat(first.nonce).isNotEmpty()
        assertThat(first.scope).contains("rwms.write")
        assertThat(second.state).isNotEqualTo(first.state)
        assertThat(second.nonce).isNotEqualTo(first.nonce)
    }

    @Test
    fun `forced refresh reuses a valid token already rotated by another request`() {
        assertThat(
            reusableManagerAccessToken(
                accessToken = "new-token",
                needsTokenRefresh = false,
                forceRefresh = true,
                rejectedAccessToken = "old-token",
            ),
        ).isEqualTo("new-token")
        assertThat(
            reusableManagerAccessToken(
                accessToken = "old-token",
                needsTokenRefresh = false,
                forceRefresh = true,
                rejectedAccessToken = "old-token",
            ),
        ).isNull()
        assertThat(
            reusableManagerAccessToken(
                accessToken = "expired-token",
                needsTokenRefresh = true,
                forceRefresh = false,
                rejectedAccessToken = null,
            ),
        ).isNull()
    }

    @Test
    fun `foreground and worker refreshes share one process rotation lock`() = runTest {
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        List(2) {
            async {
                ManagerProcessRefreshCoordinator.withLock {
                    val now = active.incrementAndGet()
                    maximum.accumulateAndGet(now) { left, right -> maxOf(left, right) }
                    delay(10)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertThat(maximum.get()).isEqualTo(1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non https origin`() {
        ManagerAuthConfiguration(Uri.parse("http://example.org"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an origin with path`() {
        ManagerAuthConfiguration(Uri.parse("https://example.org/api"))
    }
}
