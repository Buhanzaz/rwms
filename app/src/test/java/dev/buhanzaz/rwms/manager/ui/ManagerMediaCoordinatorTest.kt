package dev.buhanzaz.rwms.manager.ui

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.media.MediaDownloader
import dev.buhanzaz.rwms.manager.network.MediaPageDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ManagerMediaCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `successful batch retains every temporary claim until explicit release`() = runTest {
        val cacheRoot = temporaryFolder.newFolder("successful-batch-cache")
        val api = mediaApi { mediaId -> responseBody(mediaId) }
        val downloader = downloader(api, cacheRoot)
        val coordinator = ManagerMediaCoordinator(api, downloader)
        val loading = async {
            coordinator.loadScopedPhotoUris(
                requests = listOf(scopedRequest(MEDIA_ID_A), scopedRequest(MEDIA_ID_B)),
                warehouseId = WAREHOUSE_ID,
            )
        }

        runCurrent()
        assertThat(loading.isCompleted).isTrue()
        val results = loading.await()
        val firstFile = fileFor(results[0].uri)
        val secondFile = fileFor(results[1].uri)
        assertThat(firstFile.exists()).isTrue()
        assertThat(secondFile.exists()).isTrue()

        coordinator.releasePhotoUris(results.map(ScopedMediaResult::uri))
        assertThat(firstFile.exists()).isFalse()
        assertThat(secondFile.exists()).isTrue()

        val nextUri = downloadOriginal(downloader, MEDIA_ID_C)
        assertThat(secondFile.exists()).isFalse()
        downloader.release(nextUri)
    }

    @Test
    fun `canceled batch releases a completed temporary claim`() = runTest {
        val cacheRoot = temporaryFolder.newFolder("canceled-batch-cache")
        val blockedResponse = CompletableDeferred<Unit>()
        val blockedResponseStarted = CompletableDeferred<Unit>()
        val api = mediaApi { mediaId ->
            if (mediaId == MEDIA_ID_B) {
                blockedResponseStarted.complete(Unit)
                blockedResponse.await()
            }
            responseBody(mediaId)
        }
        val downloader = downloader(api, cacheRoot)
        val coordinator = ManagerMediaCoordinator(api, downloader)
        val loading = async {
            coordinator.loadScopedPhotoUris(
                requests = listOf(scopedRequest(MEDIA_ID_A), scopedRequest(MEDIA_ID_B)),
                warehouseId = WAREHOUSE_ID,
            )
        }

        runCurrent()
        val completedFile = cachedOriginal(cacheRoot, MEDIA_ID_A)
        assertThat(blockedResponseStarted.isCompleted).isTrue()
        assertThat(completedFile.exists()).isTrue()
        assertThat(loading.isCompleted).isFalse()

        loading.cancelAndJoin()
        runCurrent()

        val nextUri = downloadOriginal(downloader, MEDIA_ID_C)
        assertThat(completedFile.exists()).isFalse()
        downloader.release(nextUri)
    }

    @Test
    fun `duplicate shared URI releases every temporary claim without a leak`() = runTest {
        val cacheRoot = temporaryFolder.newFolder("duplicate-uri-cache")
        val api = mediaApi { mediaId -> responseBody(mediaId) }
        val downloader = downloader(api, cacheRoot)
        val coordinator = ManagerMediaCoordinator(api, downloader)
        val loading = async {
            coordinator.loadScopedPhotoUris(
                requests = listOf(scopedRequest(MEDIA_ID_A), scopedRequest(MEDIA_ID_A)),
                warehouseId = WAREHOUSE_ID,
            )
        }

        runCurrent()
        assertThat(loading.isCompleted).isTrue()
        val results = loading.await()
        assertThat(results.map(ScopedMediaResult::uri).distinct()).hasSize(1)
        val sharedFile = fileFor(results.first().uri)
        assertThat(sharedFile.exists()).isTrue()

        coordinator.releasePhotoUris(results.map(ScopedMediaResult::uri))
        val nextUri = downloadOriginal(downloader, MEDIA_ID_B)
        assertThat(sharedFile.exists()).isFalse()
        downloader.release(nextUri)
    }

    private fun TestScope.downloader(api: RwmsApi, cacheRoot: File) = MediaDownloader(
        api = api,
        cacheDir = cacheRoot,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        maxCacheFiles = 1,
        maxCacheBytes = 1_000,
    )

    private suspend fun downloadOriginal(
        downloader: MediaDownloader,
        mediaId: String,
    ): String = downloader.downloadOriginal(
        mediaId = mediaId,
        generation = 1,
        ownerType = OWNER_SCOPE.ownerType,
        ownerId = OWNER_SCOPE.ownerId,
        warehouseId = WAREHOUSE_ID,
        context = OWNER_SCOPE.context,
    )

    private fun scopedRequest(mediaId: String) = ScopedMediaDownload(
        reference = MediaReferenceDto(mediaId, 1),
        scopes = listOf(OWNER_SCOPE),
    )

    private fun cachedOriginal(cacheRoot: File, mediaId: String) =
        File(cacheRoot, "manager-remote-media/$mediaId-1.webp")

    private fun fileFor(uri: String) = File(requireNotNull(Uri.parse(uri).path))

    private fun responseBody(mediaId: String) =
        "RIFF-$mediaId-WEBP".toResponseBody("image/webp".toMediaType())

    private fun mediaApi(originalResponse: suspend (String) -> ResponseBody): RwmsApi =
        Proxy.newProxyInstance(
            RwmsApi::class.java.classLoader,
            arrayOf(RwmsApi::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "ownerMedia" -> MediaPageDto(emptyList())
                "originalMedia" -> {
                    val args = requireNotNull(arguments)
                    val mediaId = args.first() as String
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args.last() as Continuation<ResponseBody>
                    val response: suspend () -> ResponseBody = { originalResponse(mediaId) }
                    response.startCoroutineUninterceptedOrReturn(continuation)
                }
                else -> error("Unexpected API call ${method.name}")
            }
        } as RwmsApi

    private companion object {
        const val WAREHOUSE_ID = "warehouse-1"
        const val MEDIA_ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val MEDIA_ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val MEDIA_ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val OWNER_SCOPE = MaintenanceMediaScope(
            ownerType = "MAINTENANCE_REPAIR",
            ownerId = "repair-1",
            context = "RESULT",
        )
    }
}
