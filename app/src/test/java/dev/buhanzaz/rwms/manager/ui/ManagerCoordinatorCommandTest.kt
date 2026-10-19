package dev.buhanzaz.rwms.manager.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CreateEstimateRequest
import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.NumberResolutionDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Covers command admission through real manager coordinators with a gateway-boundary fake. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManagerCoordinatorCommandTest {
    @Test
    fun `maintenance editor initializes only after catalog and repair board refresh`() = runTest {
        val state = state()
        val catalog = FakeCatalog()
        val refresh = FakeRefresh()
        var opened = 0
        val coordinator = ManagerMaintenanceEditorCoordinator(
            runtime = runtime(state, this),
            api = unusedApi(),
            commandKeys = StableCommandKeys(),
            catalogAccess = catalog,
            maintenanceAssetRead = FakeAssetRead(),
            maintenanceRefresh = refresh,
            media = FakeMedia(),
        )

        coordinator.startMaintenanceEditor(MaintenanceEditorMode.ESTIMATE) { opened += 1 }
        runCurrent()

        assertThat(catalog.ensureCalls).containsExactly(WAREHOUSE)
        assertThat(refresh.repairBoardCalls).isEqualTo(1)
        assertThat(opened).isEqualTo(1)
        assertThat(state.value.maintenanceEditor?.mode).isEqualTo(MaintenanceEditorMode.ESTIMATE)
        assertThat(state.value.maintenanceEditor?.entityId).isNull()
    }

    @Test
    fun `new estimate draft keeps its idempotency key and applies returned fence`() = runTest {
        val asset = rentalItem()
        val state = state(
            maintenanceEditor = MaintenanceEditorState(
                mode = MaintenanceEditorMode.ESTIMATE,
                entityId = null,
                expectedVersion = null,
                readOnly = false,
                selectedAsset = asset,
                dispatchDate = "2026-09-07",
                sourceParty = "warehouse",
                lines = emptyList(),
                photoUris = emptyList(),
                readyMedia = listOf(dev.buhanzaz.rwms.manager.network.MediaReferenceDto("photo", 1)),
                coverPhotoKey = "media:photo",
                priority = 3,
                step = 1,
                createIdempotencyKey = "estimate-create-key",
            ),
        )
        val calls = mutableListOf<List<Any?>>()
        val api = api { name, args ->
            when (name) {
                "createEstimate" -> {
                    calls += args.dropLast(1)
                    EstimateDto(
                        id = "estimate-1", warehouseId = WAREHOUSE, rentalItemId = asset.id, version = 8,
                        lifecycle = "DRAFT", currentRevision = 1, createdAt = "2026-09-06T10:00:00Z",
                    )
                }
                else -> error("Unexpected gateway call: $name")
            }
        }
        val refresh = FakeRefresh()
        val coordinator = ManagerMaintenancePersistenceCoordinator(
            runtime = runtime(state, this),
            api = api,
            backgroundUploads = CompletableDeferred<BackgroundUploadCoordinator>(),
            maintenanceRefresh = refresh,
            catalogAccess = FakeCatalog(),
            editorClose = FakeEditorClose(),
        )

        coordinator.persistMaintenanceDraft()

        val call = calls.single()
        assertThat(call[0]).isEqualTo("estimate-create-key")
        val request = call[1] as CreateEstimateRequest
        assertThat(request.warehouseId).isEqualTo(WAREHOUSE)
        assertThat(request.rentalItemId).isEqualTo(asset.id)
        assertThat(request.dispatchDate).isEqualTo("2026-09-07")
        assertThat(state.value.maintenanceEditor?.entityId).isEqualTo("estimate-1")
        assertThat(state.value.maintenanceEditor?.expectedVersion).isEqualTo(8)
    }

    @Test
    fun `reinspection response uses the exact session fence and requires explicit choice`() = runTest {
        val session = inventorySession()
        val state = state(inventorySession = session)
        val catalog = FakeCatalog()
        val calls = mutableListOf<List<Any?>>()
        val finding = inventoryFinding(session.id)
        val coordinator = ManagerInventoryCoordinator(
            runtime = runtime(state, this),
            api = api { name, args ->
                check(name == "resolveInventoryNumber")
                calls += args.dropLast(1)
                NumberResolutionDto("CAB-1", "identity", "MATCHED", finding)
            },
            backgroundUploads = CompletableDeferred<BackgroundUploadCoordinator>(),
            commandKeys = StableCommandKeys { "resolve-key" },
            managerReadCache = ManagerReadCache(RuntimeEnvironment.getApplication()),
            catalogAccess = catalog,
            media = FakeMedia(),
            draftStore = InventoryDraftStore(RuntimeEnvironment.getApplication()),
        )
        var chosen: InventoryFindingDto? = null
        var opened = 0

        coordinator.resolveInventoryNumber(" CAB-1 ", { chosen = it }) { opened += 1 }
        runCurrent()

        assertThat(catalog.ensureCalls).containsExactly(WAREHOUSE)
        val call = calls.single()
        assertThat(call[0]).isEqualTo(session.id)
        assertThat(call[1]).isEqualTo("resolve-key")
        val request = call[2] as dev.buhanzaz.rwms.manager.network.ResolveNumberRequest
        assertThat(request.expectedSessionRevision).isEqualTo(session.sessionRevision)
        assertThat(request.submittedNumber).isEqualTo("CAB-1")
        assertThat(chosen).isEqualTo(finding)
        assertThat(opened).isEqualTo(0)
        assertThat(state.value.inventoryEditor).isNull()
    }

    private fun runtime(state: kotlinx.coroutines.flow.MutableStateFlow<ManagerUiState>, scope: CoroutineScope) =
        ManagerCommandRuntime(state, scope, { "HTTP ${it.code()}" }, {})

    private fun state(
        maintenanceEditor: MaintenanceEditorState? = null,
        inventorySession: InventorySessionDto? = null,
    ) = kotlinx.coroutines.flow.MutableStateFlow(
        ManagerUiState(
            selectedWarehouseId = WAREHOUSE,
            maintenanceEditor = maintenanceEditor,
            inventorySession = inventorySession,
        ),
    )

    private fun api(handle: (String, Array<out Any?>) -> Any?): RwmsApi = Proxy.newProxyInstance(
        RwmsApi::class.java.classLoader,
        arrayOf(RwmsApi::class.java),
    ) { _, method, args -> handle(method.name, requireNotNull(args)) } as RwmsApi

    private fun unusedApi(): RwmsApi = api { name, _ -> error("Unexpected gateway call: $name") }

    private fun rentalItem() = RentalItemDto("asset-1", 4, WAREHOUSE, "CAB-1", "AVAILABLE")

    private fun inventorySession() = InventorySessionDto(
        id = "inventory-1", sessionRevision = 6, warehouseId = WAREHOUSE, warehouseVersion = 2,
        warehouseTimeZone = "Europe/Moscow", businessDate = "2026-09-06", lifecycle = "ACTIVE",
        expectedCount = 1, findingCount = 1, inspectedCount = 1, startedAt = "2026-09-06T08:00:00Z",
        publicationState = "DRAFT",
    )

    private fun inventoryFinding(inventoryId: String) = InventoryFindingDto(
        id = "finding-1", inventoryId = inventoryId, findingRevision = 5, origin = "REGISTRY",
        inspection = "MATCHED", reconciliation = "MATCHED", assetId = "asset-1", assetVersion = 4,
        displayCanonicalNumber = "CAB-1", identityMatchKey = "identity",
        passportObservation = ObservationDto("NOT_OBSERVED"), equipmentObservation = ObservationDto("NOT_OBSERVED"),
        mutationState = "NONE", comment = "",
    )

    private class FakeCatalog : ManagerMaintenanceCatalogAccess {
        val ensureCalls = mutableListOf<String>()
        override val nodesById = emptyMap<String, dev.buhanzaz.rwms.manager.network.CatalogNodeDto>()
        override suspend fun ensureMaintenanceCatalog(warehouseId: String) { ensureCalls += warehouseId }
        override suspend fun restoreMaintenanceCatalogFromDisk(warehouseId: String) = false
    }

    private class FakeRefresh : ManagerMaintenanceRefreshPort {
        var maintenanceCalls = 0
        var repairBoardCalls = 0
        override suspend fun refreshMaintenance(force: Boolean) { maintenanceCalls += 1 }
        override suspend fun refreshRepairTaskBoard(force: Boolean) { repairBoardCalls += 1 }
        override suspend fun refreshAcceptance() = Unit
    }

    private class FakeAssetRead : ManagerMaintenanceAssetReadPort {
        override suspend fun maintenanceRentalItem(rentalItemId: String, warehouseId: String) =
            RentalItemDto(rentalItemId, 1, warehouseId, "CAB-1", "AVAILABLE")
    }

    private class FakeEditorClose : ManagerMaintenanceEditorClosePort {
        override fun closeMaintenanceEditor() = Unit
    }

    private class FakeMedia : ManagerMediaPort {
        override fun releasePhotoUris(uris: Iterable<String>) = Unit
        override suspend fun loadInventoryPhotoUris(finding: InventoryFindingDto, warehouseId: String) = emptyList<ScopedMediaResult>()
        override suspend fun loadMaintenancePhotoUris(
            references: List<dev.buhanzaz.rwms.manager.network.MediaReferenceDto>,
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
        const val WAREHOUSE = "warehouse"
    }
}
