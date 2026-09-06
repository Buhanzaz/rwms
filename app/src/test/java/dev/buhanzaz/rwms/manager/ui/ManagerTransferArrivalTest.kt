package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.LogisticsLineDto
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaPageDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import dev.buhanzaz.rwms.manager.network.TransferArrivalPreflightDto
import dev.buhanzaz.rwms.manager.network.WarehouseAccessDto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraft
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraftScopeRegistry
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadScope
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** Exercises preflight, editing and durable command admission through the actual coordinator. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagerTransferArrivalTest {
    @After
    fun clearScope() {
        BackgroundUploadDraftScopeRegistry.replace(null)
    }

    @Test
    fun `transport stays lazy until the first command`() = runTest {
        val harness = Harness(this)
        assertThat(harness.apiReads).isEqualTo(0)
        assertThat(harness.calls).isEmpty()
    }

    @Test
    fun `arrival without a repair enqueues explicit null with original versions and owner`() = runTest {
        val harness = Harness(this)
        harness.coordinator.startTransferArrival("line-1") { harness.opened++ }
        runCurrent()

        assertThat(harness.calls.map { it.first })
            .containsExactly("transferArrivalPreflight", "ownerMedia").inOrder()
        assertThat(harness.calls.first().second).containsExactly("transfer-1", "line-1", 7L, 4L)
            .inOrder()
        assertThat(harness.opened).isEqualTo(1)
        assertThat(harness.state.value.transferArrival?.priorityRequired).isFalse()
        harness.coordinator.addTransferPhoto("content://arrival-photo")
        harness.coordinator.selectTransferArrivalPriority(3)
        harness.coordinator.arriveTransferLine { harness.saved++ }
        runCurrent()

        val draft = harness.uploads.single()
        val command = checkNotNull(draft.transferArrival)
        assertThat(command.priority).isNull()
        assertThat(command.documentId).isEqualTo("transfer-1")
        assertThat(command.lineId).isEqualTo("line-1")
        assertThat(command.expectedDocumentVersion).isEqualTo(7)
        assertThat(command.expectedLineVersion).isEqualTo(4)
        assertThat(command.idempotencyKey).isNotEmpty()
        assertThat(draft.photos.single().owner.warehouseId).isEqualTo("destination")
        assertThat(draft.photos.single().owner.lineId).isEqualTo("line-1")
        assertThat(harness.state.value.transferArrival).isNull()
        assertThat(harness.saved).isEqualTo(1)
    }

    @Test
    fun `repair arrival requires an explicit valid priority before enqueue`() = runTest {
        val harness = Harness(this, priorityRequired = true)
        harness.coordinator.startTransferArrival("line-1") {}
        runCurrent()
        harness.coordinator.addTransferPhoto("content://arrival-photo")
        harness.coordinator.arriveTransferLine {}
        runCurrent()
        assertThat(harness.uploads).isEmpty()
        assertThat(harness.state.value.message).contains("Выберите приоритет")
        listOf(0, 6).forEach(harness.coordinator::selectTransferArrivalPriority)
        assertThat(harness.state.value.transferArrival?.priority).isNull()

        harness.coordinator.selectTransferArrivalPriority(3)
        harness.coordinator.arriveTransferLine {}
        runCurrent()
        assertThat(harness.uploads.single().transferArrival?.priority).isEqualTo(3)
    }

    @Test
    fun `ready owner media can confirm arrival without adding local photos`() = runTest {
        val harness = Harness(this)
        val ready = MediaAssetDto(
            id = "photo-ready",
            folderId = "folder-1",
            clientReferenceId = null,
            fileName = "photo.jpg",
            contentType = "image/jpeg",
            kind = "PHOTO",
            status = "READY",
            version = 3,
            generation = 2,
            rotationDegrees = 0,
            sortOrder = 0,
            sizeBytes = 100,
            createdAt = "2026-09-06T10:00:00Z",
        )
        harness.media = MediaPageDto(
            listOf(ready.copy(id = "photo-pending", status = "PROCESSING"), ready),
        )
        harness.coordinator.startTransferArrival("line-1") {}
        runCurrent()
        harness.coordinator.arriveTransferLine {}
        runCurrent()

        val draft = harness.uploads.single()
        assertThat(draft.photos).isEmpty()
        assertThat(draft.transferArrival?.existingMedia)
            .containsExactly(MediaReferenceDto("photo-ready", 2))
    }

    @Test
    fun `missing destination queues block media and command admission`() = runTest {
        val harness = Harness(this, priorityRequired = true)
        harness.preflight = harness.preflight.copy(missingQueueDefinitionIds = listOf("queue-1"))
        harness.coordinator.startTransferArrival("line-1") { harness.opened++ }
        runCurrent()

        assertThat(harness.calls.map { it.first }).containsExactly("transferArrivalPreflight")
        assertThat(harness.state.value.transferArrival).isNull()
        assertThat(harness.state.value.message).contains("не настроены очереди ремонта")
        assertThat(harness.opened).isEqualTo(0)
        assertThat(harness.uploads).isEmpty()
    }

    @Test
    fun `preflight and media failures never open arrival`() = runTest {
        listOf("transferArrivalPreflight", "ownerMedia").forEach { failingMethod ->
            val harness = Harness(this)
            harness.intercept = { name, _ ->
                if (name == failingMethod) throw IllegalStateException("Сервис недоступен")
                null
            }
            harness.coordinator.startTransferArrival("line-1") { harness.opened++ }
            runCurrent()
            assertThat(harness.state.value.message).isEqualTo("Сервис недоступен")
            assertThat(harness.state.value.transferArrival).isNull()
            assertThat(harness.opened).isEqualTo(0)
        }
    }

    @Test
    fun `preflight for another line is rejected before reading media`() = runTest {
        val harness = Harness(this)
        harness.preflight = harness.preflight.copy(lineId = "another-line")
        harness.coordinator.startTransferArrival("line-1") { harness.opened++ }
        runCurrent()
        assertThat(harness.calls).hasSize(1)
        assertThat(harness.state.value.message).contains("другой бытовки")
        assertThat(harness.opened).isEqualTo(0)
    }

    @Test
    fun `closing while preflight or media is pending discards its late response`() = runTest {
        listOf("transferArrivalPreflight", "ownerMedia").forEach { delayedMethod ->
            val harness = Harness(this)
            var pending: Continuation<Any?>? = null
            harness.intercept = { name, args ->
                if (name == delayedMethod) {
                    @Suppress("UNCHECKED_CAST")
                    pending = args.last() as Continuation<Any?>
                    COROUTINE_SUSPENDED
                } else null
            }
            harness.coordinator.startTransferArrival("line-1") { harness.opened++ }
            runCurrent()
            harness.coordinator.closeTransferArrival()
            checkNotNull(pending).resume(
                if (delayedMethod == "ownerMedia") MediaPageDto(emptyList()) else harness.preflight,
            )
            runCurrent()
            assertThat(harness.state.value.transferArrival).isNull()
            assertThat(harness.opened).isEqualTo(0)
        }
    }

    @Test
    fun `changed document line account or warehouse invalidates pending preflight`() = runTest {
        val changes: List<(ManagerUiState) -> ManagerUiState> = listOf(
            { state -> state.copy(selectedTransfer = state.selectedTransfer?.copy(version = 8)) },
            { state -> state.copy(selectedTransfer = state.selectedTransfer?.let { document ->
                document.copy(lines = document.lines.map { it.copy(version = 5) })
            }) },
            { state -> state.copy(currentUser = state.currentUser?.copy(id = "other-account")) },
            { state -> state.copy(selectedWarehouseId = "other-warehouse") },
        )
        changes.forEach { change ->
            val harness = Harness(this)
            var pending: Continuation<Any?>? = null
            harness.intercept = { name, args ->
                check(name == "transferArrivalPreflight")
                @Suppress("UNCHECKED_CAST")
                pending = args.last() as Continuation<Any?>
                COROUTINE_SUSPENDED
            }
            harness.coordinator.startTransferArrival("line-1") { harness.opened++ }
            runCurrent()
            harness.state.value = change(harness.state.value)
            checkNotNull(pending).resume(harness.preflight)
            runCurrent()
            assertThat(harness.state.value.transferArrival).isNull()
            assertThat(harness.opened).isEqualTo(0)
            assertThat(harness.calls).hasSize(1)
        }
    }

    @Test
    fun `changed arrival version or missing photos blocks enqueue`() = runTest {
        val harness = Harness(this)
        harness.coordinator.startTransferArrival("line-1") {}
        runCurrent()
        harness.coordinator.arriveTransferLine {}
        runCurrent()
        assertThat(harness.state.value.message).contains("хотя бы одну фотографию")
        assertThat(harness.uploads).isEmpty()

        harness.coordinator.addTransferPhoto("content://arrival-photo")
        harness.state.value = harness.state.value.copy(
            selectedTransfer = harness.state.value.selectedTransfer?.copy(version = 8),
        )
        harness.coordinator.arriveTransferLine {}
        runCurrent()
        assertThat(harness.state.value.message).contains("Перемещение изменилось")
        assertThat(harness.uploads).isEmpty()
    }

    @Test
    fun `destination manage access is required before preflight`() = runTest {
        val harness = Harness(this)
        harness.state.value = harness.state.value.copy(
            currentUser = harness.state.value.currentUser?.copy(
                warehouseAccesses = listOf(WarehouseAccessDto("source", "MANAGE")),
            ),
        )
        harness.coordinator.startTransferArrival("line-1") { harness.opened++ }
        runCurrent()
        assertThat(harness.calls).isEmpty()
        assertThat(harness.opened).isEqualTo(0)
        assertThat(harness.state.value.message).contains("Недостаточно прав")
    }

    private class Harness(scope: CoroutineScope, priorityRequired: Boolean = false) {
        val state = MutableStateFlow(transferArrivalTestState())
        val uploads = mutableListOf<BackgroundUploadDraft>()
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        var apiReads = 0
        var opened = 0
        var saved = 0
        var media = MediaPageDto(emptyList())
        var preflight = TransferArrivalPreflightDto(
            "transfer-1", "line-1", if (priorityRequired) "repair-1" else null,
            priorityRequired, emptyList(),
        )
        var intercept: ((String, Array<out Any?>) -> Any?)? = null
        private val api = Proxy.newProxyInstance(
            RwmsApi::class.java.classLoader,
            arrayOf(RwmsApi::class.java),
        ) { _, method, arguments ->
            val args = checkNotNull(arguments)
            calls += method.name to args.dropLast(1)
            intercept?.invoke(method.name, args) ?: when (method.name) {
                "transferArrivalPreflight" -> preflight
                "ownerMedia" -> media
                else -> error("Unexpected transport call: ${method.name}")
            }
        } as RwmsApi
        val coordinator = ManagerTransferCoordinator(
            runtime = ManagerCommandRuntime(state, scope, { "HTTP ${it.code()}" }, {}),
            apiProvider = { apiReads++; api },
            resolveAssetLabels = { emptyMap() },
            enqueueUpload = { uploads += it },
            commandKeys = StableCommandKeys(),
        )

        init {
            BackgroundUploadDraftScopeRegistry.replace(BackgroundUploadScope("manager", "source"))
        }
    }
}

internal fun transferArrivalTestState(): ManagerUiState = ManagerUiState(
    currentUser = CurrentUserDto(
        id = "manager",
        username = "manager",
        displayName = "Manager",
        principalType = "USER",
        globalRole = "WAREHOUSE_MANAGER",
        rentalAccess = true,
        warehouseAccessAll = false,
        warehouseAccesses = listOf(
            WarehouseAccessDto("source", "MANAGE"),
            WarehouseAccessDto("destination", "MANAGE"),
        ),
    ),
    selectedWarehouseId = "source",
    selectedTransfer = LogisticsDocumentDto(
        id = "transfer-1",
        version = 7,
        documentType = "TRANSFER",
        state = "IN_TRANSIT",
        warehouseId = "source",
        destinationWarehouseId = "destination",
        lines = listOf(LogisticsLineDto("line-1", 4, 1, "asset-1", 6, "DEPARTED")),
        createdAt = "2026-09-06T10:00:00Z",
        updatedAt = "2026-09-06T10:00:00Z",
    ),
)
