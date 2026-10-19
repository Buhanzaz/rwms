package dev.buhanzaz.rwms.worker.feature.taskdetail

import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.worker.core.ui.RwmsWorkerTheme
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.roundToInt

/** Proves task content fades through the lower 24dp of the opaque worker header. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskHeaderFadeComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun scrolledPhotosAndFieldsAreHiddenThenVisibleThroughHeaderBottom() {
        val background = Color(0xFF102030)
        val content = Color(0xFFE04050)
        val rows = (0 until 24).toList()
        lateinit var hostView: View

        compose.setContent {
            RwmsWorkerTheme {
                hostView = LocalView.current
                Box(Modifier.fillMaxSize().background(background).testTag("fade-test-root")) {
                    WorkerScreenScaffold(title = "Задание") { padding ->
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .fadeIntoTaskHeader(padding.calculateTopPadding() - 8.dp)
                                .testTag("fade-test-list"),
                            contentPadding = PaddingValues(
                                top = padding.calculateTopPadding() + 12.dp,
                                bottom = 24.dp,
                            ),
                        ) {
                            items(rows) { row ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(84.dp)
                                        .background(content)
                                        .testTag("fade-test-row-$row"),
                                )
                            }
                        }
                    }
                }
            }
        }

        val list = compose.onNodeWithTag("fade-test-list")
        list.performScrollToIndex(12)
        compose.waitForIdle()

        val header = compose.onNodeWithTag("worker-header").fetchSemanticsNode().boundsInRoot
        val density = header.height / 60f
        val headerBottom = header.bottom.roundToInt()
        lateinit var image: Bitmap
        compose.runOnIdle {
            image = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
            hostView.draw(Canvas(image))
        }
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val x = (4 * density).roundToInt()
        val aboveHeader = sample(pixels, image.width, x, headerBottom - (30 * density).roundToInt())
        val lowerHeader = sample(pixels, image.width, x, headerBottom - (12 * density).roundToInt())
        val belowHeader = sample(pixels, image.width, x, headerBottom + (12 * density).roundToInt())

        assertThat(aboveHeader).isEqualTo(background.toArgb())
        assertThat(belowHeader).isEqualTo(content.toArgb())
        assertThat(lowerHeader).isNotEqualTo(background.toArgb())
        assertThat(lowerHeader).isNotEqualTo(content.toArgb())
        val expectedMidRed = (AndroidColor.red(background.toArgb()) + AndroidColor.red(content.toArgb())) / 2
        assertThat(abs(AndroidColor.red(lowerHeader) - expectedMidRed)).isAtMost(20)
    }

    private fun sample(pixels: IntArray, width: Int, x: Int, y: Int): Int {
        check(x in 0 until width && y in 0 until pixels.size / width)
        return pixels[y * width + x]
    }
}
