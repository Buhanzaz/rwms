package dev.buhanzaz.rwms.manager.ui.components

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.media.MediaDownloader
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import dev.buhanzaz.rwms.manager.ui.InventoryEditorState
import dev.buhanzaz.rwms.manager.ui.ManagerMediaStateFlow
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keeps the old Compose reader alive during the gap after its editor snapshot was replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManagerMediaRetentionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `composed preview retains its file after editor removal and releases it on disposal`() = runBlocking {
        val directory = Files.createTempDirectory("manager-render-retention").toFile()
        try {
            val api = Proxy.newProxyInstance(RwmsApi::class.java.classLoader, arrayOf(RwmsApi::class.java)) {
                    _, method, _ ->
                check(method.name == "mediaVariantContent")
                byteArrayOf(1, 2, 3).toResponseBody("image/jpeg".toMediaType())
            } as RwmsApi
            val downloader = MediaDownloader(api, directory, Dispatchers.Unconfined, maxCacheFiles = 1)
            suspend fun download(id: String) = downloader.downloadVariant(id, 1, "/api/media/v1/assets/$id/variants/SMALL/content")
            val first = download("first")
            val state = ManagerMediaStateFlow(downloader::retain, downloader::release)
            state.value = ManagerUiState(inventoryEditor = InventoryEditorState("finding", "CAB-1", "MATCHED",
                persistedPhotoMedia = mapOf(first to MediaReferenceDto("first", 1))))
            downloader.release(first)
            val visible = mutableStateOf(true)
            compose.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalManagerMediaDownloader provides downloader) {
                        if (visible.value) ManagerPhotoPreview(first)
                    }
                }
            }
            compose.waitForIdle()
            state.value = ManagerUiState()
            val second = download("second")
            downloader.release(second)
            assertThat(File(requireNotNull(Uri.parse(first).path)).exists()).isTrue()

            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            val third = download("third")
            downloader.release(third)
            assertThat(File(requireNotNull(Uri.parse(first).path)).exists()).isFalse()
            state.close()
        } finally {
            directory.deleteRecursively()
        }
    }
}
