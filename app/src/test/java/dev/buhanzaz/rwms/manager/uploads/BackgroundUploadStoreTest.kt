package dev.buhanzaz.rwms.manager.uploads

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundUploadStoreTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        BackgroundUploadStore.resetForTests()
        context.filesDir.resolve("background-uploads").deleteRecursively()
    }

    @After
    fun tearDown() {
        BackgroundUploadStore.resetForTests()
        context.filesDir.resolve("background-uploads").deleteRecursively()
    }

    @Test
    fun `operation and per-photo progress survive a process-style store reload`() = runBlocking {
        val store = BackgroundUploadStore.get(context)
        store.initialize()
        val photoFile = store.operationDirectory("operation-1").resolve("photo.jpg")
        photoFile.writeBytes(byteArrayOf(1, 2, 3))
        val operation = operation(
            photo = BackgroundUploadPhoto(
                id = "photo-1",
                sourceName = "door.jpg",
                durableUri = Uri.fromFile(photoFile).toString(),
                owner = owner(),
                sortOrder = 0,
            ),
        )
        store.put(operation)
        store.update(operation.id) { current ->
            current.copy(
                status = BackgroundUploadStatus.RUNNING,
                stage = "Загрузка фото 1 из 1",
                photos = current.photos.map { photo ->
                    photo.copy(
                        status = BackgroundPhotoStatus.READY,
                        reference = MediaReferenceDto("media-1", 4),
                    )
                },
            )
        }

        BackgroundUploadStore.resetForTests()
        val restoredStore = BackgroundUploadStore.get(context)
        restoredStore.initialize()
        val restored = restoredStore.operation(operation.id)

        assertThat(restored?.status).isEqualTo(BackgroundUploadStatus.RUNNING)
        assertThat(restored?.stage).isEqualTo("Загрузка фото 1 из 1")
        assertThat(restored?.photos?.single()?.status).isEqualTo(BackgroundPhotoStatus.READY)
        assertThat(restored?.photos?.single()?.reference)
            .isEqualTo(MediaReferenceDto("media-1", 4))
        assertThat(photoFile.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `successful removal deletes both queue row and durable originals`() = runBlocking {
        val store = BackgroundUploadStore.get(context)
        store.initialize()
        val directory = store.operationDirectory("operation-1")
        directory.resolve("photo.jpg").writeBytes(byteArrayOf(9))
        store.put(operation())

        store.remove("operation-1")

        assertThat(store.operations.value).isEmpty()
        assertThat(directory.exists()).isFalse()
    }

    @Test
    fun `concurrent initialization restores one durable queue`() = runBlocking {
        val persisted = BackgroundUploadStore.get(context)
        persisted.initialize()
        persisted.put(operation())

        BackgroundUploadStore.resetForTests()
        val restored = BackgroundUploadStore.get(context)
        coroutineScope {
            List(8) {
                async(Dispatchers.Default) { restored.initialize() }
            }.awaitAll()
        }

        assertThat(restored.operations.value).containsExactly(operation())
        assertThat(restored.operation("operation-1")).isEqualTo(operation())
    }

    private fun operation(
        photo: BackgroundUploadPhoto? = null,
    ) = BackgroundUploadOperation(
        id = "operation-1",
        area = BackgroundUploadArea.ACCEPTANCE,
        title = "Приёмка ремонта 231226",
        createdAtEpochMillis = 100,
        updatedAtEpochMillis = 100,
        photos = listOfNotNull(photo),
        acceptance = AcceptanceUploadCommand(
            repairId = "repair-1",
            warehouseId = "warehouse-1",
            expectedVersion = 3,
            idempotencyKey = "command-1",
        ),
    )

    private fun owner() = MediaOwner(
        ownerType = "MAINTENANCE_ACCEPTANCE",
        ownerId = "repair-1",
        warehouseId = "warehouse-1",
        context = "ACCEPTANCE",
    )
}
