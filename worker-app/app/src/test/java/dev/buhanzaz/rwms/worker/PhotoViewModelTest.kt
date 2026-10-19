package dev.buhanzaz.rwms.worker

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies progressive authenticated-photo loading and path-local pager retention. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `small preview is published before the current original`() = runTest(dispatcher) {
        val originalReady = CompletableDeferred<Unit>()
        val source = object : PhotoBitmapSource {
            override suspend fun load(path: String, maxBytes: Int, maxPixels: Long): Bitmap {
                if (path == "/original") originalReady.await()
                val edge = if (path == "/preview") 8 else 32
                return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
            }
        }
        val viewModel = PhotoViewModel(source)
        val item = PhotoMediaItem("/preview", "/original")

        viewModel.show(listOf(item), selectedIndex = 0)
        runCurrent()

        assertThat(viewModel.state.value.bitmaps.getValue("/original").width).isEqualTo(8)
        assertThat(viewModel.state.value.loadingOriginals).contains("/original")
        assertThat(viewModel.state.value.originalPaths).doesNotContain("/original")

        originalReady.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.state.value.bitmaps.getValue("/original").width).isEqualTo(32)
        assertThat(viewModel.state.value.originalPaths).contains("/original")
    }

    @Test
    fun `neighbour request survives swipe and viewed page remains available on back swipe`() =
        runTest(dispatcher) {
            val secondPreviewReady = CompletableDeferred<Unit>()
            val requests = mutableListOf<String>()
            val source = object : PhotoBitmapSource {
                override suspend fun load(path: String, maxBytes: Int, maxPixels: Long): Bitmap {
                    requests += path
                    if (path == "/preview-1") secondPreviewReady.await()
                    return Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
                }
            }
            val items = (0..2).map { index ->
                PhotoMediaItem("/preview-$index", "/original-$index")
            }
            val viewModel = PhotoViewModel(source)

            viewModel.show(items, selectedIndex = 0)
            runCurrent()
            viewModel.show(items, selectedIndex = 1)
            runCurrent()
            secondPreviewReady.complete(Unit)
            advanceUntilIdle()

            assertThat(requests.count { it == "/preview-1" }).isEqualTo(1)
            assertThat(viewModel.state.value.bitmaps).containsKey("/original-1")

            viewModel.show(items, selectedIndex = 2)
            advanceUntilIdle()
            viewModel.show(items, selectedIndex = 1)

            assertThat(viewModel.state.value.bitmaps.getValue("/original-1").isRecycled).isFalse()
        }
}
