package dev.buhanzaz.rwms.driver.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.network.ApiProblemDto
import dev.buhanzaz.rwms.driver.core.network.GatewayProblemException
import org.junit.Test

/** Covers the driver sync outcomes derived from authoritative gateway statuses. */
class DriverSyncFailurePolicyTest {
    @Test
    fun `authorization and conflict statuses stop background sync with typed outcomes`() {
        assertThat(outcomeFor(401))
            .isEqualTo(DriverSyncOutcome.AuthenticationRequired("status 401"))
        assertThat(outcomeFor(403))
            .isEqualTo(DriverSyncOutcome.UserActionRequired("status 403"))
        assertThat(outcomeFor(409))
            .isEqualTo(DriverSyncOutcome.Conflict("status 409"))
    }

    @Test
    fun `only declared temporary statuses request another bounded pass`() {
        listOf(429, 502, 503, 504).forEach { status ->
            assertThat(outcomeFor(status)).isEqualTo(DriverSyncOutcome.Retry("status $status"))
        }
        assertThat(outcomeFor(500)).isEqualTo(DriverSyncOutcome.Failed("status 500"))
    }

    private fun outcomeFor(status: Int): DriverSyncOutcome = driverSyncOutcomeForGatewayProblem(
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
