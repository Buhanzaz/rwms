package dev.buhanzaz.rwms.manager.media

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class MediaUploaderHttpContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: RwmsApi
    private val photoBytes = byteArrayOf(
        0xff.toByte(),
        0xd8.toByte(),
        0xff.toByte(),
        0xd9.toByte(),
    )
    private val checksum = "b".repeat(64)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(
                MoshiConverterFactory.create(
                    Moshi.Builder()
                        .add(ExplicitNullJsonAdapterFactory)
                        .addLast(KotlinJsonAdapterFactory())
                        .build(),
                ),
            )
            .build()
            .create(RwmsApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `image bundle retries three variant paths with stable per-part keys until ready`() =
        runTest {
            val mediaId = "11111111-1111-1111-1111-111111111111"
            val sessionId = "22222222-2222-2222-2222-222222222222"
            val warehouseId = "33333333-3333-3333-3333-333333333333"
            val ownerId = "44444444-4444-4444-4444-444444444444"
            val bundle = imageBundle()
            val owner = MediaOwner(
                ownerType = "MAINTENANCE_ESTIMATE",
                ownerId = ownerId,
                warehouseId = warehouseId,
                context = "ESTIMATE",
            )
            val identity = imageBundleUploadIdentity(owner, "local-photo", bundle, 0)
            val createResponse = buildString {
                append("{\"uploadSessionId\":\"$sessionId\",\"mediaId\":\"$mediaId\",")
                append("\"expiresAt\":\"2026-07-28T01:00:00Z\",\"variantUploadUrls\":[")
                append(
                    ImageUploadVariantKind.entries.joinToString(",") { kind ->
                        "{\"kind\":\"${kind.name}\",\"contentUploadUrl\":" +
                            "\"/api/media/v1/upload-sessions/$sessionId/variants/" +
                            "${kind.name}/content\"}"
                    },
                )
                append("]}")
            }
            val objectResponse =
                """{"objectVersionId":"object-v1","etag":"etag-v1","checksumSha256":"$checksum"}"""
            val processingAsset = assetJson(
                mediaId = mediaId,
                status = "PROCESSING",
                generation = 0,
            )
            val readyAsset = assetJson(
                mediaId = mediaId,
                status = "READY",
                generation = 2,
            )
            server.enqueue(json(createResponse, 201))
            server.enqueue(json(objectResponse, 201))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(json(objectResponse, 201))
            server.enqueue(json(createResponse))
            repeat(3) { server.enqueue(json(objectResponse)) }
            server.enqueue(json(processingAsset, 202))
            server.enqueue(json("""{"items":[$processingAsset],"next":null}"""))
            server.enqueue(json("""{"items":[$readyAsset],"next":null}"""))

            val references = uploader(bundle).upload(
                owner = owner,
                photoUris = listOf("local-photo"),
            )

            assertThat(references.single().mediaId).isEqualTo(mediaId)
            assertThat(references.single().generation).isEqualTo(2)
            val requests = List(11) {
                checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            }
            val createKey = requests[0].getHeader("Idempotency-Key")
            assertThat(createKey).isNotNull()
            assertThat(requests[4].getHeader("Idempotency-Key")).isEqualTo(createKey)
            assertThat(requests[0].body.clone().readUtf8())
                .isEqualTo(requests[4].body.clone().readUtf8())
            assertThat(requests[0].path)
                .isEqualTo("/api/media/v1/upload-sessions")
            assertThat(requests[0].body.clone().readUtf8()).isEqualTo(
                buildString {
                    append("{\"ownerType\":\"MAINTENANCE_ESTIMATE\",\"ownerId\":\"$ownerId\",")
                    append("\"warehouseId\":\"$warehouseId\",\"context\":\"ESTIMATE\",")
                    append("\"folderId\":\"${identity.folderId}\",\"fileName\":\"photo.webp\",")
                    append("\"sortOrder\":0,\"imageVariants\":[")
                    append(
                        bundle.variants.joinToString(",") { variant ->
                            "{\"kind\":\"${variant.kind.name}\",\"contentLength\":" +
                                "${variant.contentLength},\"checksumSha256\":" +
                                "\"${variant.checksumSha256}\",\"width\":${variant.width}," +
                                "\"height\":${variant.height}}"
                        },
                    )
                    append("]}")
                },
            )
            val firstParts = requests.subList(1, 4).associateBy { it.path }
            val retriedParts = requests.subList(5, 8).associateBy { it.path }
            assertThat(firstParts.keys).containsExactlyElementsIn(
                ImageUploadVariantKind.entries.map { kind ->
                    "/api/media/v1/upload-sessions/$sessionId/variants/${kind.name}/content"
                },
            )
            firstParts.forEach { (path, request) ->
                assertThat(request.method).isEqualTo("PUT")
                assertThat(request.getHeader("Content-Type")).isEqualTo("image/webp")
                assertThat(request.getHeader("Idempotency-Key"))
                    .isEqualTo(retriedParts[path]?.getHeader("Idempotency-Key"))
            }
            assertThat(firstParts.values.map { it.getHeader("Idempotency-Key") }.toSet())
                .hasSize(3)
            assertThat(requests[8].path)
                .isEqualTo("/api/media/v1/upload-sessions/$sessionId/complete")
            assertThat(requests[8].getHeader("Idempotency-Key"))
                .isEqualTo(identity.finalizeKey)
            assertThat(requests[8].body.clone().readUtf8()).isEqualTo(
                buildString {
                    append("{\"variants\":[")
                    append(
                        ImageUploadVariantKind.entries.joinToString(",") { kind ->
                            "{\"kind\":\"${kind.name}\",\"objectVersionId\":" +
                                "\"object-v1\",\"etag\":\"etag-v1\"," +
                                "\"checksumSha256\":\"$checksum\"}"
                        },
                    )
                    append("]}")
                },
            )
            assertThat(requests[9].path).isEqualTo(
                "/api/media/v1/assets?ownerType=MAINTENANCE_ESTIMATE" +
                    "&ownerId=$ownerId&warehouseId=$warehouseId" +
                    "&context=ESTIMATE&limit=100",
            )
            assertThat(requests[10].path).isEqualTo(requests[9].path)
        }

    @Test
    fun `terminal media failure stops after finalize without owner proof read`() =
        runTest {
            val mediaId = "11111111-1111-1111-1111-111111111111"
            val sessionId = "22222222-2222-2222-2222-222222222222"
            server.enqueue(json(sessionJson(sessionId, mediaId), 201))
            server.enqueue(json(uploadedObjectJson(), 201))
            server.enqueue(
                json(
                    assetJson(mediaId, status = "FAILED", generation = 0),
                    202,
                ),
            )

            val failure = runCatching {
                uploader().upload(owner(), listOf("local-photo"))
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo(MEDIA_PROCESSING_FAILED_MESSAGE)
            assertThat(server.requestCount).isEqualTo(3)
            val requests = List(3) {
                checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            }
            assertThat(requests.map { it.path }).containsExactly(
                "/api/media/v1/upload-sessions",
                "/api/media/v1/upload-sessions/$sessionId/content",
                "/api/media/v1/upload-sessions/$sessionId/complete",
            ).inOrder()
        }

    @Test
    fun `image bundle rejects a foreign variant path before sending any bytes`() = runTest {
        val mediaId = "11111111-1111-1111-1111-111111111111"
        val sessionId = "22222222-2222-2222-2222-222222222222"
        val response = buildString {
            append("{\"uploadSessionId\":\"$sessionId\",\"mediaId\":\"$mediaId\",")
            append("\"expiresAt\":\"2026-07-28T01:00:00Z\",\"variantUploadUrls\":[")
            append(
                ImageUploadVariantKind.entries.joinToString(",") { kind ->
                    val path = if (kind == ImageUploadVariantKind.MEDIUM) {
                        "https://media-service:8080/private/${kind.name}"
                    } else {
                        "/api/media/v1/upload-sessions/$sessionId/variants/${kind.name}/content"
                    }
                    "{\"kind\":\"${kind.name}\",\"contentUploadUrl\":\"$path\"}"
                },
            )
            append("]}")
        }
        server.enqueue(json(response, 201))

        val failure = runCatching {
            uploader(imageBundle()).upload(owner(), listOf("local-photo"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            .isEqualTo("/api/media/v1/upload-sessions")
    }

    @Test
    fun `malformed create response fails parsing before content upload`() =
        runTest {
            server.enqueue(
                json(
                    """{"uploadSessionId":42,"expiresAt":"not-a-session"}""",
                    201,
                ),
            )

            val failure = runCatching {
                uploader().upload(owner(), listOf("local-photo"))
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(JsonDataException::class.java)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
                .isEqualTo("/api/media/v1/upload-sessions")
        }

    @Test
    fun `owner media that never becomes ready times out after bounded API polling`() =
        runTest {
            val mediaId = "11111111-1111-1111-1111-111111111111"
            val sessionId = "22222222-2222-2222-2222-222222222222"
            val processingAsset = assetJson(
                mediaId = mediaId,
                status = "PROCESSING",
                generation = 0,
            )
            server.enqueue(json(sessionJson(sessionId, mediaId), 201))
            server.enqueue(json(uploadedObjectJson(), 201))
            server.enqueue(json(processingAsset, 202))
            repeat(MEDIA_READY_ATTEMPTS) {
                server.enqueue(
                    json("""{"items":[$processingAsset],"next":null}"""),
                )
            }

            val failure = runCatching {
                uploader().upload(owner(), listOf("local-photo"))
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo(MEDIA_READY_TIMEOUT_MESSAGE)
            assertThat(server.requestCount).isEqualTo(3 + MEDIA_READY_ATTEMPTS)
            repeat(3) {
                checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            }
            repeat(MEDIA_READY_ATTEMPTS) {
                val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertThat(request.method).isEqualTo("GET")
                assertThat(request.path).contains("/api/media/v1/assets?")
            }
        }

    @Test
    fun `video keeps compatibility source fields path body and shared transfer finalize key`() =
        runTest {
            val mediaId = "11111111-1111-1111-1111-111111111111"
            val sessionId = "22222222-2222-2222-2222-222222222222"
            val videoBytes = byteArrayOf(
                0x00, 0x00, 0x00, 0x18,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            )
            val payload = PhotoPayload(
                fileName = "inspection.mp4",
                contentType = "video/mp4",
                bytes = videoBytes,
                checksumSha256 = checksum,
            )
            val owner = owner()
            val identity = mediaUploadIdentity(owner, "local-video", payload, 0)
            val ready = assetJson(mediaId, status = "READY", generation = 1)
            server.enqueue(json(sessionJson(sessionId, mediaId), 201))
            server.enqueue(json(uploadedObjectJson(), 201))
            server.enqueue(json(ready, 202))
            server.enqueue(json("""{"items":[$ready],"next":null}"""))

            uploader(payload).upload(owner, listOf("local-video"))

            val requests = List(4) {
                checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            }
            assertThat(requests[0].body.clone().readUtf8()).isEqualTo(
                """{"ownerType":"INVENTORY_FINDING","ownerId":"44444444-4444-4444-4444-444444444444","warehouseId":"33333333-3333-3333-3333-333333333333","context":"INSPECTION","folderId":"${identity.folderId}","fileName":"inspection.mp4","contentType":"video/mp4","contentLength":12,"checksumSha256":"$checksum","sortOrder":0}""",
            )
            assertThat(requests[1].path)
                .isEqualTo("/api/media/v1/upload-sessions/$sessionId/content")
            assertThat(requests[1].getHeader("Content-Type")).isEqualTo("video/mp4")
            assertThat(requests[1].body.clone().readByteArray().toList())
                .containsExactlyElementsIn(videoBytes.toList())
                .inOrder()
            assertThat(requests[1].getHeader("Idempotency-Key"))
                .isEqualTo(requests[2].getHeader("Idempotency-Key"))
            assertThat(requests[1].getHeader("Idempotency-Key"))
                .isEqualTo(identity.finalizeKey)
            assertThat(requests[2].body.clone().readUtf8()).isEqualTo(
                """{"objectVersionId":"object-v1","etag":"etag-v1","checksumSha256":"$checksum"}""",
            )
        }

    private fun uploader(
        payload: MediaUploadPayload = PhotoPayload(
            fileName = "photo.jpg",
            contentType = "image/jpeg",
            bytes = photoBytes,
            checksumSha256 = checksum,
        ),
    ): MediaUploader =
        MediaUploader(
            api = api,
            payloadLoader = { payload },
            testContract = Unit,
        )

    private fun imageBundle(): ImageUploadBundle {
        val bytes = "RIFF0000WEBPtest".encodeToByteArray()
        return ImageUploadBundle(
            fileName = "photo.webp",
            variants = ImageUploadVariantKind.entries.mapIndexed { index, kind ->
                val file = temporaryFolder.newFile("${kind.name}.webp")
                file.writeBytes(bytes)
                ImageUploadVariant(
                    kind = kind,
                    file = file,
                    contentLength = file.length(),
                    checksumSha256 = checksum,
                    width = 320 * (index + 1),
                    height = 180 * (index + 1),
                )
            },
        )
    }

    private fun owner(): MediaOwner =
        MediaOwner(
            ownerType = "INVENTORY_FINDING",
            ownerId = "44444444-4444-4444-4444-444444444444",
            warehouseId = "33333333-3333-3333-3333-333333333333",
            context = "INSPECTION",
        )

    private fun sessionJson(
        sessionId: String,
        mediaId: String,
    ): String =
        """{"uploadSessionId":"$sessionId","mediaId":"$mediaId","expiresAt":"2026-07-28T01:00:00Z","contentUploadUrl":"/api/media/v1/upload-sessions/$sessionId/content"}"""

    private fun uploadedObjectJson(): String =
        """{"objectVersionId":"object-v1","etag":"etag-v1","checksumSha256":"$checksum"}"""

    private fun json(
        body: String,
        status: Int = 200,
    ): MockResponse =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun assetJson(
        mediaId: String,
        status: String,
        generation: Long,
        version: Long = 1,
        rotationDegrees: Int = 0,
    ): String =
        """{"id":"$mediaId","folderId":"55555555-5555-5555-5555-555555555555","clientReferenceId":null,"fileName":"photo.jpg","contentType":"image/jpeg","kind":"IMAGE","status":"$status","version":$version,"generation":$generation,"rotationDegrees":$rotationDegrees,"sortOrder":0,"sizeBytes":4,"createdAt":"2026-07-28T00:00:00Z","variants":[]}"""

}
