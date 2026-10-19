package dev.buhanzaz.rwms.manager.ui

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.uploads.AcceptanceUploadCommand
import dev.buhanzaz.rwms.manager.uploads.BackgroundPhotoStatus
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraftScopeRegistry
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadOperation
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadPhoto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadScope
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadStatus
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadStore
import java.lang.reflect.Proxy
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises retry callbacks through the real upload queue, including failed AtomicFile writes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ManagerUploadRetryTest {
    private lateinit var context: Application
    private lateinit var workManager: WorkManager
    private lateinit var backend: RwmsBackend
    private lateinit var uploads: BackgroundUploadCoordinator
    private lateinit var store: BackgroundUploadStore
    private val dispatcher = StandardTestDispatcher()
    private val commandJob = SupervisorJob()
    private val commandScope = CoroutineScope(commandJob + dispatcher)
    private val state = MutableStateFlow(ManagerUiState())
    private val ownerScope = BackgroundUploadScope("account-1", "warehouse-1")

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        BackgroundUploadStore.resetForTests()
        BackgroundUploadDraftScopeRegistry.replace(null)
        context.filesDir.resolve("background-uploads-v2").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        backend = RwmsBackend(context, "https://rwms.test")
        uploads = BackgroundUploadCoordinator(context)
        uploads.initialize()
        uploads.activateVerifiedScope(ownerScope.ownerAccountId, ownerScope.warehouseId)
        store = BackgroundUploadStore.get(context)
        store.put(failedOperation())
    }

    @After
    fun tearDown() = runBlocking {
        commandScope.cancel()
        backend.auth.logout()
        workManager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        BackgroundUploadStore.resetForTests()
        BackgroundUploadDraftScopeRegistry.replace(null)
        context.filesDir.resolve("background-uploads-v2").deleteRecursively()
        Unit
    }

    @Test
    fun `operation retry reports a failed write and preserves the durable command`() =
        exerciseRetry(photo = false, failWrite = true)

    @Test
    fun `photo retry reports a failed write and preserves every photo`() =
        exerciseRetry(photo = true, failWrite = true)

    @Test
    fun `operation retry still persists and schedules its original command`() =
        exerciseRetry(photo = false, failWrite = false)

    @Test
    fun `photo retry still queues only its selected photo`() =
        exerciseRetry(photo = true, failWrite = false)

    @Test
    fun `cancelled upload initialization does not become a user error`() = runTest(dispatcher) {
        val unavailable = CompletableDeferred<BackgroundUploadCoordinator>()
        unavailable.cancel(CancellationException("Workspace closed"))
        val coordinator = coordinator(unavailable)
        coordinator.retryBackgroundUpload("operation-1")
        coordinator.retryBackgroundPhoto("operation-1", "photo-1")
        runCurrent()
        assertThat(commandJob.children.toList()).isEmpty()
        assertThat(commandJob.isActive).isTrue()
        assertThat(state.value.message).isNull()
        assertThat(store.operation(ownerScope, "operation-1")).isEqualTo(failedOperation())
    }

    private fun exerciseRetry(photo: Boolean, failWrite: Boolean) = runTest(dispatcher, timeout = 30.seconds) {
        val coordinator = coordinator(CompletableDeferred(uploads))
        val queueFile = context.filesDir.resolve("background-uploads-v2/queue.json")
        val originalBytes = queueFile.readBytes()
        val interruptedWrite = queueFile.resolveSibling("queue.json.new")
        if (failWrite) {
            // An existing directory makes the real AtomicFile.startWrite fail without losing its base file.
            check(interruptedWrite.mkdir())
        }
        if (photo) coordinator.retryBackgroundPhoto("operation-1", "photo-1")
        else coordinator.retryBackgroundUpload("operation-1")
        runCurrent()
        commandJob.children.toList().joinAll()

        val work = workManager.getWorkInfosForUniqueWork(
            BackgroundUploadCoordinator.workName(ownerScope, "operation-1"),
        ).get()
        if (failWrite) {
            assertThat(state.value.message).isNotEmpty()
            assertThat(store.operation(ownerScope, "operation-1")).isEqualTo(failedOperation())
            assertThat(queueFile.readBytes()).isEqualTo(originalBytes)
            assertThat(work).isEmpty()
            check(interruptedWrite.delete())
            BackgroundUploadStore.resetForTests()
            val reloaded = BackgroundUploadStore.get(context)
            reloaded.initialize()
            assertThat(reloaded.operation(ownerScope, "operation-1")).isEqualTo(failedOperation())
        } else {
            val updated = requireNotNull(store.operation(ownerScope, "operation-1"))
            assertThat(state.value.message).isNull()
            assertThat(updated.status).isEqualTo(BackgroundUploadStatus.QUEUED)
            assertThat(updated.acceptance).isEqualTo(failedOperation().acceptance)
            assertThat(updated.photos.first().status).isEqualTo(BackgroundPhotoStatus.QUEUED)
            assertThat(updated.photos.last().status).isEqualTo(
                if (photo) BackgroundPhotoStatus.FAILED else BackgroundPhotoStatus.QUEUED,
            )
            assertThat(work).hasSize(1)
            assertThat(work.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        }
    }

    private fun coordinator(backgroundUploads: Deferred<BackgroundUploadCoordinator>) = ManagerWorkspaceCoordinator(
        runtime = ManagerCommandRuntime(state, commandScope, { error("Unexpected HTTP") }, { error("Unexpected logout") }),
        backend = backend,
        backgroundUploads = backgroundUploads,
        preference = WarehousePreference(context),
        commandKeys = StableCommandKeys(),
        catalogWorkspace = Proxy.newProxyInstance(
            ManagerMaintenanceCatalogWorkspacePort::class.java.classLoader,
            arrayOf(ManagerMaintenanceCatalogWorkspacePort::class.java),
        ) { _, method, _ -> error("Unexpected catalog action: ${method.name}") } as ManagerMaintenanceCatalogWorkspacePort,
        editorReset = object : ManagerMaintenanceEditorResetPort {
            override fun invalidateAssetSearch() = error("Unexpected editor reset")
        },
    )

    private fun failedOperation() = BackgroundUploadOperation(
        id = "operation-1", area = BackgroundUploadArea.ACCEPTANCE, title = "Приёмка",
        createdAtEpochMillis = 100, updatedAtEpochMillis = 100,
        status = BackgroundUploadStatus.FAILED, error = "Previous failure",
        ownerAccountId = ownerScope.ownerAccountId, warehouseId = ownerScope.warehouseId,
        acceptance = AcceptanceUploadCommand(
            repairId = "repair-1", warehouseId = ownerScope.warehouseId,
            expectedVersion = 3, idempotencyKey = "original-key",
        ),
        photos = listOf("photo-1", "photo-2").map { id ->
            BackgroundUploadPhoto(
                id = id, sourceName = "$id.jpg", durableUri = "file:///unused-$id.jpg", sortOrder = 0,
                owner = MediaOwner(
                    ownerType = "MAINTENANCE_ACCEPTANCE", ownerId = "repair-1",
                    warehouseId = ownerScope.warehouseId, context = "ACCEPTANCE",
                ),
                status = BackgroundPhotoStatus.FAILED, error = "Previous photo failure",
            )
        },
    )
}
