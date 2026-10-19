package dev.buhanzaz.rwms.worker

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Exercises the real navigation host without camera, credentials or storage side effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkerGalleryOverlayTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<ComponentActivity>? = null

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun `opening cancelling and reopening gallery retains completion dialog and creates a fresh session`() {
        val gallery = mutableStateListOf<NavKey>()
        val taskStack = mutableListOf<NavKey>()
        val first = newCameraRoute("entry", 1, fromGallery = true)
        var completionMounts = 0
        var completionDisposals = 0
        var galleryDisposals = 0
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        requireNotNull(controller).get().setContent {
            MaterialTheme {
                Dialog(onDismissRequest = {}) {
                    DisposableEffect(Unit) {
                        completionMounts++
                        onDispose { completionDisposals++ }
                    }
                    Text("Завершить задание")
                }
                WorkerGalleryOverlay(gallery) { _, dismiss ->
                    DisposableEffect(Unit) { onDispose { galleryDisposals++ } }
                    Dialog(onDismissRequest = dismiss) {
                        Button(onClick = dismiss) { Text("Закрыть галерею") }
                    }
                }
            }
        }
        compose.runOnIdle { openWorkerCapture(taskStack, gallery, first) }
        compose.onNodeWithText("Завершить задание").assertIsDisplayed()
        compose.onNodeWithText("Закрыть галерею").performClick()
        compose.onNodeWithText("Завершить задание").assertIsDisplayed()
        compose.runOnIdle {
            assertThat(gallery).isEmpty()
            assertThat(taskStack).isEmpty()
            assertThat(galleryDisposals).isEqualTo(1)
            openWorkerCapture(taskStack, gallery, newCameraRoute("entry", 1, fromGallery = true))
        }
        compose.onNodeWithText("Закрыть галерею").assertIsDisplayed()
        compose.runOnIdle {
            assertThat(completionMounts).isEqualTo(1)
            assertThat(completionDisposals).isEqualTo(0)
            assertThat(gallery.single()).isNotEqualTo(first)
        }
    }
}
