package dev.buhanzaz.rwms.driver.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.network.ApiProblemDto
import dev.buhanzaz.rwms.driver.core.network.GatewayProblemException
import java.io.IOException
import org.junit.Test

/** Covers the driver sync outcomes derived from authoritative gateway statuses. */
class DriverSyncFailurePolicyTest {
    @Test
    fun `authorization and conflict statuses stop background sync with typed outcomes`() {
        assertThat(outcomeFor(401))
            .isEqualTo(DriverSyncOutcome.AuthenticationRequired("Сессия истекла. Войдите снова."))
        assertThat(outcomeFor(403))
            .isEqualTo(
                DriverSyncOutcome.UserActionRequired(
                    "Доступ к действию закрыт. Обновите права или обратитесь к логисту.",
                ),
            )
        assertThat(outcomeFor(409))
            .isEqualTo(
                DriverSyncOutcome.Conflict(
                    "Данные изменились. Синхронизируйте список заданий и повторите действие.",
                ),
            )
    }

    @Test
    fun `only declared temporary statuses request another bounded pass`() {
        assertThat(outcomeFor(429)).isEqualTo(
            DriverSyncOutcome.Retry("Слишком много запросов. Подождите и повторите попытку."),
        )
        listOf(502, 503, 504).forEach { status ->
            assertThat(outcomeFor(status)).isEqualTo(
                DriverSyncOutcome.Retry(
                    "RWMS временно недоступен. Проверьте соединение и повторите попытку.",
                ),
            )
        }
        assertThat(outcomeFor(500)).isEqualTo(
            DriverSyncOutcome.Failed(
                "Не удалось выполнить действие. Синхронизируйте данные и повторите попытку.",
            ),
        )
    }

    @Test
    fun `backend problem text never reaches driver sync outcomes`() {
        listOf(401, 403, 409, 429, 500).forEach { status ->
            val outcome = outcomeFor(status)

            assertThat(outcome.reason()).doesNotContain(MALICIOUS_DETAIL)
            assertThat(outcome.reason()).doesNotContain("Internal Server Error")
        }
    }

    @Test
    fun `untyped internal errors use safe fixed sync outcomes`() {
        assertThat(driverSyncOutcomeForUnexpectedFailure(IllegalStateException(INTERNAL_ERROR)))
            .isEqualTo(
                DriverSyncOutcome.Failed(
                    "Не удалось синхронизировать данные. " +
                        "Обновите список заданий и повторите попытку.",
                ),
            )
        assertThat(driverSyncOutcomeForUnexpectedFailure(IOException(INTERNAL_ERROR)))
            .isEqualTo(
                DriverSyncOutcome.Retry(
                    "Нет соединения с RWMS. Проверьте сеть и повторите попытку.",
                ),
            )
    }

    private fun outcomeFor(status: Int): DriverSyncOutcome = driverSyncOutcomeForGatewayProblem(
        GatewayProblemException(
            ApiProblemDto(
                type = "about:blank",
                title = "Internal Server Error",
                status = status,
                detail = MALICIOUS_DETAIL,
                code = "HTTP_$status",
            ),
        ),
    )

    private fun DriverSyncOutcome.reason(): String = when (this) {
        DriverSyncOutcome.Complete -> error("Complete has no failure reason")
        is DriverSyncOutcome.AuthenticationRequired -> reason
        is DriverSyncOutcome.UserActionRequired -> reason
        is DriverSyncOutcome.Conflict -> reason
        is DriverSyncOutcome.Retry -> reason
        is DriverSyncOutcome.Deferred -> reason
        is DriverSyncOutcome.Failed -> reason
    }

    /** Raw failure samples that must never become durable or driver-visible sync text. */
    private companion object {
        const val MALICIOUS_DETAIL =
            "Raw backend detail: java.lang.IllegalStateException at SecretService.kt:42"
        const val INTERNAL_ERROR = "socket closed inside okhttp dispatcher"
    }
}
