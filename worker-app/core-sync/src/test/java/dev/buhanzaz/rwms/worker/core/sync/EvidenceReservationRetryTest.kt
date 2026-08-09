package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.network.isRetryableGatewayStatus
import org.junit.Test

class EvidenceReservationRetryTest {
    @Test
    fun `only declared temporary gateway statuses are retryable`() {
        assertThat(isRetryableGatewayStatus(429)).isTrue()
        assertThat(isRetryableGatewayStatus(502)).isTrue()
        assertThat(isRetryableGatewayStatus(503)).isTrue()
        assertThat(isRetryableGatewayStatus(504)).isTrue()
    }

    @Test
    fun `authorization conflict validation and missing responses stop automatic reservation replay`() {
        assertThat(isTerminalEvidenceReservationStatus(400)).isTrue()
        assertThat(isTerminalEvidenceReservationStatus(403)).isTrue()
        assertThat(isTerminalEvidenceReservationStatus(404)).isTrue()
        assertThat(isTerminalEvidenceReservationStatus(409)).isTrue()
    }

    @Test
    fun `unexpected server failures stop automatic reservation replay`() {
        assertThat(isTerminalEvidenceReservationStatus(500)).isTrue()
        assertThat(isTerminalEvidenceReservationStatus(503)).isFalse()
    }
}
