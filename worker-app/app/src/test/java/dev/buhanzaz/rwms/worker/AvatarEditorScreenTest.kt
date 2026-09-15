package dev.buhanzaz.rwms.worker

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import dev.buhanzaz.rwms.worker.core.ui.RwmsWorkerTheme
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNativeImageDecoder
import java.nio.ByteBuffer
import com.google.common.truth.Truth.assertThat
import androidx.test.core.app.ApplicationProvider
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [WorkerAvatarImageSourceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarEditorScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<ComponentActivity>? = null

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun `avatar editor keeps safe controls visible on black fullscreen surface`() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        requireNotNull(controller).get().setContent {
            RwmsWorkerTheme {
                AvatarEditorScreen(
                    initialUri = "content://missing-avatar",
                    isSaving = false,
                    error = null,
                    onBack = {},
                    onDismissError = {},
                    onSave = {},
                )
            }
        }

        compose.onNodeWithText("Фото профиля").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
        compose.onNodeWithText("Двигайте и масштабируйте изображение")
            .assertIsDisplayed()
        compose.onNodeWithText("Другое").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Не удалось открыть изображение").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Не удалось открыть изображение").assertIsDisplayed()
    }

    @Test
    fun `system back cancels the editor`() {
        var backs = 0
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        requireNotNull(controller).get().setContent {
            RwmsWorkerTheme {
                AvatarEditorScreen(
                    initialUri = "content://missing-avatar",
                    isSaving = false,
                    error = null,
                    onBack = { backs++ },
                    onDismissError = {},
                    onSave = {},
                )
            }
        }
        compose.waitForIdle()
        requireNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        compose.runOnIdle { assertThat(backs).isEqualTo(1) }
    }

    @Test
    fun `crop output is square and does not recycle source when crop aliases it`() {
        val source = Bitmap.createBitmap(2_048, 2_048, Bitmap.Config.ARGB_8888)

        val output = renderWorkerAvatar(source, WorkerAvatarCrop())

        assertThat(output.width).isEqualTo(1_024)
        assertThat(output.height).isEqualTo(1_024)
        assertThat(source.isRecycled).isFalse()
        output.recycle()
        source.recycle()
    }

    @Test
    fun `crop bounds follow source aspect and zoom`() {
        val crop = WorkerAvatarCrop().transformed(
            width = 1600,
            height = 800,
            viewport = androidx.compose.ui.geometry.Size(400f, 400f),
            centroid = androidx.compose.ui.geometry.Offset(200f, 200f),
            pan = androidx.compose.ui.geometry.Offset(100_000f, -100_000f),
            zoomChange = 6f,
        )
        val source = crop.sourceRect(1600, 800)
        assertThat(source.left).isAtLeast(0f)
        assertThat(source.top).isAtLeast(0f)
        assertThat(source.right).isAtMost(1600f)
        assertThat(source.bottom).isAtMost(800f)
        assertThat(crop.zoom).isEqualTo(6f)
    }

    @Test
    fun `decoder reads a real local image instead of treating bounds decode as null`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("worker-avatar-", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = decodeAvatarBitmap(context, Uri.fromFile(file))
            assertThat(decoded.width).isEqualTo(120)
            assertThat(decoded.height).isEqualTo(80)
            decoded.recycle()
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    @Config(sdk = [27])
    fun `decoder reads a real local image through the legacy bitmap path`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("worker-avatar-legacy-", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = decodeAvatarBitmap(context, Uri.fromFile(file))
            assertThat(decoded.width).isEqualTo(120)
            assertThat(decoded.height).isEqualTo(80)
            decoded.recycle()
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun `real image reaches done and hands a rendered square to upload`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("worker-avatar-save-", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val saved = mutableListOf<Bitmap>()
        try {
            file.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            requireNotNull(controller).get().setContent {
                RwmsWorkerTheme {
                    AvatarEditorScreen(
                        initialUri = Uri.fromFile(file).toString(),
                        isSaving = false,
                        error = null,
                        onBack = {},
                        onDismissError = {},
                        onSave = { saved += it },
                    )
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Не удалось открыть изображение").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText("Готово").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("avatar-crop-preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Готово").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { saved.isNotEmpty() }
            assertThat(saved).hasSize(1)
            assertThat(saved.single().width).isEqualTo(1_024)
            assertThat(saved.single().height).isEqualTo(1_024)
        } finally {
            saved.forEach { it.recycle() }
            source.recycle()
            file.delete()
        }
    }
}

/** Bridges URI source creation while keeping native ImageDecoder pixel behavior in Robolectric. */
@Implements(value = ImageDecoder::class, callNativeMethodsByDefault = true)
class WorkerAvatarImageSourceShadow : ShadowNativeImageDecoder() {
    companion object {
        @Implementation
        @JvmStatic
        fun createSource(resolver: ContentResolver, uri: Uri): ImageDecoder.Source {
            val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            return ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        }
    }
}
