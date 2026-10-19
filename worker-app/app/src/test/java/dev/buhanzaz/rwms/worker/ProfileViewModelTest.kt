package dev.buhanzaz.rwms.worker

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.PendingPayloadCipher
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.network.MediaAssetDto
import dev.buhanzaz.rwms.worker.core.network.MediaAssetPageDto
import dev.buhanzaz.rwms.worker.core.network.SafeMediaVariantDto
import dev.buhanzaz.rwms.worker.core.network.UploadSessionDto
import dev.buhanzaz.rwms.worker.core.network.UploadedObjectDto
import dev.buhanzaz.rwms.worker.core.network.WorkerContextDto
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayApi
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.WorkerIdentityDto
import dev.buhanzaz.rwms.worker.core.network.WorkerOfflineLeaseDto
import dev.buhanzaz.rwms.worker.core.network.WorkerProfileAvatarScopeDto
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response

/** Exercises actual avatar loading/upload callbacks when an obsolete request completes late. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: WorkerDatabase
    private lateinit var viewModel: ProfileViewModel
    private val oldReady = CompletableDeferred<Unit>()
    private val uploadedReady = CompletableDeferred<Unit>()
    private var uploadFailure: Throwable? = null
    private var uploads = 0
    private var contextReads = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, WorkerDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val store = WorkerLocalStore(database, PendingPayloadCipher(context), Json)
        val api = Proxy.newProxyInstance(
            WorkerGatewayApi::class.java.classLoader,
            arrayOf(WorkerGatewayApi::class.java),
        ) { _, method, args ->
            val response: suspend () -> Any? = {
                when (method.name) {
                    "workerContext" -> {
                        contextReads += 1
                        Response.success(
                            WorkerContextDto(
                                WorkerIdentityDto("worker", "warehouse", "worker", "Worker"),
                                emptyList(), emptyList(), emptyList(), null, NOW, 1,
                                WorkerOfflineLeaseDto("lease", NOW, NOW, 1),
                            ),
                        )
                    }
                    "prepareWorkerProfileAvatarScope" -> Response.success(
                        WorkerProfileAvatarScopeDto("WORKER_PROFILE", "worker", "warehouse", "AVATAR"),
                    )
                    "mediaAssets" -> Response.success(MediaAssetPageDto(listOf(asset("old"))))
                    "mediaContent" -> {
                        val old = args!![0].toString().contains(OLD_ID)
                        // Deliberately emulate a transport that finishes even after cancellation.
                        withContext(NonCancellable) {
                            (if (old) oldReady else uploadedReady).await()
                        }
                        Response.success(imageBody(if (old) 8 else 16))
                    }
                    "createUploadSession" -> {
                        uploads += 1
                        uploadFailure?.let { throw it }
                        Response.success(UploadSessionDto("upload", "new", NOW, "/api/media/v1/upload-sessions/$NEW_ID/content", emptyList()))
                    }
                    "uploadMediaContent" -> {
                        assertThat(args!![0]).isEqualTo("/api/media/v1/upload-sessions/$NEW_ID/content")
                        Response.success(UploadedObjectDto("version", "etag", "a".repeat(64)))
                    }
                    "finalizeUploadSession" -> Response.success(asset("new"))
                    else -> error("Unexpected profile API call: ${method.name}")
                }
            }
            @Suppress("UNCHECKED_CAST")
            response.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
        } as WorkerGatewayApi
        viewModel = ProfileViewModel(store, WorkerGatewayClient(api, Json), context, dispatcher)
    }

    @After
    fun tearDown() {
        oldReady.complete(Unit)
        uploadedReady.complete(Unit)
        viewModel.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `late refresh cannot replace the uploaded avatar`() = runTest(dispatcher) {
        observeAndBind()
        var successes = 0
        viewModel.uploadAvatar(bitmap(16)) { successes += 1 }
        viewModel.uploadAvatar(bitmap(16)) { successes += 1 }
        runCurrent()
        assertThat(uploads).isEqualTo(1)
        assertThat(viewModel.state.value.isAvatarUploading).isTrue()

        uploadedReady.complete(Unit)
        runCurrent()
        assertThat(viewModel.state.value.avatar?.width).isEqualTo(16)
        assertThat(successes).isEqualTo(1)
        oldReady.complete(Unit)
        runCurrent()

        assertThat(viewModel.state.value.avatar?.width).isEqualTo(16)
        assertThat(viewModel.state.value.isAvatarUploading).isFalse()
        assertThat(viewModel.state.value.avatarError).isNull()
    }

    @Test
    fun `refresh completing during upload cannot clear its busy state`() = runTest(dispatcher) {
        observeAndBind()
        viewModel.uploadAvatar(bitmap(16)) {}
        runCurrent()
        oldReady.complete(Unit)
        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertThat(viewModel.state.value.isAvatarUploading).isTrue()
        assertThat(viewModel.state.value.isAvatarLoading).isFalse()
        assertThat(viewModel.state.value.avatarError).isNull()
        assertThat(contextReads).isEqualTo(1)
        uploadedReady.complete(Unit)
        runCurrent()
        assertThat(viewModel.state.value.avatar?.width).isEqualTo(16)
        assertThat(viewModel.state.value.isAvatarUploading).isFalse()
    }

    @Test
    fun `upload failure keeps the current image and releases the busy state`() = runTest(dispatcher) {
        oldReady.complete(Unit)
        observeAndBind()
        assertThat(viewModel.state.value.avatar?.width).isEqualTo(8)
        uploadFailure = IOException("connection failed")
        var successes = 0
        viewModel.uploadAvatar(bitmap(16)) { successes += 1 }
        runCurrent()

        assertThat(viewModel.state.value.avatar?.width).isEqualTo(8)
        assertThat(viewModel.state.value.isAvatarUploading).isFalse()
        assertThat(viewModel.state.value.avatarError).isNotEmpty()
        assertThat(successes).isEqualTo(0)
    }

    @Test
    fun `changing the bound worker fences an unfinished upload callback`() = runTest(dispatcher) {
        oldReady.complete(Unit)
        observeAndBind()
        var successes = 0
        viewModel.uploadAvatar(bitmap(16)) { successes += 1 }
        runCurrent()
        viewModel.bind("another-worker")
        runCurrent()
        uploadedReady.complete(Unit)
        runCurrent()

        assertThat(viewModel.state.value.avatar?.width).isEqualTo(8)
        assertThat(viewModel.state.value.isAvatarUploading).isFalse()
        assertThat(viewModel.state.value.avatarError).isNull()
        assertThat(successes).isEqualTo(0)
    }

    private fun TestScope.observeAndBind() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.bind("worker")
        runCurrent()
    }

    private fun asset(id: String) = MediaAssetDto(
        id, "folder", null, "$id.png", "image/png", "IMAGE", "READY", 1, 1, 0, 0, null, NOW,
        listOf(SafeMediaVariantDto("SMALL", "image/png", "/api/media/v1/assets/${if (id == "old") OLD_ID else NEW_ID}/variants/SMALL/content", 16, 16)),
    )

    private fun bitmap(edge: Int) = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)

    private fun imageBody(edge: Int) = ByteArrayOutputStream().also { output ->
        bitmap(edge).compress(Bitmap.CompressFormat.PNG, 100, output)
    }.toByteArray().toResponseBody("image/png".toMediaType())

    private companion object {
        const val OLD_ID = "00000000-0000-0000-0000-000000000001"
        const val NEW_ID = "00000000-0000-0000-0000-000000000002"
        const val NOW = "2026-09-06T09:00:00Z"
    }
}
