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

    @Test
    fun `gallery completion uses a distinct one-shot capture route`() {
        val route = newCameraRoute("entry-1", 2, fromGallery = true)

        assertThat(route.fromGallery).isTrue()
        assertThat(route.completeAfterSave).isTrue()
    }

    @Test
    fun `ordinary result capture does not request task completion`() {
        val route = newCameraRoute("entry-1", 2, completeAfterSave = false)

        assertThat(route.fromGallery).isFalse()
        assertThat(route.completeAfterSave).isFalse()
    }

    @Test
    fun `completion intent remains on the capture route until camera persistence`() {
        val route = newCameraRoute("entry-1", 2, completeAfterSave = true)

        assertThat(route.completeAfterSave).isTrue()
    }
}
