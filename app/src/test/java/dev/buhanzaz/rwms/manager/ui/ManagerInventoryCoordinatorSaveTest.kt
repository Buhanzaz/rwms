package dev.buhanzaz.rwms.manager.ui

import android.app.Application
import android.net.Uri
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CabinCatalogValueDto
import dev.buhanzaz.rwms.manager.network.CabinTypeDimensionDto
import dev.buhanzaz.rwms.manager.network.CreateFindingAssetRequest
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaPageDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import dev.buhanzaz.rwms.manager.network.RentalItemCreationOptionsDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadDraftScopeRegistry
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadStore
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/** Covers inventory save orchestration through the real durable upload boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ManagerInventoryCoordinatorSaveTest {
    private lateinit var context: Application
    private lateinit var workManager: WorkManager
    private lateinit var uploads: BackgroundUploadCoordinator
    private lateinit var commandScope: CoroutineScope

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        BackgroundUploadStore.resetForTests()
        BackgroundUploadDraftScopeRegistry.replace(null)
        context.filesDir.resolve("background-uploads-v2").deleteRecursively()
        clearDraftStorage()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        uploads = BackgroundUploadCoordinator(context)
        uploads.initialize()
        uploads.activateVerifiedScope(ACCOUNT, WAREHOUSE)
        commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = runBlocking {
        commandScope.cancel()
        workManager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        BackgroundUploadStore.resetForTests()
        BackgroundUploadDraftScopeRegistry.replace(null)
        context.filesDir.resolve("background-uploads-v2").deleteRecursively()
        clearDraftStorage()
        Unit
    }

    @Test
    fun `create failure retries with the same key and saves only after durable enqueue`() = runBlocking {
        val session = inventorySession()
        val reference = MediaReferenceDto("media-1", 3)
        val created = inventoryFinding(session.id, findingRevision = 11, media = listOf(reference))
        val refreshed = session.copy(sessionRevision = 7, findingCount = 1)
        val createCalls = mutableListOf<List<Any?>>()
        var createAttempts = 0
        val api = api { name, args ->
            when (name) {
                "createInventoryAsset" -> {
                    createCalls += args.dropLast(1)
                    createAttempts += 1
                    if (createAttempts == 1) throw IOException("offline")
                    created
                }
                "inventory" -> refreshed
                "ownerMedia" -> MediaPageDto(listOf(mediaAsset(reference)))
                else -> error("Unexpected gateway call: $name")
            }
        }
        val generatedKeys = mutableListOf<String>()
        val state = state(
            session,
            creationEditor(reference),
        )
        val coordinator = coordinator(
            state = state,
            api = api,
            commandKeys = StableCommandKeys {
                "create-key-${generatedKeys.size + 1}".also(generatedKeys::add)
            },
        )
        var saved = 0

        coordinator.saveInventoryInspection { saved += 1 }
        awaitMessage(state, "Нет связи с RWMS. Проверьте подключение")

        assertThat(saved).isEqualTo(0)
        assertThat(state.value.inventoryEditor).isNotNull()
        assertThat(uploads.operations.value).isEmpty()

        state.value = state.value.copy(message = null)
        coordinator.saveInventoryInspection { saved += 1 }
        awaitSaved(state)

        assertThat(saved).isEqualTo(1)
        assertThat(generatedKeys).containsExactly("create-key-1")
        assertThat(createCalls.map { it[2] }).containsExactly("create-key-1", "create-key-1")
        createCalls.forEach { call ->
            assertThat(call[0]).isEqualTo(session.id)
            assertThat(call[1]).isEqualTo("new-finding")
            val request = call[3] as CreateFindingAssetRequest
            assertThat(request.expectedSessionRevision).isEqualTo(7)
            assertThat(request.expectedFindingRevision).isEqualTo(0)
            assertThat(request.origin).isEqualTo("ADDED_NEW")
            assertThat(request.displayCanonicalNumber).isEqualTo("CAB-NEW")
        }
        val queued = uploads.operations.value.single().inventory
        assertThat(queued?.inventoryId).isEqualTo(refreshed.id)
        assertThat(queued?.findingId).isEqualTo(created.id)
        assertThat(queued?.expectedFindingRevision).isEqualTo(11)
        assertThat(queued?.existingMedia).containsExactly(reference)
        assertThat(state.value.inventoryEditor).isNull()
    }

    @Test
    fun `creation reads the latest session before posting without replacing the form`() = runBlocking {
        val reference = MediaReferenceDto("media-1", 3)
        val editor = creationEditor(reference).copy(comment = "Complete inspection")
        val state = state(inventorySession(), editor)
        val calls = mutableListOf<String>()
        val coordinator = coordinator(state, api { name, args ->
            calls += name
            when (name) {
                "inventory" -> inventorySession().copy(sessionRevision = 40)
                "createInventoryAsset" -> {
                    assertThat(state.value.inventoryEditor).isEqualTo(editor)
                    assertThat((args[3] as CreateFindingAssetRequest).expectedSessionRevision)
                        .isEqualTo(40)
                    inventoryFinding("inventory-1", 1, listOf(reference))
                }
                "ownerMedia" -> MediaPageDto(listOf(mediaAsset(reference)))
                else -> error("Unexpected gateway call: $name")
            }
        })

        coordinator.saveInventoryInspection {}
        awaitSaved(state)

        assertThat(calls).containsExactly("inventory", "createInventoryAsset", "inventory", "ownerMedia")
            .inOrder()
        assertThat(uploads.operations.value.single().inventory?.comment).isEqualTo(editor.comment)
        assertThat(state.value.inventorySession?.sessionRevision).isEqualTo(40)
    }

    @Test
    fun `one concurrent session advance retries creation using the same source identity`() = runBlocking {
        val reference = MediaReferenceDto("media-1", 3)
        val state = state(inventorySession(), creationEditor(reference))
        val calls = mutableListOf<List<Any?>>()
        var reads = 0
        val coordinator = coordinator(state, api { name, args ->
            when (name) {
                "inventory" -> inventorySession().copy(sessionRevision = if (++reads == 1) 40 else 41)
                "createInventoryAsset" -> {
                    calls += args.dropLast(1)
                    if (calls.size == 1) throw httpFailure(409)
                    inventoryFinding("inventory-1", 1, listOf(reference))
                }
                "ownerMedia" -> MediaPageDto(listOf(mediaAsset(reference)))
                else -> error("Unexpected gateway call: $name")
            }
        }, commandKeys = StableCommandKeys())

        coordinator.saveInventoryInspection {}
        awaitSaved(state)

        assertThat(calls.map { it[1] }).containsExactly("new-finding", "new-finding")
        val requests = calls.map { it[3] as CreateFindingAssetRequest }
        assertThat(requests.map { it.expectedSessionRevision }).containsExactly(40L, 41L).inOrder()
        assertThat(requests[1].copy(expectedSessionRevision = 40)).isEqualTo(requests[0])
        assertThat(calls[0][2]).isNotEqualTo(calls[1][2])
        assertThat(uploads.operations.value).hasSize(1)
    }

    @Test
    fun `unchanged revision conflict does not retry or enqueue`() = exerciseCreationRejection(
        advancingRevision = false, status = 409, expectedAttempts = 1, expectedReads = 2,
    )

    @Test
    fun `a second session race stops without an infinite retry`() = exerciseCreationRejection(
        advancingRevision = true, status = 409, expectedAttempts = 2, expectedReads = 2,
    )

    @Test
    fun `non conflict create failure is not automatically retried`() = exerciseCreationRejection(
        advancingRevision = true, status = 503, expectedAttempts = 1, expectedReads = 1,
    )

    @Test
    fun `source identity conflict is not retried even when session could advance`() = exerciseCreationRejection(
        advancingRevision = true, status = 409, expectedAttempts = 1, expectedReads = 1,
        detail = "Inventory finding is already bound to another source request",
    )

    @Test
    fun `completed session retains the editor without attempting creation`() = runBlocking {
        val editor = creationEditor(MediaReferenceDto("media-1", 3))
        val state = state(inventorySession(), editor)
        var calls = 0
        val coordinator = coordinator(state, api { name, _ ->
            check(name == "inventory")
            calls += 1
            inventorySession().copy(lifecycle = "COMPLETED", sessionRevision = 40)
        })

        coordinator.saveInventoryInspection { error("Unexpected enqueue") }
        awaitMessage(state, "Инвентаризация уже завершена. Черновик осмотра сохранён")

        assertThat(calls).isEqualTo(1)
        assertThat(state.value.inventoryEditor).isEqualTo(editor)
        assertThat(uploads.operations.value).isEmpty()
    }

    @Test
    fun `warehouse switch during preflight never posts the old form`() = runBlocking {
        val editor = creationEditor(MediaReferenceDto("media-1", 3))
        val state = state(inventorySession(), editor)
        val coordinator = coordinator(state, api { name, _ ->
            check(name == "inventory")
            state.value = state.value.copy(selectedWarehouseId = "another-warehouse")
            inventorySession().copy(sessionRevision = 40)
        })

        coordinator.saveInventoryInspection { error("Unexpected enqueue") }
        awaitMessage(state, "Склад или осмотр изменился. Добавление остановлено")

        assertThat(state.value.inventorySession?.sessionRevision).isEqualTo(6)
        assertThat(state.value.inventoryEditor).isEqualTo(editor)
        assertThat(uploads.operations.value).isEmpty()
    }

    @Test
    fun `second confirmation tap cannot launch another create or enqueue`() = runBlocking {
        val reference = MediaReferenceDto("media-1", 3)
        val state = state(inventorySession(), creationEditor(reference))
        var pendingCreate: Continuation<Any?>? = null
        var createCalls = 0
        val coordinator = coordinator(state, api { name, args ->
            when (name) {
                "inventory" -> inventorySession().copy(sessionRevision = 40)
                "createInventoryAsset" -> {
                    createCalls += 1
                    @Suppress("UNCHECKED_CAST")
                    pendingCreate = args.last() as Continuation<Any?>
                    COROUTINE_SUSPENDED
                }
                "ownerMedia" -> MediaPageDto(listOf(mediaAsset(reference)))
                else -> error("Unexpected gateway call: $name")
            }
        })
        var saved = 0

        coordinator.saveInventoryInspection { saved += 1 }
        assertThat(pendingCreate).isNotNull()
        coordinator.saveInventoryInspection { saved += 1 }
        assertThat(state.value.busy).isTrue()
        assertThat(createCalls).isEqualTo(1)
        requireNotNull(pendingCreate).resumeWith(Result.success(
            inventoryFinding("inventory-1", 1, listOf(reference)).copy(id = "new-finding"),
        ))
        awaitSaved(state)

        assertThat(saved).isEqualTo(1)
        assertThat(uploads.operations.value).hasSize(1)
        assertThat(uploads.operations.value.single().inventory?.findingId).isEqualTo("new-finding")
    }

    @Test
    fun `scope change during media read cannot enqueue the original inspection`() = runBlocking {
        val reference = MediaReferenceDto("media-1", 3)
        val editor = creationEditor(reference)
        val state = state(inventorySession(), editor)
        val coordinator = coordinator(state, api { name, _ ->
            when (name) {
                "inventory" -> inventorySession().copy(sessionRevision = 40)
                "createInventoryAsset" -> inventoryFinding("inventory-1", 1, listOf(reference))
                "ownerMedia" -> {
                    state.value = state.value.copy(selectedWarehouseId = "another-warehouse")
                    MediaPageDto(listOf(mediaAsset(reference)))
                }
                else -> error("Unexpected gateway call: $name")
            }
        })

        coordinator.saveInventoryInspection { error("Unexpected enqueue") }
        awaitMessage(state, "Склад или осмотр изменился. Добавление остановлено")

        assertThat(state.value.inventoryEditor).isEqualTo(editor)
        assertThat(uploads.operations.value).isEmpty()
    }

    @Test
    fun `lost create response retries the same finding with a new session fence`() = runBlocking {
        val reference = MediaReferenceDto("media-1", 3)
        val state = state(inventorySession(), creationEditor(reference))
        val calls = mutableListOf<List<Any?>>()
        var serverRevision = 40L
        val coordinator = coordinator(state, api { name, args ->
            when (name) {
                "inventory" -> inventorySession().copy(sessionRevision = serverRevision)
                "createInventoryAsset" -> {
                    calls += args.dropLast(1)
                    if (calls.size == 1) {
                        serverRevision = 41
                        throw IOException("Response lost after source attachment was committed")
                    }
                    inventoryFinding("inventory-1", 1, listOf(reference)).copy(id = "new-finding")
                }
                "ownerMedia" -> MediaPageDto(listOf(mediaAsset(reference)))
                else -> error("Unexpected gateway call: $name")
            }
        }, commandKeys = StableCommandKeys())

        coordinator.saveInventoryInspection { error("Unexpected enqueue") }
        awaitMessage(state, "Нет связи с RWMS. Проверьте подключение")
        assertThat(uploads.operations.value).isEmpty()
        state.value = state.value.copy(message = null)
        coordinator.saveInventoryInspection {}
        awaitSaved(state)

        assertThat(calls.map { it[1] }).containsExactly("new-finding", "new-finding")
        val requests = calls.map { it[3] as CreateFindingAssetRequest }
        assertThat(requests.map { it.expectedSessionRevision }).containsExactly(40L, 41L).inOrder()
        assertThat(requests[1].copy(expectedSessionRevision = 40)).isEqualTo(requests[0])
        assertThat(calls[0][2]).isNotEqualTo(calls[1][2])
        assertThat(uploads.operations.value).hasSize(1)
        assertThat(uploads.operations.value.single().inventory?.findingId).isEqualTo("new-finding")
    }

    @Test
    fun `failed fresh read leaves complete draft and original photos recoverable after restart`() = runBlocking {
        val source = context.cacheDir.resolve("creation-original.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val uri = Uri.fromFile(source).toString()
        val editor = creationEditor(MediaReferenceDto("unused", 1)).copy(
            photoUris = listOf(uri), coverPhotoUri = uri, uploadedPhotoMedia = emptyMap(),
            comment = "Complete draft", equipmentQuantities = mapOf("equipment-1" to "2"),
            planLines = listOf(MaintenanceLineEditorState(
                id = "line-1", catalogNodeId = null, description = "Repair", lineType = "WORK",
                unit = "pcs", quantity = "1", unitPrice = "0", normativeMinutes = 10,
                comment = "Work detail", photoUris = listOf(uri),
            )),
        )
        val draftScope = InventoryDraftScope(ACCOUNT, WAREHOUSE)
        val cipher = object : InventoryDraftCipher {
            override fun encrypt(plainText: String, associatedData: String) = plainText
            override fun decrypt(encoded: String, associatedData: String) = encoded
        }
        val store = InventoryDraftStore(context, cipher)
        val state = state(inventorySession(), editor)
        state.value = state.value.copy(currentUser = CurrentUserDto(
            id = ACCOUNT, username = "tester", displayName = "Tester", principalType = "STAFF",
            globalRole = "MANAGER", rentalAccess = false, warehouseAccessAll = true,
        ))
        val coordinator = coordinator(state, api { name, _ ->
            check(name == "inventory")
            throw IOException("offline")
        }, draftStore = store)
        coordinator.activateDraftScope(draftScope)
        state.value = state.value.copy(inventoryEditor = editor)

        coordinator.saveInventoryInspection { error("Unexpected enqueue") }
        awaitMessage(state, "Нет связи с RWMS. Проверьте подключение")
        check(source.delete())
        val restored = requireNotNull(InventoryDraftStore(context, cipher).read(draftScope))
        val durableUri = restored.editor.photoUris.single()

        assertThat(restored.editor).isEqualTo(editor.copy(
            photoUris = listOf(durableUri), coverPhotoUri = durableUri,
            planLines = editor.planLines.map { it.copy(photoUris = listOf(durableUri)) },
        ))
        assertThat(File(requireNotNull(Uri.parse(durableUri).path)).readBytes())
            .isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(uploads.operations.value).isEmpty()
    }

    private fun exerciseCreationRejection(
        advancingRevision: Boolean,
        status: Int,
        expectedAttempts: Int,
        expectedReads: Int,
        detail: String = "Inventory revision is stale",
    ) = runBlocking {
        val editor = creationEditor(MediaReferenceDto("media-1", 3))
        val state = state(inventorySession(), editor)
        var reads = 0
        var attempts = 0
        val coordinator = coordinator(state, api { name, _ ->
            when (name) {
                "inventory" -> {
                    reads += 1
                    inventorySession().copy(sessionRevision = if (advancingRevision) 40L + reads else 40)
                }
                "createInventoryAsset" -> {
                    attempts += 1
                    throw httpFailure(status, detail)
                }
                else -> error("Unexpected gateway call: $name")
            }
        })

        coordinator.saveInventoryInspection { error("Unexpected enqueue") }
        awaitMessage(state, "HTTP $status")

        assertThat(attempts).isEqualTo(expectedAttempts)
        assertThat(reads).isEqualTo(expectedReads)
        assertThat(state.value.inventoryEditor).isEqualTo(editor)
        assertThat(uploads.operations.value).isEmpty()
    }

    private fun httpFailure(status: Int, detail: String = "Inventory revision is stale") = HttpException(
        Response.error<Any>(status,
            """{"code":"INVENTORY_VERSION_CONFLICT","detail":"$detail"}""".toResponseBody(),
        ),
    )

    private fun clearDraftStorage() {
        context.getSharedPreferences("rwms_manager_inventory_drafts", 0).edit().clear().commit()
        context.filesDir.resolve("manager-inventory-drafts").deleteRecursively()
        context.cacheDir.resolve("creation-original.jpg").delete()
    }

    @Test
    fun `existing finding save keeps its finding fence in the queued update`() = runBlocking {
        val session = inventorySession()
        val reference = MediaReferenceDto("media-existing", 4)
        val finding = inventoryFinding(session.id, findingRevision = 5, media = listOf(reference))
        val calls = mutableListOf<String>()
        val state = state(
            session,
            InventoryEditorState(
                findingId = finding.id,
                number = finding.displayCanonicalNumber,
                outcome = "MATCHED",
                finding = finding,
                comment = "  checked  ",
                equipmentObservationRequested = false,
            ),
        )
        val coordinator = coordinator(
            state = state,
            api = api { name, _ ->
                calls += name
                when (name) {
                    "ownerMedia" -> MediaPageDto(listOf(mediaAsset(reference)))
                    else -> error("Unexpected gateway call: $name")
                }
            },
        )
        var saved = 0

        coordinator.saveInventoryInspection { saved += 1 }
        awaitSaved(state)

        assertThat(calls).containsExactly("ownerMedia")
        val queued = uploads.operations.value.single().inventory
        assertThat(queued?.inventoryId).isEqualTo(session.id)
        assertThat(queued?.findingId).isEqualTo(finding.id)
        assertThat(queued?.expectedFindingRevision).isEqualTo(5)
        assertThat(queued?.comment).isEqualTo("checked")
        assertThat(queued?.existingMedia).containsExactly(reference)
        assertThat(saved).isEqualTo(1)
    }

    @Test
    fun `invalid inspection is rejected before any gateway or upload call`() = runBlocking {
        val session = inventorySession()
        val calls = mutableListOf<String>()
        val state = state(
            session,
            InventoryEditorState(
                findingId = "finding-invalid",
                number = "CAB-INVALID",
                outcome = "MATCHED",
                finding = inventoryFinding(session.id, findingRevision = 2),
                equipmentObservationRequested = false,
            ),
        )
        val coordinator = coordinator(
            state = state,
            api = api { name, _ ->
                calls += name
                error("Unexpected gateway call: $name")
            },
        )
        var saved = 0

        coordinator.saveInventoryInspection { saved += 1 }
        awaitMessage(state, "Добавьте хотя бы одну фотографию")

        assertThat(calls).isEmpty()
        assertThat(uploads.operations.value).isEmpty()
        assertThat(saved).isEqualTo(0)
        assertThat(state.value.inventoryEditor).isNotNull()
    }

    private fun coordinator(
        state: MutableStateFlow<ManagerUiState>,
        api: RwmsApi,
        commandKeys: StableCommandKeys = StableCommandKeys { "unused-key" },
        draftStore: InventoryDraftStore = InventoryDraftStore(context),
    ) = ManagerInventoryCoordinator(
        runtime = ManagerCommandRuntime(state, commandScope, { "HTTP ${it.code()}" }, {}),
        api = api,
        backgroundUploads = CompletableDeferred(uploads),
        commandKeys = commandKeys,
        managerReadCache = ManagerReadCache(context),
        catalogAccess = EmptyCatalog,
        media = EmptyMedia,
        draftStore = draftStore,
    )

    private fun state(
        session: InventorySessionDto,
        editor: InventoryEditorState,
    ) = MutableStateFlow(
        ManagerUiState(
            selectedWarehouseId = WAREHOUSE,
            inventorySession = session,
            inventoryEditor = editor,
        ),
    )

    private fun creationEditor(reference: MediaReferenceDto) = InventoryEditorState(
        findingId = "new-finding",
        number = "CAB-NEW",
        outcome = "NOT_FOUND",
        creationOptions = creationOptions(),
        creationOrigin = "ADDED_NEW",
        rentalType = "Office",
        dimensions = "6x2.4",
        finishing = "Standard",
        category = "New",
        linoleum = true,
        photoUris = listOf("cached://cover"),
        coverPhotoUri = "cached://cover",
        uploadedPhotoMedia = mapOf("cached://cover" to reference),
        equipmentObservationRequested = false,
    )

    private fun creationOptions() = RentalItemCreationOptionsDto(
        newCategory = "New",
        usedCategories = listOf("Used"),
        rentalTypes = listOf(CabinCatalogValueDto("type-1", "Office")),
        dimensions = listOf(CabinCatalogValueDto("dimension-1", "6x2.4")),
        finishings = listOf(CabinCatalogValueDto("finishing-1", "Standard")),
        characteristics = emptyList(),
        typeDimensions = listOf(CabinTypeDimensionDto("type-1", "dimension-1", 0)),
    )

    private fun inventorySession() = InventorySessionDto(
        id = "inventory-1",
        sessionRevision = 6,
        warehouseId = WAREHOUSE,
        warehouseVersion = 2,
        warehouseTimeZone = "Europe/Moscow",
        businessDate = "2026-09-06",
        lifecycle = "ACTIVE",
        expectedCount = 1,
        findingCount = 0,
        inspectedCount = 0,
        startedAt = "2026-09-06T08:00:00Z",
        publicationState = "DRAFT",
    )

    private fun inventoryFinding(
        inventoryId: String,
        findingRevision: Long,
        media: List<MediaReferenceDto> = emptyList(),
    ) = InventoryFindingDto(
        id = if (media.isEmpty()) "finding-invalid" else "finding-1",
        inventoryId = inventoryId,
        findingRevision = findingRevision,
        origin = "REGISTRY",
        inspection = "MATCHED",
        reconciliation = "MATCHED",
        assetId = "asset-1",
        assetVersion = 4,
        displayCanonicalNumber = "CAB-1",
        identityMatchKey = "identity",
        passportObservation = ObservationDto("NOT_OBSERVED"),
        equipmentObservation = ObservationDto("NOT_OBSERVED"),
        mutationState = "NONE",
        comment = "",
        media = media,
    )

    private fun mediaAsset(reference: MediaReferenceDto) = MediaAssetDto(
        id = reference.mediaId,
        folderId = "folder-1",
        clientReferenceId = null,
        fileName = "photo.jpg",
        contentType = "image/jpeg",
        kind = "IMAGE",
        status = "READY",
        version = 1,
        generation = reference.generation,
        rotationDegrees = 0,
        sortOrder = 0,
        sizeBytes = 100,
        createdAt = "2026-09-06T08:00:00Z",
    )

    private suspend fun awaitMessage(
        state: MutableStateFlow<ManagerUiState>,
        expected: String,
    ) {
        val settled = withTimeout(5_000) {
            state.first { it.message != null && !it.busy }
        }
        assertThat(settled.message).isEqualTo(expected)
    }

    private suspend fun awaitSaved(state: MutableStateFlow<ManagerUiState>) {
        withTimeout(5_000) {
            state.first { it.inventoryEditor == null && !it.busy }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun api(handle: (String, Array<out Any?>) -> Any?): RwmsApi = Proxy.newProxyInstance(
        RwmsApi::class.java.classLoader,
        arrayOf(RwmsApi::class.java),
    ) { _, method, args ->
        val arguments = requireNotNull(args)
        try {
            handle(method.name, arguments)
        } catch (failure: IOException) {
            // A suspend failure bypasses Java Proxy's wrapping of undeclared checked exceptions.
            (arguments.last() as Continuation<Any?>).resumeWith(Result.failure(failure))
            COROUTINE_SUSPENDED
        }
    } as RwmsApi

    private object EmptyCatalog : ManagerMaintenanceCatalogAccess {
        override val nodesById = emptyMap<String, dev.buhanzaz.rwms.manager.network.CatalogNodeDto>()
        override suspend fun ensureMaintenanceCatalog(warehouseId: String) = Unit
        override suspend fun restoreMaintenanceCatalogFromDisk(warehouseId: String) = false
    }

    private object EmptyMedia : ManagerMediaPort {
        override fun releasePhotoUris(uris: Iterable<String>) = Unit

        override suspend fun loadInventoryPhotoUris(
            finding: InventoryFindingDto,
            warehouseId: String,
        ) = emptyList<ScopedMediaResult>()

        override suspend fun loadMaintenancePhotoUris(
            references: List<MediaReferenceDto>,
            scopes: List<MaintenanceMediaScope>,
            warehouseId: String,
        ) = emptyMap<String, String>()

        override suspend fun loadScopedPhotoUris(
            requests: List<ScopedMediaDownload>,
            warehouseId: String,
            preferCurrentOwnerReference: Boolean,
        ) = emptyList<ScopedMediaResult>()
    }

    private companion object {
        const val ACCOUNT = "account-1"
        const val WAREHOUSE = "warehouse-1"
    }
}
