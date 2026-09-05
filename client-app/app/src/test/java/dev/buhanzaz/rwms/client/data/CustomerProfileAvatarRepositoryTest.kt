package dev.buhanzaz.rwms.client.data

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies cropped-avatar transport, immutable upload identity and READY/profile fencing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerProfileAvatarRepositoryTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val profile = CustomerProfile(
        id = "profile-a",
        version = 7,
        entityType = CustomerEntityType.INDIVIDUAL,
        firstName = "Иван",
        lastName = "Петров",
        phone = "+79990000000",
    )
    private val scope = CustomerProfileAvatarUploadScope(
        profileVersion = 8,
        ownerType = "LOGISTICS_CUSTOMER_PROFILE",
        ownerId = "profile-a",
        warehouseId = "warehouse-a",
        context = "PROFILE_AVATAR",
    )
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0x02, 0xff.toByte(), 0xd9.toByte())

    @Test
    fun `uploads exact cropped bytes and binds READY generation with prepared profile version`() = runTest {
        val remote = AvatarApi()

        val result = repository(remote).uploadProfileAvatar(profile, scope.warehouseId, jpeg)

        assertThat(remote.prepared).containsExactly(PrepareCustomerProfileAvatarUploadRequest(7, "warehouse-a"))
        val created = remote.created.single()
        assertThat(created.ownerType).isEqualTo(scope.ownerType)
        assertThat(created.ownerId).isEqualTo(profile.id)
        assertThat(created.warehouseId).isEqualTo(scope.warehouseId)
        assertThat(created.context).isEqualTo(scope.context)
        assertThat(created.documentId).isNull()
        assertThat(created.lineId).isNull()
        assertThat(created.fileName).isEqualTo("avatar.jpg")
        assertThat(created.contentType).isEqualTo("image/jpeg")
        assertThat(created.contentLength).isEqualTo(jpeg.size.toLong())
        assertThat(created.checksumSha256).isEqualTo(checksum(jpeg))
        assertThat(remote.bodies.single()).isEqualTo(jpeg)
        assertThat(remote.bodyContentTypes).containsExactly("image/jpeg")
        assertThat(remote.bodyLengths).containsExactly(jpeg.size.toLong())
        assertThat(remote.finalized).containsExactly(
            FinalizeCustomerMediaUploadRequest("object-version", "object-etag", "uploaded-checksum"),
        )
        assertThat(remote.bound).containsExactly(SetCustomerProfileAvatarRequest(8, "media-a", 3))
        assertThat(result).isEqualTo(profile.copy(version = 9))
        assertThat(remote.calls).containsExactly(
            "prepareProfileAvatarUpload", "createMediaUpload", "uploadMediaContent",
            "finalizeMediaUpload", "setProfileAvatar",
        ).inOrder()
    }

    @Test
    fun `identical crop keeps upload key across recreation and changed crop gets a new key`() = runTest {
        val remote = AvatarApi()
        repeat(2) { repository(remote).uploadProfileAvatar(profile, scope.warehouseId, jpeg.copyOf()) }
        val changed = jpeg.copyOf().also { it[2] = 0x03 }
        repository(remote).uploadProfileAvatar(profile, scope.warehouseId, changed)

        val expectedKey = UUID.nameUUIDFromBytes(
            "${scope.ownerId}:profile-avatar:${checksum(jpeg)}".toByteArray(),
        ).toString()
        assertThat(remote.keys.take(6)).containsExactlyElementsIn(List(6) { expectedKey })
        assertThat(remote.keys[6]).isNotEqualTo(expectedKey)
        assertThat(remote.keys.drop(6).distinct()).hasSize(1)
        assertThat(remote.created.map { it.folderId }.distinct()).hasSize(1)
    }

    @Test
    fun `caller cannot change upload body or checksum after preparation starts`() = runTest {
        val mutableBytes = jpeg.copyOf()
        val remote = AvatarApi(onPrepare = { mutableBytes.fill(0) })

        repository(remote).uploadProfileAvatar(profile, scope.warehouseId, mutableBytes)

        assertThat(remote.bodies.single()).isEqualTo(jpeg)
        assertThat(remote.created.single().checksumSha256).isEqualTo(checksum(jpeg))
    }

    @Test
    fun `empty and excessive image bytes are rejected before creating a server scope`() = runTest {
        val remote = AvatarApi()
        for (bytes in listOf(byteArrayOf(), ByteArray(8 * 1024 * 1024 + 1))) {
            val failure = runCatching {
                repository(remote).uploadProfileAvatar(profile, scope.warehouseId, bytes)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CustomerApiException::class.java)
            assertThat((failure as CustomerApiException).status).isEqualTo(422)
        }
        assertThat(remote.calls).isEmpty()
    }

    @Test
    fun `every mismatched owner scope is rejected before uploading or binding`() = runTest {
        val invalidScopes = listOf(
            scope.copy(ownerType = "LOGISTICS_SHIPMENT"),
            scope.copy(ownerId = "another-profile"),
            scope.copy(warehouseId = "another-warehouse"),
            scope.copy(context = "SHIPMENT"),
        )
        for (invalid in invalidScopes) {
            val remote = AvatarApi(preparedScope = invalid)
            val failure = runCatching {
                repository(remote).uploadProfileAvatar(profile, scope.warehouseId, jpeg)
            }.exceptionOrNull()
            assertThat((failure as CustomerApiException).status).isEqualTo(503)
            assertThat(remote.calls).containsExactly("prepareProfileAvatarUpload")
        }
    }

    @Test
    fun `processing avatar binds only the exact uploaded asset after it becomes READY`() = runTest {
        val remote = AvatarApi(finalizedStatus = "PROCESSING")

        repository(remote).uploadProfileAvatar(profile, scope.warehouseId, jpeg)

        assertThat(remote.bound).containsExactly(SetCustomerProfileAvatarRequest(8, "media-a", 4))
        assertThat(remote.calls.indexOf("mediaAssets")).isLessThan(remote.calls.indexOf("setProfileAvatar"))
    }

    @Test
    fun `failed processing and bounded processing timeout never bind an avatar`() = runTest {
        for (status in listOf("FAILED", "PROCESSING")) {
            val remote = AvatarApi(finalizedStatus = status, polledStatus = status)
            val failure = runCatching {
                repository(remote).uploadProfileAvatar(profile, scope.warehouseId, jpeg)
            }.exceptionOrNull()

            assertThat((failure as CustomerApiException).status).isEqualTo(if (status == "FAILED") 422 else 503)
            assertThat(remote.bound).isEmpty()
            assertThat(remote.calls.count { it == "mediaAssets" }).isEqualTo(if (status == "FAILED") 0 else 45)
        }
    }

    @Test
    fun `bind conflict and lost response remain errors without compensating media deletion`() = runTest {
        for (bindFailure in listOf(
            CustomerApiException(409, "Профиль изменился"),
            CustomerApiException(null, "Ответ на привязку потерян"),
        )) {
            val remote = AvatarApi(bindFailure = bindFailure)

            val failure = runCatching {
                repository(remote).uploadProfileAvatar(profile, scope.warehouseId, jpeg)
            }.exceptionOrNull()

            assertThat(failure).isSameInstanceAs(bindFailure)
            assertThat(remote.bound).containsExactly(SetCustomerProfileAvatarRequest(8, "media-a", 3))
            assertThat(remote.calls.last()).isEqualTo("setProfileAvatar")
        }
    }

    private fun repository(remote: AvatarApi) = CustomerRepository(
        context = context,
        api = remote.api,
        json = json,
        workflowStore = CustomerWorkflowStore(context, json),
    )

    private fun checksum(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private inner class AvatarApi(
        private val preparedScope: CustomerProfileAvatarUploadScope = scope,
        private val finalizedStatus: String = "READY",
        private val polledStatus: String = "READY",
        private val onPrepare: () -> Unit = {},
        private val bindFailure: CustomerApiException? = null,
    ) {
        val calls = mutableListOf<String>()
        val prepared = mutableListOf<PrepareCustomerProfileAvatarUploadRequest>()
        val created = mutableListOf<CreateCustomerMediaUploadRequest>()
        val finalized = mutableListOf<FinalizeCustomerMediaUploadRequest>()
        val bound = mutableListOf<SetCustomerProfileAvatarRequest>()
        val keys = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray>()
        val bodyContentTypes = mutableListOf<String>()
        val bodyLengths = mutableListOf<Long>()
        val api = Proxy.newProxyInstance(
            CustomerApi::class.java.classLoader,
            arrayOf(CustomerApi::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            calls += method.name
            when (method.name) {
                "prepareProfileAvatarUpload" -> {
                    prepared += args[0] as PrepareCustomerProfileAvatarUploadRequest
                    onPrepare()
                    preparedScope
                }
                "createMediaUpload" -> {
                    keys += args[0] as String
                    created += args[1] as CreateCustomerMediaUploadRequest
                    MediaUploadSession("upload-a", "media-a", "2026-09-06T12:00:00Z", "/api/media/v1/content")
                }
                "uploadMediaContent" -> {
                    assertThat(args[0]).isEqualTo("/api/media/v1/content")
                    keys += args[1] as String
                    val body = args[2] as RequestBody
                    bodies += Buffer().also(body::writeTo).readByteArray()
                    bodyContentTypes += body.contentType().toString()
                    bodyLengths += body.contentLength()
                    UploadedMediaObject("object-version", "object-etag", "uploaded-checksum")
                }
                "finalizeMediaUpload" -> {
                    assertThat(args[0]).isEqualTo("upload-a")
                    keys += args[1] as String
                    finalized += args[2] as FinalizeCustomerMediaUploadRequest
                    CustomerMediaAsset("media-a", finalizedStatus, 3)
                }
                "mediaAssets" -> {
                    assertThat(args[0]).isEqualTo(scope.ownerType)
                    assertThat(args[1]).isEqualTo(scope.ownerId)
                    assertThat(args[2]).isNull()
                    assertThat(args[3]).isNull()
                    assertThat(args[4]).isEqualTo(scope.warehouseId)
                    assertThat(args[5]).isEqualTo(scope.context)
                    CustomerMediaPage(listOf(
                        CustomerMediaAsset("another-media", "READY", 99),
                        CustomerMediaAsset("media-a", polledStatus, 4),
                    ))
                }
                "setProfileAvatar" -> {
                    bound += args[0] as SetCustomerProfileAvatarRequest
                    bindFailure?.let { throw it }
                    profile.copy(version = 9)
                }
                else -> error("Unexpected CustomerApi call: ${method.name}")
            }
        } as CustomerApi
    }
}
