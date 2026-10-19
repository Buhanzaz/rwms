package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.network.ApiProblemDto
import dev.buhanzaz.rwms.worker.core.network.GatewayProblemException
import java.io.IOException
import org.junit.Test

/** Covers the worker sync outcomes derived from authoritative gateway statuses. */
class WorkerSyncFailurePolicyTest {
    @Test
    fun `authorization and conflict statuses stop background sync with typed outcomes`() {
        assertThat(outcomeFor(401))
            .isEqualTo(WorkerSyncOutcome.AuthenticationRequired("Сессия истекла. Войдите снова."))
        assertThat(outcomeFor(403))
            .isEqualTo(
                WorkerSyncOutcome.UserActionRequired(
                    "Доступ к действию закрыт. Обновите права или обратитесь к руководителю.",
                ),
            )
        assertThat(outcomeFor(409))
            .isEqualTo(
                WorkerSyncOutcome.Conflict(
                    "Данные изменились. Обновите список заданий и повторите действие.",
                ),
            )
    }

    @Test
    fun `only declared temporary statuses request another bounded pass`() {
        assertThat(outcomeFor(429)).isEqualTo(
            WorkerSyncOutcome.Retry("Слишком много запросов. Подождите и повторите попытку."),
        )
        listOf(502, 503, 504).forEach { status ->
            assertThat(outcomeFor(status)).isEqualTo(
                WorkerSyncOutcome.Retry(
                    "RWMS временно недоступен. Проверьте соединение и повторите попытку.",
                ),
            )
        }
        assertThat(outcomeFor(500)).isEqualTo(
            WorkerSyncOutcome.Failed(
                "Не удалось выполнить действие. Обновите данные и повторите попытку.",
            ),
        )
    }

    @Test
    fun `backend problem text never reaches worker sync outcomes`() {
        listOf(401, 403, 409, 429, 500).forEach { status ->
            val outcome = outcomeFor(status)

            assertThat(outcome.reason()).doesNotContain(MALICIOUS_DETAIL)
            assertThat(outcome.reason()).doesNotContain("Internal Server Error")
        }
    }

    @Test
    fun `untyped internal errors use safe fixed sync outcomes`() {
        assertThat(workerSyncOutcomeForUnexpectedFailure(IllegalStateException(INTERNAL_ERROR)))
            .isEqualTo(
                WorkerSyncOutcome.Failed(
                    "Не удалось синхронизировать данные. " +
                        "Обновите список заданий и повторите попытку.",
                ),
            )
        assertThat(workerSyncOutcomeForUnexpectedFailure(IOException(INTERNAL_ERROR)))
            .isEqualTo(
                WorkerSyncOutcome.Retry(
                    "Нет соединения с RWMS. Проверьте сеть и повторите попытку.",
                ),
            )
    }

    private fun outcomeFor(status: Int): WorkerSyncOutcome = workerSyncOutcomeForGatewayProblem(
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

    private fun WorkerSyncOutcome.reason(): String = when (this) {
        WorkerSyncOutcome.Complete -> error("Complete has no failure reason")
        is WorkerSyncOutcome.AuthenticationRequired -> reason
        is WorkerSyncOutcome.UserActionRequired -> reason
        is WorkerSyncOutcome.Conflict -> reason
        is WorkerSyncOutcome.Retry -> reason
        is WorkerSyncOutcome.Deferred -> reason
        is WorkerSyncOutcome.Failed -> reason
    }

    /** Raw failure samples that must never become durable or worker-visible sync text. */
    private companion object {
        const val MALICIOUS_DETAIL =
            "Raw backend detail: java.lang.IllegalStateException at SecretService.kt:42"
        const val INTERNAL_ERROR = "socket closed inside okhttp dispatcher"
    }
}
