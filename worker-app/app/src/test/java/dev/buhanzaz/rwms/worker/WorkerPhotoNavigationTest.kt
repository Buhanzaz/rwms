package dev.buhanzaz.rwms.worker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Verifies selected-photo routing and the pure bounds used by the full-screen media pager. */
@RunWith(JUnit4::class)
class WorkerPhotoNavigationTest {
    @Test
    fun `selected thumbnail index survives navigation`() {
        val route = PhotoRoute(
            title = "Общие фото",
            readPaths = listOf("/media/one", "/media/two", "/media/three"),
            initialIndex = 1,
        )

        assertThat(photoPagerInitialPage(route.initialIndex, route.readPaths.size)).isEqualTo(1)
    }

    @Test
    fun `invalid selected thumbnail index is clamped safely`() {
        assertThat(photoPagerInitialPage(-4, 3)).isEqualTo(0)
        assertThat(photoPagerInitialPage(8, 3)).isEqualTo(2)
        assertThat(photoPagerInitialPage(8, 0)).isEqualTo(0)
    }

    @Test
    fun `zoomed fitted photo cannot be panned beyond visible bounds`() {
        val bounded = boundedPhotoOffset(
            proposed = Offset(80f, 40f),
            scale = 2f,
            viewportSize = IntSize(100, 100),
            imageSize = IntSize(200, 100),
        )

        assertThat(bounded.x).isEqualTo(50f)
        assertThat(bounded.y).isEqualTo(0f)
    }

    @Test
    fun `unzoomed fitted photo always recenters`() {
        val bounded = boundedPhotoOffset(
            proposed = Offset(-80f, 40f),
            scale = 1f,
            viewportSize = IntSize(100, 100),
            imageSize = IntSize(100, 200),
        )

        assertThat(bounded.x).isEqualTo(0f)
        assertThat(bounded.y).isEqualTo(0f)
    }
}
