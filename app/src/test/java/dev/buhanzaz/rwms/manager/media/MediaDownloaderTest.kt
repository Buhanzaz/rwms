package dev.buhanzaz.rwms.manager.media

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/** Verifies that streamed media bytes leave the caller thread before local cache persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MediaDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `variant streaming body is persisted on the configured IO dispatcher`() = runBlocking {
        val readThread = AtomicReference<String>()
        val payload = "RIFFmanager-test-WEBP".encodeToByteArray()
        val api = mediaApi(streamingBody(payload, readThread))
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "manager-media-cache-io")
        }

        executor.asCoroutineDispatcher().use { ioDispatcher ->
            val uri = MediaDownloader(
                api = api,
                cacheDir = temporaryFolder.newFolder("cache"),
                ioDispatcher = ioDispatcher,
            ).downloadVariant(
                mediaId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                generation = 1,
                contentPath =
                    "/api/media/v1/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/variants/MEDIUM/content",
            )

            assertThat(readThread.get()).contains("manager-media-cache-io")
            assertThat(File(requireNotNull(Uri.parse(uri).path)).readBytes())
                .isEqualTo(payload)
        }
    }

    @Test
    fun `cache promotion copies the complete part when legacy rename fails`() {
        val directory = temporaryFolder.newFolder("promotion")
        val temporary = File(directory, "photo.webp.part").apply {
            writeBytes("RIFFcomplete-WEBP".encodeToByteArray())
        }
        val target = File(directory, "photo.webp")

        promoteCompleteMediaCacheFile(
            temporary = temporary,
            target = target,
            rename = { _, _ -> false },
        )

        assertThat(target.readBytes()).isEqualTo("RIFFcomplete-WEBP".encodeToByteArray())
        assertThat(temporary.exists()).isFalse()
    }

    @Test
    fun `cache promotion never replaces an existing complete immutable generation`() {
        val directory = temporaryFolder.newFolder("concurrent-promotion")
        val target = File(directory, "photo.webp").apply {
            writeBytes("RIFFfirst-WEBP".encodeToByteArray())
        }
        val later = File(directory, "photo-later.webp.part").apply {
            writeBytes("RIFFlater-WEBP".encodeToByteArray())
        }
        val renameCalls = AtomicInteger()

        promoteCompleteMediaCacheFile(
            temporary = later,
            target = target,
            rename = { _, _ ->
                renameCalls.incrementAndGet()
                false
            },
        )

        assertThat(renameCalls.get()).isEqualTo(0)
        assertThat(target.readBytes()).isEqualTo("RIFFfirst-WEBP".encodeToByteArray())
        assertThat(later.exists()).isFalse()
    }

    @Test
    fun `cached original is reused only after the exact owner read is authorized`() = runBlocking {
        val cache = temporaryFolder.newFolder("authorized-cache")
        val mediaId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val cached = File(cache, "manager-remote-media/$mediaId-7.webp").apply {
            parentFile?.mkdirs()
            writeBytes("RIFFauthorized-WEBP".encodeToByteArray())
        }
        val originalCalls = AtomicInteger()
        val api = mediaApi(
            original = {
                originalCalls.incrementAndGet()
                "unused-network-body".toResponseBody("image/webp".toMediaType())
            },
        )

        val uri = MediaDownloader(api, cache).downloadOriginal(
            mediaId = mediaId,
            generation = 7,
            ownerType = "MAINTENANCE_REPAIR",
            ownerId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            warehouseId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            context = "RESULT",
        )

        assertThat(originalCalls.get()).isEqualTo(1)
        assertThat(File(requireNotNull(Uri.parse(uri).path))).isEqualTo(cached)
        assertThat(cached.readBytes()).isEqualTo("RIFFauthorized-WEBP".encodeToByteArray())
    }

    @Test
    fun `forbidden owner read cannot expose a cached original`() {
        val cache = temporaryFolder.newFolder("forbidden-cache")
        val mediaId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        File(cache, "manager-remote-media/$mediaId-7.webp").apply {
            parentFile?.mkdirs()
            writeBytes("RIFFmust-not-leak-WEBP".encodeToByteArray())
        }
        val api = mediaApi(
            original = {
                throw HttpException(
                    Response.error<ResponseBody>(
                        403,
                        "forbidden".toResponseBody("text/plain".toMediaType()),
                    ),
                )
            },
        )

        assertThrows(HttpException::class.java) {
            runBlocking {
                MediaDownloader(api, cache).downloadOriginal(
                    mediaId = mediaId,
                    generation = 7,
                    ownerType = "MAINTENANCE_REPAIR",
                    ownerId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    warehouseId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                    context = "RESULT",
                )
            }
        }
    }

    @Test
    fun `incomplete streaming body leaves no cache file or part`() {
        val cache = temporaryFolder.newFolder("incomplete-cache")
        val readThread = AtomicReference<String>()
        val api = mediaApi(
            variant = streamingBody(
                payload = "short".encodeToByteArray(),
                readThread = readThread,
                declaredLength = 50,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                MediaDownloader(api, cache).downloadVariant(
                    mediaId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    generation = 1,
                    contentPath =
                        "/api/media/v1/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/variants/MEDIUM/content",
                )
            }
        }

        assertThat(File(cache, "manager-remote-media").listFiles().orEmpty()).isEmpty()
    }

    private fun streamingBody(
        payload: ByteArray,
        readThread: AtomicReference<String>,
        declaredLength: Long = payload.size.toLong(),
    ): ResponseBody {
        val source = Buffer().write(payload)
        return object : ResponseBody() {
            override fun contentType() = "image/webp".toMediaType()

            override fun contentLength(): Long = declaredLength

            override fun source(): BufferedSource {
                readThread.set(Thread.currentThread().name)
                return source
            }
        }
    }

    private fun mediaApi(
        variant: ResponseBody? = null,
        original: (() -> ResponseBody)? = null,
    ): RwmsApi =
        Proxy.newProxyInstance(
            RwmsApi::class.java.classLoader,
            arrayOf(RwmsApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "mediaVariantContent" -> requireNotNull(variant)
                "originalMedia" -> requireNotNull(original).invoke()
                else -> error("Unexpected API call ${method.name}")
            }
        } as RwmsApi
}
