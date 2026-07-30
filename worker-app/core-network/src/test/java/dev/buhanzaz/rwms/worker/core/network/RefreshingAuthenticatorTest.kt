package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test

class RefreshingAuthenticatorTest {
    @Test
    fun `retries unauthorized gateway call with a rotated access token`() {
        val credentials = FakeCredentials(
            currentToken = "expired",
            refreshResult = TokenRefreshResult.Refreshed("rotated"),
        )
        val request = RefreshingAuthenticator(credentials).authenticate(null, unauthorized("expired"))

        assertThat(request?.header("Authorization")).isEqualTo("Bearer rotated")
        assertThat(credentials.refreshCalls).isEqualTo(1)
        assertThat(credentials.cleared).isFalse()
    }

    @Test
    fun `invalid refresh grant clears disabled or revoked worker session`() {
        val credentials = FakeCredentials(
            currentToken = "expired",
            refreshResult = TokenRefreshResult.InvalidGrant,
        )
        val request = RefreshingAuthenticator(credentials).authenticate(null, unauthorized("expired"))

        assertThat(request).isNull()
        assertThat(credentials.refreshCalls).isEqualTo(1)
        assertThat(credentials.cleared).isTrue()
    }

    private fun unauthorized(token: String): Response = Response.Builder()
        .request(
            Request.Builder()
                .url("https://rwms.example.org/api/task-board/worker/v1/context")
                .header("Authorization", "Bearer $token")
                .build(),
        )
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Unauthorized")
        .build()

    private class FakeCredentials(
        private var currentToken: String?,
        private val refreshResult: TokenRefreshResult,
    ) : SessionCredentialStore {
        var refreshCalls = 0
        var cleared = false

        override suspend fun currentAccessToken(): String? = currentToken

        override suspend fun refreshAccessToken(): TokenRefreshResult {
            refreshCalls += 1
            if (refreshResult is TokenRefreshResult.Refreshed) {
                currentToken = refreshResult.accessToken
            }
            return refreshResult
        }

        override suspend fun clearSession() {
            cleared = true
            currentToken = null
        }
    }
}
