package dev.buhanzaz.rwms.driver.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies status classification and safe fallback behavior at the driver gateway boundary. */
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
                "Доступ к действию закрыт. Обновите права или обратитесь к логисту.",
            (409 to "UNKNOWN_CONFLICT") to
                "Данные изменились. Синхронизируйте список заданий и повторите действие.",
            (429 to "HTTP_429") to
                "Слишком много запросов. Подождите и повторите попытку.",
            (503 to "TASK_BOARD_DEPENDENCY_UNAVAILABLE") to
                "RWMS временно недоступен. Проверьте соединение и повторите попытку.",
            (500 to "UNEXPECTED_FAILURE") to
                "Не удалось выполнить действие. Синхронизируйте данные и повторите попытку.",
        )

        expected.forEach { (statusAndCode, message) ->
            assertThat(gatewayProblemUserMessage(problem(statusAndCode.first, statusAndCode.second)))
                .isEqualTo(message)
        }
    }

    @Test
    fun `problem codes explain stale lease capacity window shift and route safely`() {
        val expected = mapOf(
            "DRIVER_OFFLINE_LEASE_EXPIRED" to
                "Офлайн-допуск истёк. Подключитесь к сети и синхронизируйте задания.",
            "ENTRY_VERSION_CONFLICT" to
                "Данные задания изменились. Синхронизируйте список и повторите действие.",
            "VEHICLE_CAPACITY_EXCEEDED" to
                "Действие невозможно: превышена вместимость транспорта. Обратитесь к логисту.",
            "DELIVERY_WINDOW_VIOLATION" to
                "Действие не помещается во временное окно. Обратитесь к логисту.",
            "DRIVER_SHIFT_CLOSED" to
                "Состояние смены изменилось. Синхронизируйте данные и повторите действие.",
            "ROUTE_ORDER_INVALID" to
                "Маршрут изменился или действие нарушает его порядок. Синхронизируйте задания.",
        )

        expected.forEach { (code, message) ->
            assertThat(gatewayProblemUserMessage(problem(status = 409, code = code)))
                .isEqualTo(message)
        }
    }

    @Test
    fun `trusted short Russian domain message is preserved only for an approved code`() {
        val trusted = problem(
            status = 409,
            code = "TASK_BOARD_CONFLICT",
            detail = "Сначала завершите предыдущий этап маршрута.",
        )
        val untrustedCode = trusted.copy(code = "UNKNOWN_CONFLICT")
        val technical = trusted.copy(
            detail = "Ошибка SQL: java.lang.IllegalStateException в DriverShiftService.java:42",
        )

        assertThat(gatewayProblemUserMessage(trusted))
            .isEqualTo("Сначала завершите предыдущий этап маршрута.")
        assertThat(gatewayProblemUserMessage(untrustedCode))
            .isEqualTo("Данные изменились. Синхронизируйте список заданий и повторите действие.")
        assertThat(gatewayProblemUserMessage(technical))
            .isEqualTo("Данные изменились. Синхронизируйте список заданий и повторите действие.")
    }

    @Test
    fun `exception message never exposes backend title detail or body`() {
        val problem = problem(
            status = 500,
            code = "UNKNOWN_BACKEND_FAILURE",
            title = "Internal Server Error",
            detail = MALICIOUS_DETAIL,
        )

        val error = GatewayProblemException(problem)

        assertThat(error.message)
            .isEqualTo("Не удалось выполнить действие. Синхронизируйте данные и повторите попытку.")
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
    fun `driver message keeps typed domain mapping through a wrapper`() {
        val failure = IllegalStateException(
            "wrapper must stay internal",
            GatewayProblemException(problem(status = 409, code = "DELIVERY_WINDOW_VIOLATION")),
        )

        assertThat(failure.toDriverUserMessage("Не удалось обновить задание"))
            .isEqualTo("Действие не помещается во временное окно. Обратитесь к логисту.")
    }

    @Test
    fun `driver message classifies only proven transport outage`() {
        assertThat(IOException("Failed to connect to internal-gateway:8080").toDriverUserMessage(FALLBACK))
            .isEqualTo("Нет связи с RWMS. Проверьте интернет и повторите действие.")
        assertThat(
            IllegalStateException("wrapper", GatewayUnavailableException()).toDriverUserMessage(FALLBACK),
        ).isEqualTo("Нет связи с RWMS. Проверьте интернет и повторите действие.")
    }

    @Test
    fun `driver message rejects arbitrary English and Russian exception text`() {
        val failures = listOf(
            IllegalStateException("manual change invalid: cycle capacity window shift limits"),
            IllegalArgumentException("Внутренняя ошибка назначения водителя"),
        )

        failures.forEach { failure ->
            assertThat(failure.toDriverUserMessage(FALLBACK)).isEqualTo(FALLBACK)
        }
    }

    @Test
    fun `driver message never renders HTTP SQL JSON service class or path details`() {
        val failures = listOf(
            RuntimeException("RMS Logistics Service returned HTTP 400"),
            RuntimeException("SQL SELECT secret FROM users"),
            RuntimeException("{\"detail\":\"private response\"}"),
            RuntimeException("dev.buhanzaz.InternalService at /api/internal/tasks"),
            RuntimeException("java.lang.IllegalStateException at DriverShiftViewModel.kt:401"),
        )

        failures.forEach { failure ->
            assertThat(failure.toDriverUserMessage(FALLBACK)).isEqualTo(FALLBACK)
        }
    }

    @Test
    fun `driver message propagates cancellation instead of creating feedback`() {
        val cancellation = CancellationException("screen closed")

        val thrown = assertThrows(CancellationException::class.java) {
            IllegalStateException("wrapper", cancellation).toDriverUserMessage(FALLBACK)
        }

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

    /** Malicious server-controlled text used to prove the driver presentation boundary is closed. */
    private companion object {
        const val FALLBACK = "Не удалось выполнить операцию. Повторите попытку."
        const val MALICIOUS_DETAIL =
            "Raw backend detail: java.lang.IllegalStateException; SELECT secret FROM users; {json}"
    }
}
