package dev.buhanzaz.rwms.manager.media

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploaderHttpContractTest {
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
    fun `whole upload retries with stable separate create and transfer keys until ready`() =
        runTest {
            val mediaId = "11111111-1111-1111-1111-111111111111"
            val sessionId = "22222222-2222-2222-2222-222222222222"
            val warehouseId = "33333333-3333-3333-3333-333333333333"
            val ownerId = "44444444-4444-4444-4444-444444444444"
            val createResponse =
                """{"uploadSessionId":"$sessionId","mediaId":"$mediaId","expiresAt":"2026-07-28T01:00:00Z","contentUploadUrl":"/api/media/v1/upload-sessions/$sessionId/content"}"""
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
            server.enqueue(json(createResponse))
            server.enqueue(json(objectResponse))
            server.enqueue(json(processingAsset, 202))
            server.enqueue(json("""{"items":[$processingAsset],"next":null}"""))
            server.enqueue(json("""{"items":[$readyAsset],"next":null}"""))

            val references = uploader().upload(
                owner = MediaOwner(
                    ownerType = "MAINTENANCE_ESTIMATE",
                    ownerId = ownerId,
                    warehouseId = warehouseId,
                    context = "ESTIMATE",
                ),
                photoUris = listOf("local-photo"),
            )

            assertThat(references.single().mediaId).isEqualTo(mediaId)
            assertThat(references.single().generation).isEqualTo(2)
            val requests = List(8) {
                checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            }
            val createKey = requests[0].getHeader("Idempotency-Key")
            val uploadKey = requests[1].getHeader("Idempotency-Key")
            assertThat(createKey).isNotNull()
            assertThat(uploadKey).isNotNull()
            assertThat(createKey).isNotEqualTo(uploadKey)
            assertThat(requests[3].getHeader("Idempotency-Key")).isEqualTo(createKey)
            listOf(1, 2, 4, 5).forEach { index ->
                assertThat(requests[index].getHeader("Idempotency-Key"))
                    .isEqualTo(uploadKey)
            }
            assertThat(requests[0].body.readUtf8()).isEqualTo(requests[3].body.readUtf8())
            assertThat(requests[0].path)
                .isEqualTo("/api/media/v1/upload-sessions")
            assertThat(requests[1].path)
                .isEqualTo("/api/media/v1/upload-sessions/$sessionId/content")
            assertThat(requests[2].path)
                .isEqualTo("/api/media/v1/upload-sessions/$sessionId/complete")
            assertThat(requests[1].method).isEqualTo("PUT")
            assertThat(requests[1].getHeader("Content-Type")).isEqualTo("image/jpeg")
            assertThat(requests[1].getHeader("Content-Length"))
                .isEqualTo(photoBytes.size.toString())
            assertThat(requests[1].body.readByteArray().toList())
                .containsExactlyElementsIn(photoBytes.toList())
                .inOrder()
            assertThat(requests[2].body.readUtf8()).isEqualTo(
                """{"objectVersionId":"object-v1","etag":"etag-v1","checksumSha256":"$checksum"}""",
            )
            assertThat(requests[6].path).isEqualTo(
                "/api/media/v1/assets?ownerType=MAINTENANCE_ESTIMATE" +
                    "&ownerId=$ownerId&warehouseId=$warehouseId" +
                    "&context=ESTIMATE&limit=100",
            )
            assertThat(requests[7].path).isEqualTo(requests[6].path)
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
    fun `rotation keeps one media id and waits for its new canonical generation`() = runTest {
        val mediaId = "11111111-1111-1111-1111-111111111111"
        val readyBeforeRotation = assetJson(
            mediaId = mediaId,
            status = "READY",
            generation = 1,
            version = 7,
            rotationDegrees = 0,
        )
        val processing = assetJson(
            mediaId = mediaId,
            status = "PROCESSING",
            generation = 1,
            version = 8,
            rotationDegrees = 0,
        )
        val readyAfterRotation = assetJson(
            mediaId = mediaId,
            status = "READY",
            generation = 2,
            version = 9,
            rotationDegrees = 90,
        )
        server.enqueue(json(processing, 202))
        server.enqueue(json("""{"items":[$processing],"next":null}"""))
        server.enqueue(json("""{"items":[$readyAfterRotation],"next":null}"""))

        val reference = uploader().rotate(
            owner = owner(),
            asset = mediaAsset(readyBeforeRotation),
            rotationDegrees = 90,
        )

        assertThat(reference).isEqualTo(MediaReferenceDto(mediaId, 2))
        val rotation = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertThat(rotation.method).isEqualTo("POST")
        assertThat(rotation.path).isEqualTo(
            "/api/media/v1/assets/$mediaId/rotation?ownerType=INVENTORY_FINDING" +
                "&ownerId=${owner().ownerId}&warehouseId=${owner().warehouseId}&context=INSPECTION",
        )
        assertThat(rotation.getHeader("Idempotency-Key")).isNotEmpty()
        assertThat(rotation.body.readUtf8())
            .isEqualTo("""{"rotationDegrees":90,"expectedVersion":7}""")
        repeat(2) {
            val ownerRead = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertThat(ownerRead.method).isEqualTo("GET")
            assertThat(ownerRead.path).contains("/api/media/v1/assets?")
        }
    }

    private fun uploader(): MediaUploader =
        MediaUploader(
            api = api,
            payloadLoader = {
                PhotoPayload(
                    fileName = "photo.jpg",
                    contentType = "image/jpeg",
                    bytes = photoBytes,
                    checksumSha256 = checksum,
                )
            },
            testContract = Unit,
        )

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

    private fun mediaAsset(json: String): MediaAssetDto =
        Moshi.Builder()
            .add(ExplicitNullJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter(MediaAssetDto::class.java)
            .fromJson(json)!!
}
