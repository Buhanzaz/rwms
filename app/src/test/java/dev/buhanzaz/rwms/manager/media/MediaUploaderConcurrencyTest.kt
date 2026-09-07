package dev.buhanzaz.rwms.manager.media

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CreateUploadSessionRequest
import dev.buhanzaz.rwms.manager.network.ImageVariantUploadUrlDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import dev.buhanzaz.rwms.manager.network.UploadSessionDto
import dev.buhanzaz.rwms.manager.network.UploadedObjectDto
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploaderConcurrencyTest {
    @Test
    fun `independent cabin uploaders share the logical upload cap and release it on cancellation`() =
        runTest {
            var active = 0
            var maximumActive = 0
            val api = object : RwmsApi by unusedApi() {
                override suspend fun createUploadSession(
                    idempotencyKey: String,
                    request: CreateUploadSessionRequest,
                ): UploadSessionDto {
                    active += 1
                    maximumActive = maxOf(maximumActive, active)
                    try {
                        awaitCancellation()
                    } finally {
                        active -= 1
                    }
                }
            }
            val batches = (0..2).map { cabin ->
                async {
                    uploader(api).upload(owner(cabin), (0..2).map { "photo-$cabin-$it" })
                }
            }
            runCurrent()

            assertThat(active).isEqualTo(4)
            assertThat(maximumActive).isEqualTo(4)

            batches.forEach { it.cancel() }
            batches.forEach { it.join() }
            assertThat(active).isEqualTo(0)

            val retry = async { uploader(api).upload(owner(3), listOf("retry-photo")) }
            runCurrent()
            assertThat(active).isEqualTo(1)
            retry.cancelAndJoin()
            assertThat(active).isEqualTo(0)
        }

    @Test
    fun `independent cabin image bundles share the variant stream cap`() = runTest {
        var activeParts = 0
        var maximumActiveParts = 0
        val api = object : RwmsApi by unusedApi() {
            override suspend fun createUploadSession(
                idempotencyKey: String,
                request: CreateUploadSessionRequest,
            ): UploadSessionDto = UploadSessionDto(
                uploadSessionId = idempotencyKey,
                mediaId = idempotencyKey,
                expiresAt = "2026-09-07T12:00:00Z",
                variantUploadUrls = ImageUploadVariantKind.entries.map { kind ->
                    ImageVariantUploadUrlDto(
                        kind.name,
                        "/api/media/v1/upload-sessions/$idempotencyKey/variants/${kind.name}/content",
                    )
                },
            )

            override suspend fun uploadContent(
                contentPath: String,
                idempotencyKey: String,
                body: RequestBody,
            ): UploadedObjectDto {
                activeParts += 1
                maximumActiveParts = maxOf(maximumActiveParts, activeParts)
                try {
                    awaitCancellation()
                } finally {
                    activeParts -= 1
                }
            }
        }
        val bundles = (0..2).map { cabin ->
            async {
                uploader(api, imageBundle()).upload(owner(cabin), listOf("image-$cabin"))
            }
        }
        runCurrent()

        assertThat(activeParts).isEqualTo(6)
        assertThat(maximumActiveParts).isEqualTo(6)

        bundles.forEach { it.cancel() }
        bundles.forEach { it.join() }
        assertThat(activeParts).isEqualTo(0)

        val retry = async {
            uploader(api, imageBundle()).upload(owner(3), listOf("retry-image"))
        }
        runCurrent()
        assertThat(activeParts).isEqualTo(3)
        retry.cancelAndJoin()
        assertThat(activeParts).isEqualTo(0)
    }

    private fun uploader(
        api: RwmsApi,
        payload: MediaUploadPayload = PhotoPayload(
            fileName = "photo.jpg",
            contentType = "image/jpeg",
            bytes = byteArrayOf(1, 2, 3),
            checksumSha256 = "a".repeat(64),
        ),
    ): MediaUploader = MediaUploader(api, { payload }, testContract = Unit)

    private fun owner(cabin: Int) = MediaOwner(
        ownerType = "INVENTORY_FINDING",
        ownerId = "finding-$cabin",
        warehouseId = "warehouse-a",
        context = "INSPECTION",
    )

    private fun imageBundle() = ImageUploadBundle(
        fileName = "photo.webp",
        variants = ImageUploadVariantKind.entries.map { kind ->
            ImageUploadVariant(
                kind = kind,
                file = File("unused-${kind.name}.webp"),
                contentLength = 12,
                checksumSha256 = "a".repeat(64),
                width = 320,
                height = 180,
            )
        },
    )

    private fun unusedApi(): RwmsApi = Proxy.newProxyInstance(
        RwmsApi::class.java.classLoader,
        arrayOf(RwmsApi::class.java),
    ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as RwmsApi
}
