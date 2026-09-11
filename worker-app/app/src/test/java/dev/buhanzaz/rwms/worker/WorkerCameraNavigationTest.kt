package dev.buhanzaz.rwms.worker

import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class WorkerCameraNavigationTest {
    @Test
    fun `gallery opens over the current task without navigating away and dismissal preserves it`() {
        val task: NavKey = PhotoRoute("task", emptyList(), emptyList(), 0)
        val backStack = mutableListOf(task)
        val galleryBackStack = mutableListOf<NavKey>()
        val request = newCameraRoute("entry-1", 2, fromGallery = true)

        openWorkerCapture(backStack, galleryBackStack, request)

        assertThat(backStack).containsExactly(task)
        assertThat(galleryBackStack).containsExactly(request)
        closeCameraRoute(galleryBackStack, request)
        assertThat(backStack).containsExactly(task)
        assertThat(galleryBackStack).isEmpty()
    }

    @Test
    fun `repeated gallery tap keeps one photo selection session`() {
        val backStack = mutableListOf<NavKey>()
        val galleryBackStack = mutableListOf<NavKey>()
        val first = newCameraRoute("entry-1", 2, fromGallery = true)
        openWorkerCapture(backStack, galleryBackStack, first)
        openWorkerCapture(backStack, galleryBackStack, newCameraRoute("entry-1", 2, fromGallery = true))

        assertThat(backStack).isEmpty()
        assertThat(galleryBackStack).containsExactly(first)
    }

    @Test
    fun `camera still opens its full screen without a gallery sheet`() {
        val backStack = mutableListOf<NavKey>()
        val galleryBackStack = mutableListOf<NavKey>()
        val request = newCameraRoute("entry-1", 2)
        openWorkerCapture(backStack, galleryBackStack, request)

        assertThat(backStack).containsExactly(request)
        assertThat(galleryBackStack).isEmpty()
    }

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

    @Test
    fun `problem photo route preserves its draft identity without completing the task`() {
        val route = newCameraRoute(
            "entry-1",
            2,
            completeAfterSave = false,
            fromGallery = true,
            problemReportId = "report-1",
            maxPhotos = 1,
        )

        assertThat(route.problemReportId).isEqualTo("report-1")
        assertThat(route.maxPhotos).isEqualTo(1)
        assertThat(route.completeAfterSave).isFalse()
        assertThat(route.fromGallery).isTrue()
    }

    @Test
    fun `cancelled visual picker immediately removes its current transient route`() {
        val route = newCameraRoute("entry-1", 2, fromGallery = true)
        val backStack: MutableList<NavKey> = mutableListOf(route)

        closeCameraRoute(backStack, route)

        assertThat(backStack).isEmpty()
    }

    @Test
    fun `late picker result never closes a newer capture session`() {
        val cancelledRoute = newCameraRoute("entry-1", 2, fromGallery = true)
        val currentRoute = newCameraRoute("entry-1", 2, fromGallery = true)
        val backStack: MutableList<NavKey> = mutableListOf(cancelledRoute, currentRoute)

        closeCameraRoute(backStack, cancelledRoute)

        assertThat(backStack).containsExactly(cancelledRoute, currentRoute).inOrder()
    }
}
