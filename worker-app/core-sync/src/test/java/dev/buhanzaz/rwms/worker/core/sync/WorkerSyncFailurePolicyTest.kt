package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.network.ApiProblemDto
import dev.buhanzaz.rwms.worker.core.network.GatewayProblemException
import org.junit.Test

/** Covers the worker sync outcomes derived from authoritative gateway statuses. */
class WorkerSyncFailurePolicyTest {
    @Test
    fun `authorization and conflict statuses stop background sync with typed outcomes`() {
        assertThat(outcomeFor(401))
            .isEqualTo(WorkerSyncOutcome.AuthenticationRequired("status 401"))
        assertThat(outcomeFor(403))
            .isEqualTo(WorkerSyncOutcome.UserActionRequired("status 403"))
        assertThat(outcomeFor(409))
            .isEqualTo(WorkerSyncOutcome.Conflict("status 409"))
    }

    @Test
    fun `only declared temporary statuses request another bounded pass`() {
        listOf(429, 502, 503, 504).forEach { status ->
            assertThat(outcomeFor(status)).isEqualTo(WorkerSyncOutcome.Retry("status $status"))
        }
        assertThat(outcomeFor(500)).isEqualTo(WorkerSyncOutcome.Failed("status 500"))
    }

    private fun outcomeFor(status: Int): WorkerSyncOutcome = workerSyncOutcomeForGatewayProblem(
        GatewayProblemException(
            ApiProblemDto(
                type = "about:blank",
                title = "Gateway failure",
                status = status,
                detail = "status $status",
                code = "HTTP_$status",
            ),
        ),
    )
}
