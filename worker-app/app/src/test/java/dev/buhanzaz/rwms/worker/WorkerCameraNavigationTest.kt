package dev.buhanzaz.rwms.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class WorkerCameraNavigationTest {
    @Test
    fun `reopening the same task creates a fresh camera navigation entry`() {
        val first = newCameraRoute("entry-1", 2)
        val reopened = newCameraRoute("entry-1", 2)

        assertThat(reopened.entryId).isEqualTo(first.entryId)
        assertThat(reopened.routeIndex).isEqualTo(first.routeIndex)
        assertThat(reopened.captureSessionId).isNotEqualTo(first.captureSessionId)
        assertThat(reopened).isNotEqualTo(first)
    }
}
