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
    fun `known dispositions map to fixed action oriented Russian messages`() {
        val expected = mapOf(
            (401 to "TASK_BOARD_UNAUTHORIZED") to "Сессия истекла. Войдите снова.",
            (403 to "TASK_BOARD_FORBIDDEN") to
                "Доступ к действию закрыт. Обновите права или обратитесь к руководителю.",
            (409 to "TASK_BOARD_CONFLICT") to
                "Данные изменились. Обновите список заданий и повторите действие.",
            (429 to "HTTP_429") to
                "Слишком много запросов. Подождите и повторите попытку.",
            (503 to "TASK_BOARD_DEPENDENCY_UNAVAILABLE") to
                "RWMS временно недоступен. Проверьте соединение и повторите попытку.",
            (500 to "UNEXPECTED_FAILURE") to
                "Не удалось выполнить действие. Обновите данные и повторите попытку.",
        )

        expected.forEach { (statusAndCode, message) ->
            assertThat(gatewayProblemUserMessage(problem(statusAndCode.first, statusAndCode.second)))
                .isEqualTo(message)
        }
    }

    @Test
    fun `known problem codes refine the safe message without rendering backend text`() {
        assertThat(gatewayProblemUserMessage(problem(409, "MEDIA_UPLOAD_EXPIRED")))
            .isEqualTo("Срок загрузки фотографии истёк. Запустите синхронизацию ещё раз.")
        assertThat(gatewayProblemUserMessage(problem(404, "MEDIA_NOT_FOUND")))
            .isEqualTo("Данные больше недоступны. Обновите список заданий.")
        assertThat(gatewayProblemUserMessage(problem(400, "MEDIA_INVALID_REQUEST")))
            .isEqualTo("Проверьте данные и повторите действие.")
    }

    @Test
    fun `exception message never exposes malicious title or detail`() {
        val problem = problem(
            status = 418,
            code = "UNKNOWN_BACKEND_FAILURE",
            title = "Internal Server Error",
            detail = MALICIOUS_DETAIL,
        )

        val error = GatewayProblemException(problem)

        assertThat(error.message)
            .isEqualTo("Не удалось выполнить действие. Обновите данные и повторите попытку.")
        assertThat(error.message).doesNotContain("Internal Server Error")
        assertThat(error.message).doesNotContain(MALICIOUS_DETAIL)
        assertThat(error.problem).isSameInstanceAs(problem)
        assertThat(error.problem.detail).isEqualTo(MALICIOUS_DETAIL)
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

    @Test
    fun `safe worker message keeps typed problem semantics through a wrapper`() {
        val typed = GatewayProblemException(problem(409, "MEDIA_UPLOAD_EXPIRED"))
        val wrapped = IllegalStateException(MALICIOUS_DETAIL, typed)

        assertThat(wrapped.safeWorkerUserMessage("Не удалось открыть экран."))
            .isEqualTo("Срок загрузки фотографии истёк. Запустите синхронизацию ещё раз.")
    }

    @Test
    fun `safe worker message classifies transport outage without exposing its text`() {
        val error = IOException("HTTP 502 from internal-media-service at /api/internal/media")

        assertThat(error.safeWorkerUserMessage("Не удалось открыть экран."))
            .isEqualTo("Нет соединения с RWMS. Проверьте сеть и повторите попытку.")
    }

    @Test
    fun `safe worker message uses actionable fallback for arbitrary exception`() {
        val fallback = "Не удалось открыть фотографию. Повторите попытку."

        val message = IllegalStateException(MALICIOUS_DETAIL).safeWorkerUserMessage(fallback)

        assertThat(message).isEqualTo(fallback)
        assertThat(message).doesNotContain(MALICIOUS_DETAIL)
    }

    @Test
    fun `safe worker message rethrows nested cancellation`() {
        val cancellation = CancellationException("cancelled")
        val wrapped = IllegalStateException(MALICIOUS_DETAIL, cancellation)

        val thrown = runCatching {
            wrapped.safeWorkerUserMessage("Не удалось выполнить действие.")
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancellation)
    }

    private fun problem(
        status: Int,
        code: String,
        title: String = "Raw backend title",
        detail: String = MALICIOUS_DETAIL,
    ) = ApiProblemDto(
        type = "about:blank",
        title = title,
        status = status,
        detail = detail,
        code = code,
    )

    /** Malicious server-controlled text used to prove the presentation boundary is closed. */
    private companion object {
        const val MALICIOUS_DETAIL =
            "Internal stack trace: SELECT secret FROM users; <script>alert('leak')</script>"
    }
}
