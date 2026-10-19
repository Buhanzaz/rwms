package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompletionGateTest {
    @Test
    fun `completion is blocked until the required evidence is ready`() {
        assertThat(completionGateAllows(requiredReadyEvidence = 1, actualReadyEvidence = 0)).isFalse()
        assertThat(completionGateAllows(requiredReadyEvidence = 1, actualReadyEvidence = 1)).isTrue()
    }

    @Test
    fun `queues without result photos remain completable`() {
        assertThat(completionGateAllows(requiredReadyEvidence = 0, actualReadyEvidence = 0)).isTrue()
    }

    @Test
    fun `server evidence count participates without double counting cached ids`() {
        assertThat(
            combinedReadyEvidenceCount(
                serverReadyEvidenceCount = 2,
                localReadyEvidenceIds = listOf("evidence-2"),
                detailReadyEvidenceIds = listOf("evidence-1", "evidence-2"),
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `new local ready evidence extends an older detail snapshot`() {
        assertThat(
            combinedReadyEvidenceCount(
                serverReadyEvidenceCount = 1,
                localReadyEvidenceIds = listOf("evidence-2"),
                detailReadyEvidenceIds = listOf("evidence-1"),
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `all pages must describe one stable feed revision`() {
        val first = validateFeedPage(null, revision = 8, serverTime = "2026-07-25T10:00:00Z")

        assertThat(validateFeedPage(first, revision = 8, serverTime = "2026-07-25T10:00:00Z")).isEqualTo(first)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mixed feed pages are rejected`() {
        validateFeedPage(FeedPageConsistency(8, "2026-07-25T10:00:00Z"), 9, "2026-07-25T10:00:00Z")
    }
}
