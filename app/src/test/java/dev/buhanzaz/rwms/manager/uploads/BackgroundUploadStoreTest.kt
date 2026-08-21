package dev.buhanzaz.rwms.manager.uploads

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.imageVariantDirectoryFor
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.WarehouseAccessDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
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
        BackgroundUploadDraftScopeRegistry.replace(null)
        context.filesDir.resolve("background-uploads").deleteRecursively()
        context.filesDir.resolve("background-uploads-v2").deleteRecursively()
        context.filesDir.resolve("background-uploads-quarantine").deleteRecursively()
    }

    @After
    fun tearDown() {
        BackgroundUploadStore.resetForTests()
        BackgroundUploadDraftScopeRegistry.replace(null)
        context.filesDir.resolve("background-uploads").deleteRecursively()
        context.filesDir.resolve("background-uploads-v2").deleteRecursively()
        context.filesDir.resolve("background-uploads-quarantine").deleteRecursively()
    }

    @Test
    fun `operation and per-photo progress survive a process-style store reload`() = runBlocking {
        val store = BackgroundUploadStore.get(context)
        store.initialize()
        store.activateScope(scopeA)
        val photoFile = store.operationDirectory(scopeA, "operation-1").resolve("photo.jpg")
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
        store.update(scopeA, operation.id) { current ->
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
        restoredStore.activateScope(scopeA)
        val restored = restoredStore.operation(scopeA, operation.id)

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
        store.activateScope(scopeA)
        val directory = store.operationDirectory(scopeA, "operation-1")
        directory.resolve("photo.jpg").writeBytes(byteArrayOf(9))
        store.put(operation())

        store.remove(scopeA, "operation-1")

        assertThat(store.operations.value).isEmpty()
        assertThat(directory.exists()).isFalse()
    }

    @Test
    fun `ready cleanup deletes scoped original and variants but retains queue recovery state`() =
        runBlocking {
            val store = BackgroundUploadStore.get(context)
            store.initialize()
            store.activateScope(scopeA)
            val directory = store.operationDirectory(scopeA, "operation-1")
            val original = directory.resolve("photo.jpg").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val variants = imageVariantDirectoryFor(original).apply { mkdirs() }
            variants.resolve("SMALL.webp").writeBytes(byteArrayOf(4, 5, 6))
            val readyPhoto = BackgroundUploadPhoto(
                id = "photo-1",
                sourceName = "photo.jpg",
                durableUri = Uri.fromFile(original).toString(),
                owner = owner(),
                sortOrder = 0,
                status = BackgroundPhotoStatus.READY,
                reference = MediaReferenceDto("media-1", 1),
            )
            store.put(operation(photo = readyPhoto))

            val deleted = store.deleteReadyPhotoFiles(
                scopeA,
                "operation-1",
                readyPhoto.durableUri,
            )

            assertThat(deleted).isTrue()
            assertThat(original.exists()).isFalse()
            assertThat(variants.exists()).isFalse()
            assertThat(store.operation(scopeA, "operation-1")?.photos?.single()?.reference)
                .isEqualTo(MediaReferenceDto("media-1", 1))
        }

    @Test
    fun `cleanup refuses queued originals gallery uris and files outside operation scope`() =
        runBlocking {
            val store = BackgroundUploadStore.get(context)
            store.initialize()
            store.activateScope(scopeA)
            val directory = store.operationDirectory(scopeA, "operation-1")
            val queuedFile = directory.resolve("queued.jpg").apply { writeBytes(byteArrayOf(1)) }
            val queued = BackgroundUploadPhoto(
                id = "queued",
                sourceName = "queued.jpg",
                durableUri = Uri.fromFile(queuedFile).toString(),
                owner = owner(),
                sortOrder = 0,
            )
            store.put(operation(photo = queued))

            assertThat(
                store.deleteReadyPhotoFiles(scopeA, "operation-1", queued.durableUri),
            ).isFalse()
            assertThat(queuedFile.exists()).isTrue()

            val external = context.cacheDir.resolve("user-gallery.jpg").apply {
                writeBytes(byteArrayOf(2))
            }
            val protectedUris = listOf(
                Uri.fromFile(external).toString(),
                "content://gallery/user-owned-photo",
            )
            protectedUris.forEachIndexed { index, uri ->
                val photo = queued.copy(
                    id = "protected-$index",
                    durableUri = uri,
                    reference = MediaReferenceDto("media-$index", 1),
                )
                val operationId = "protected-$index"
                store.put(operation(photo = photo, id = operationId))
                assertThat(store.deleteReadyPhotoFiles(scopeA, operationId, uri)).isFalse()
            }
            assertThat(external.exists()).isTrue()
        }

    @Test
    fun `cancelled operation stays removed when a running worker reports progress`() = runBlocking {
        val store = BackgroundUploadStore.get(context)
        store.initialize()
        store.activateScope(scopeA)
        val directory = store.operationDirectory(scopeA, "operation-1")
        directory.resolve("photo.jpg").writeBytes(byteArrayOf(9))
        store.put(operation())

        store.remove(scopeA, "operation-1")
        val workerUpdate = store.update(scopeA, "operation-1") { current ->
            current.copy(
                status = BackgroundUploadStatus.RUNNING,
                stage = "Загрузка фото 1 из 1",
            )
        }

        assertThat(workerUpdate).isNull()
        assertThat(store.operations.value).isEmpty()
        assertThat(directory.exists()).isFalse()
    }

    @Test
    fun `concurrent initialization restores one durable queue`() = runBlocking {
        val persisted = BackgroundUploadStore.get(context)
        persisted.initialize()
        persisted.activateScope(scopeA)
        persisted.put(operation())

        BackgroundUploadStore.resetForTests()
        val restored = BackgroundUploadStore.get(context)
        coroutineScope {
            List(8) {
                async(Dispatchers.Default) { restored.initialize() }
            }.awaitAll()
        }
        restored.activateScope(scopeA)

        assertThat(restored.operations.value).containsExactly(operation())
        assertThat(restored.operation(scopeA, "operation-1")).isEqualTo(operation())
    }

    @Test
    fun `same warehouse is isolated by account across restart and mutation`() = runBlocking {
        val store = BackgroundUploadStore.get(context)
        store.initialize()
        val accountA = operation(id = "operation-a", scope = scopeA).copy(
            status = BackgroundUploadStatus.RUNNING,
            stage = "Загрузка фото",
        )
        val accountB = operation(id = "operation-b", scope = scopeB)
        store.put(accountA)
        store.put(accountB)

        store.activateScope(scopeA)
        assertThat(store.operations.value).containsExactly(accountA)
        assertThat(store.operation(scopeA, accountB.id)).isNull()
        assertThat(store.update(scopeA, accountB.id) { it.copy(stage = "wrong") }).isNull()

        store.activateScope(scopeB)
        assertThat(store.operations.value).containsExactly(accountB)
        assertThat(store.operation(scopeB, accountA.id)).isNull()
        assertThat(store.update(scopeB, accountA.id) { it.copy(stage = "retry") }).isNull()

        BackgroundUploadStore.resetForTests()
        val restored = BackgroundUploadStore.get(context)
        restored.initialize()
        restored.activateScope(scopeB)

        assertThat(restored.operations.value).containsExactly(accountB)
        assertThat(restored.operation(scopeB, accountB.id)).isEqualTo(accountB)
    }

    @Test
    fun `same operation id uses different durable directories for each scope`() = runBlocking {
        val store = BackgroundUploadStore.get(context)
        store.initialize()

        val accountA = store.operationDirectory(scopeA, "same-operation")
        val accountB = store.operationDirectory(scopeB, "same-operation")
        val otherWarehouse = store.operationDirectory(scopeAOtherWarehouse, "same-operation")

        assertThat(accountA).isNotEqualTo(accountB)
        assertThat(accountA).isNotEqualTo(otherWarehouse)
        assertThat(accountA.canonicalPath).doesNotContain(scopeA.ownerAccountId)
        assertThat(accountA.canonicalPath).doesNotContain(scopeA.warehouseId)
    }

    @Test
    fun `ownerless schema one queue and originals are quarantined and ignored`() = runBlocking {
        val legacyRoot = context.filesDir.resolve("background-uploads").apply { mkdirs() }
        legacyRoot.resolve("operation-legacy").apply { mkdirs() }
            .resolve("photo.jpg").writeBytes(byteArrayOf(4, 5, 6))
        legacyRoot.resolve("queue.json").writeText(
            """
            {
              "schemaVersion": 1,
              "operations": [{
                "id": "operation-legacy",
                "area": "ACCEPTANCE",
                "title": "Legacy ownerless upload",
                "createdAtEpochMillis": 100,
                "updatedAtEpochMillis": 100,
                "acceptance": {
                  "repairId": "repair-1",
                  "warehouseId": "warehouse-1",
                  "expectedVersion": 3,
                  "idempotencyKey": "command-1"
                }
              }]
            }
            """.trimIndent(),
        )

        val store = BackgroundUploadStore.get(context)
        store.initialize()
        store.activateScope(scopeA)

        assertThat(store.operations.value).isEmpty()
        assertThat(store.operation(scopeA, "operation-legacy")).isNull()
        assertThat(legacyRoot.exists()).isFalse()
        val quarantinedNames = context.filesDir.resolve("background-uploads-quarantine")
            .walkTopDown()
            .map { it.name }
            .toList()
        assertThat(quarantinedNames).containsAtLeast("queue.json", "photo.jpg")
        Unit
    }

    @Test
    fun `work identity changes with account and warehouse`() {
        val operationId = "same-operation"

        val accountA = BackgroundUploadCoordinator.workName(scopeA, operationId)
        val accountB = BackgroundUploadCoordinator.workName(scopeB, operationId)
        val otherWarehouse = BackgroundUploadCoordinator.workName(
            scopeAOtherWarehouse,
            operationId,
        )

        assertThat(accountA).isNotEqualTo(accountB)
        assertThat(accountA).isNotEqualTo(otherWarehouse)
        assertThat(accountA).doesNotContain(scopeA.ownerAccountId)
        assertThat(accountA).doesNotContain(scopeA.warehouseId)
    }

    @Test
    fun `draft captures account before logout and cannot be created while unverified`() {
        BackgroundUploadDraftScopeRegistry.replace(scopeA)
        val accountA = draft()

        BackgroundUploadDraftScopeRegistry.replace(scopeB)
        val accountB = draft()

        assertThat(accountA.scope).isEqualTo(scopeA)
        assertThat(accountB.scope).isEqualTo(scopeB)
        assertThat(accountA.ownerAccountId).isEqualTo("account-a")
        BackgroundUploadDraftScopeRegistry.replace(null)
        assertThrows(IllegalArgumentException::class.java) { draft() }
    }

    @Test
    fun `worker policy rejects account switch and warehouse grant downgrade`() {
        val operation = operation()

        assertThat(
            BackgroundUploadAuthorizationPolicy.canExecute(
                user(accountId = "account-a", warehouseIds = listOf("warehouse-1")),
                operation,
            ),
        ).isTrue()
        assertThat(
            BackgroundUploadAuthorizationPolicy.canExecute(
                user(accountId = "account-b", warehouseIds = listOf("warehouse-1")),
                operation,
            ),
        ).isFalse()
        assertThat(
            BackgroundUploadAuthorizationPolicy.canExecute(
                user(accountId = "account-a", warehouseIds = emptyList()),
                operation,
            ),
        ).isFalse()
    }

    private fun operation(
        photo: BackgroundUploadPhoto? = null,
        id: String = "operation-1",
        scope: BackgroundUploadScope = scopeA,
    ) = BackgroundUploadOperation(
        id = id,
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
        ownerAccountId = scope.ownerAccountId,
        warehouseId = scope.warehouseId,
    )

    private fun owner() = MediaOwner(
        ownerType = "MAINTENANCE_ACCEPTANCE",
        ownerId = "repair-1",
        warehouseId = "warehouse-1",
        context = "ACCEPTANCE",
    )

    private fun user(
        accountId: String,
        warehouseIds: List<String>,
    ) = CurrentUserDto(
        id = accountId,
        username = accountId,
        displayName = accountId,
        principalType = "USER",
        globalRole = "WAREHOUSE_MANAGER",
        rentalAccess = false,
        warehouseAccessAll = false,
        warehouseAccesses = warehouseIds.map { WarehouseAccessDto(it, "MANAGE") },
    )

    private fun draft() = BackgroundUploadDraft(
        area = BackgroundUploadArea.ACCEPTANCE,
        title = "Приёмка",
        acceptance = AcceptanceUploadCommand(
            repairId = "repair-1",
            warehouseId = "warehouse-1",
            expectedVersion = 3,
            idempotencyKey = "command-1",
        ),
    )

    /** Stable account-and-warehouse scopes shared by the focused outbox tests. */
    private companion object {
        val scopeA = BackgroundUploadScope("account-a", "warehouse-1")
        val scopeB = BackgroundUploadScope("account-b", "warehouse-1")
        val scopeAOtherWarehouse = BackgroundUploadScope("account-a", "warehouse-2")
    }
}
