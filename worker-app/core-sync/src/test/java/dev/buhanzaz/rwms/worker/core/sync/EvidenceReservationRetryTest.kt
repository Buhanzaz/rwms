package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EvidenceReservationRetryTest {
    @Test
    fun `missing route remains retryable for rolling deployment recovery`() {
        assertThat(isTerminalEvidenceReservationStatus(404)).isFalse()
    }

    @Test
    fun `validation authorization and conflict failures remain terminal`() {
        assertThat(isTerminalEvidenceReservationStatus(400)).isTrue()
        assertThat(isTerminalEvidenceReservationStatus(403)).isTrue()
        assertThat(isTerminalEvidenceReservationStatus(409)).isTrue()
    }

    @Test
    fun `server failures remain retryable`() {
        assertThat(isTerminalEvidenceReservationStatus(500)).isFalse()
        assertThat(isTerminalEvidenceReservationStatus(503)).isFalse()
    }
}
