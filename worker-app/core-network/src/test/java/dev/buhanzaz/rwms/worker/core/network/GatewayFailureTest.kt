package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/** Verifies status classification and safe fallback behavior at the worker gateway boundary. */
class GatewayFailureTest {
    @Test
    fun `gateway statuses have one explicit recovery disposition`() {
        assertThat(gatewayFailureDisposition(401))
            .isEqualTo(GatewayFailureDisposition.AUTHENTICATION_REQUIRED)
        assertThat(gatewayFailureDisposition(403))
            .isEqualTo(GatewayFailureDisposition.USER_ACTION_REQUIRED)
        assertThat(gatewayFailureDisposition(409)).isEqualTo(GatewayFailureDisposition.CONFLICT)
        assertThat(gatewayFailureDisposition(429)).isEqualTo(GatewayFailureDisposition.RETRYABLE)
        assertThat(gatewayFailureDisposition(502)).isEqualTo(GatewayFailureDisposition.RETRYABLE)
        assertThat(gatewayFailureDisposition(503)).isEqualTo(GatewayFailureDisposition.RETRYABLE)
        assertThat(gatewayFailureDisposition(504)).isEqualTo(GatewayFailureDisposition.RETRYABLE)
        assertThat(gatewayFailureDisposition(500)).isEqualTo(GatewayFailureDisposition.TERMINAL)
    }

    @Test
    fun `malformed Problem Details keeps the response status typed and safe`() {
        val problem = "<html>gateway failure</html>".toResponseBody().toApiProblem(Json, 503)

        assertThat(problem.status).isEqualTo(503)
        assertThat(problem.code).isEqualTo("HTTP_503")
        assertThat(problem.detail).isNull()
    }

    @Test
    fun `response status wins over an invalid Problem Details status field`() {
        val problem = """
            {"type":"about:blank","title":"Unauthorized","status":500,"code":"AUTH_REQUIRED"}
        """.trimIndent().toResponseBody().toApiProblem(Json, 401)

        assertThat(problem.status).isEqualTo(401)
        assertThat(problem.code).isEqualTo("AUTH_REQUIRED")
    }

    @Test
    fun `only proven transport exceptions qualify for automatic retry`() {
        assertThat(IOException("offline").isProvenGatewayTransportFailure()).isTrue()
        assertThat(GatewayUnavailableException().isProvenGatewayTransportFailure()).isTrue()
        assertThat(IllegalStateException("bad payload").isProvenGatewayTransportFailure()).isFalse()
        assertThat(CancellationException("cancelled").isProvenGatewayTransportFailure()).isFalse()
        assertThat(
            CancellationException("cancelled").apply { initCause(IOException("offline")) }
                .isProvenGatewayTransportFailure(),
        ).isFalse()
        assertThat(
            CancellationException("cancelled").apply { initCause(GatewayUnavailableException()) }
                .isProvenGatewayTransportFailure(),
        ).isFalse()
    }
}
