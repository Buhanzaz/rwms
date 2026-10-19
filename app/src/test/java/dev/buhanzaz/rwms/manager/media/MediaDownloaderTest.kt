package dev.buhanzaz.rwms.manager.media

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
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

    @Test
    fun `retained URI survives duplicate ownership until its final release`() = runBlocking {
        val cache = temporaryFolder.newFolder("retained-cache")
        val payload = "RIFFretained-WEBP".encodeToByteArray()
        val downloader = MediaDownloader(
            api = mediaApi(variantFactory = { payload.toResponseBody("image/webp".toMediaType()) }),
            cacheDir = cache,
            maxCacheFiles = 1,
            maxCacheBytes = 1_000,
        )
        val firstUri = downloader.downloadVariant(
            mediaId = MEDIA_ID_A,
            generation = 1,
            contentPath = variantPath(MEDIA_ID_A),
        )
        assertThat(downloader.retain(firstUri)).isTrue()
        val secondUri = downloader.downloadVariant(
            mediaId = MEDIA_ID_B,
            generation = 1,
            contentPath = variantPath(MEDIA_ID_B),
        )
        val firstFile = File(requireNotNull(Uri.parse(firstUri).path))

        downloader.release(firstUri)
        assertThat(firstFile.exists()).isTrue()
        downloader.release(firstUri)
        assertThat(firstFile.exists()).isFalse()
        assertThat(File(requireNotNull(Uri.parse(secondUri).path)).exists()).isTrue()
        assertThat(downloader.retain("file:///outside-the-index.webp")).isFalse()
        downloader.release(secondUri)
    }

    @Test
    fun `cache evicts by count and bytes without touching capture drafts`() = runBlocking {
        val countRoot = temporaryFolder.newFolder("count-cache")
        val draft = File(countRoot, "manager-photos/offline-draft.jpg").apply {
            parentFile?.mkdirs()
            writeText("draft")
        }
        val countDownloader = MediaDownloader(
            api = mediaApi(variantFactory = {
                "RIFFcount-WEBP".toResponseBody("image/webp".toMediaType())
            }),
            cacheDir = countRoot,
            maxCacheFiles = 1,
            maxCacheBytes = 1_000,
        )
        val countFirst = countDownloader.downloadVariant(MEDIA_ID_A, 1, variantPath(MEDIA_ID_A))
        countDownloader.release(countFirst)
        val countSecond = countDownloader.downloadVariant(MEDIA_ID_B, 1, variantPath(MEDIA_ID_B))
        assertThat(File(requireNotNull(Uri.parse(countFirst).path)).exists()).isFalse()
        assertThat(File(requireNotNull(Uri.parse(countSecond).path)).exists()).isTrue()
        assertThat(draft.readText()).isEqualTo("draft")
        countDownloader.release(countSecond)

        val byteRoot = temporaryFolder.newFolder("byte-cache")
        val byteDownloader = MediaDownloader(
            api = mediaApi(variantFactory = {
                "12345678".toResponseBody("image/webp".toMediaType())
            }),
            cacheDir = byteRoot,
            maxCacheFiles = 10,
            maxCacheBytes = 10,
        )
        val byteFirst = byteDownloader.downloadVariant(MEDIA_ID_A, 1, variantPath(MEDIA_ID_A))
        byteDownloader.release(byteFirst)
        val byteSecond = byteDownloader.downloadVariant(MEDIA_ID_B, 1, variantPath(MEDIA_ID_B))
        assertThat(File(requireNotNull(Uri.parse(byteFirst).path)).exists()).isFalse()
        assertThat(File(requireNotNull(Uri.parse(byteSecond).path)).exists()).isTrue()
        byteDownloader.release(byteSecond)
    }

    @Test
    fun `failed eviction remains indexed retries later and does not block other candidates`() {
        val directory = temporaryFolder.newFolder("failed-eviction-cache")
        val firstDeleteAttempts = AtomicInteger()
        val cache = RetainedMediaCache(
            directory = directory,
            maxFiles = 1,
            maxBytes = 1_000,
            deleteFile = { file ->
                if (file.name.startsWith(MEDIA_ID_A) &&
                    firstDeleteAttempts.incrementAndGet() < 3
                ) {
                    false
                } else {
                    file.delete()
                }
            },
        )
        val first = registerRetained(cache, directory, "$MEDIA_ID_A-1.webp")
        val second = registerRetained(cache, directory, "$MEDIA_ID_B-1.webp")
        val third = registerRetained(cache, directory, "$MEDIA_ID_C-1.webp")

        cache.release(Uri.fromFile(first).toString())
        assertThat(firstDeleteAttempts.get()).isEqualTo(1)
        assertThat(first.exists()).isTrue()

        cache.release(Uri.fromFile(second).toString())
        assertThat(firstDeleteAttempts.get()).isEqualTo(2)
        assertThat(first.exists()).isTrue()
        assertThat(second.exists()).isFalse()

        cache.release(Uri.fromFile(third).toString())
        assertThat(firstDeleteAttempts.get()).isEqualTo(3)
        assertThat(first.exists()).isFalse()
        assertThat(third.exists()).isTrue()
    }

    private fun registerRetained(
        cache: RetainedMediaCache,
        directory: File,
        fileName: String,
    ): File {
        val file = File(directory, fileName).apply {
            writeBytes("RIFFretained-WEBP".encodeToByteArray())
        }
        val stem = file.nameWithoutExtension
        cache.begin(stem)
        cache.register(stem, file)
        assertThat(cache.retain(Uri.fromFile(file).toString())).isTrue()
        cache.end(stem)
        return file
    }

    @Test
    fun `registering the same path preserves its existing ownership`() {
        val directory = temporaryFolder.newFolder("same-path-cache")
        val first = File(directory, "$MEDIA_ID_A-1.webp").apply {
            writeBytes("RIFFfirst-WEBP".encodeToByteArray())
        }
        val cache = RetainedMediaCache(directory, maxFiles = 1, maxBytes = 1_000)
        val firstUri = Uri.fromFile(first).toString()
        assertThat(cache.retain(firstUri)).isTrue()

        cache.register(first.nameWithoutExtension, first)
        val second = File(directory, "$MEDIA_ID_B-1.webp").apply {
            writeBytes("RIFFsecond-WEBP".encodeToByteArray())
        }
        cache.register(second.nameWithoutExtension, second)

        assertThat(first.exists()).isTrue()
        assertThat(second.exists()).isFalse()
        cache.release(firstUri)
    }

    @Test
    fun `canceled streaming body clears its in-flight entry and temporary part`() = runBlocking {
        val cache = temporaryFolder.newFolder("canceled-cache")
        val downloader = MediaDownloader(
            api = mediaApi(variantFactory = { cancelingBody() }),
            cacheDir = cache,
            maxCacheFiles = 1,
            maxCacheBytes = 1_000,
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                downloader.downloadVariant(MEDIA_ID_A, 1, variantPath(MEDIA_ID_A))
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
        variantFactory: (() -> ResponseBody)? = null,
        original: (() -> ResponseBody)? = null,
    ): RwmsApi =
        Proxy.newProxyInstance(
            RwmsApi::class.java.classLoader,
            arrayOf(RwmsApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "mediaVariantContent" -> variantFactory?.invoke() ?: requireNotNull(variant)
                "originalMedia" -> requireNotNull(original).invoke()
                else -> error("Unexpected API call ${method.name}")
            }
        } as RwmsApi

    private fun cancelingBody(): ResponseBody = object : ResponseBody() {
        override fun contentType() = "image/webp".toMediaType()

        override fun contentLength(): Long = 32

        override fun source(): BufferedSource = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                throw CancellationException("test cancellation")
            }
        }.buffer()
    }

    private fun variantPath(mediaId: String): String =
        "/api/media/v1/assets/$mediaId/variants/MEDIUM/content"

    private companion object {
        const val MEDIA_ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val MEDIA_ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val MEDIA_ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }
}
