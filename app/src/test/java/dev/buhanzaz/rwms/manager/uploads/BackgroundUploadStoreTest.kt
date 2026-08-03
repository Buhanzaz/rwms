package dev.buhanzaz.rwms.manager.uploads

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
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
    fun `operation and per-photo progress survive a process-style store reload`() {
        val store = BackgroundUploadStore.get(context)
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
        val restored = BackgroundUploadStore.get(context).operation(operation.id)

        assertThat(restored?.status).isEqualTo(BackgroundUploadStatus.RUNNING)
        assertThat(restored?.stage).isEqualTo("Загрузка фото 1 из 1")
        assertThat(restored?.photos?.single()?.status).isEqualTo(BackgroundPhotoStatus.READY)
        assertThat(restored?.photos?.single()?.reference)
            .isEqualTo(MediaReferenceDto("media-1", 4))
        assertThat(photoFile.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `successful removal deletes both queue row and durable originals`() {
        val store = BackgroundUploadStore.get(context)
        val directory = store.operationDirectory("operation-1")
        directory.resolve("photo.jpg").writeBytes(byteArrayOf(9))
        store.put(operation())

        store.remove("operation-1")

        assertThat(store.operations.value).isEmpty()
        assertThat(directory.exists()).isFalse()
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
