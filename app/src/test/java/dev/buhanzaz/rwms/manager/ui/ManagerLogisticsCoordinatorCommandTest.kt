package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CreateTransferRequest
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import dev.buhanzaz.rwms.manager.network.LogisticsLineDto
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaPageDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.ReturnEstimateSourceDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import dev.buhanzaz.rwms.manager.network.StartReturnEstimatesRequest
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Verifies logistics command payloads, fences and success reductions at coordinator boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagerLogisticsCoordinatorCommandTest {
    @Test
    fun `transfer create sends selected asset fences and applies the returned document`() = runTest {
        val candidate = RentalItemDto("asset-1", 14, SOURCE, "CAB-1", "FREE")
        val state = MutableStateFlow(
            ManagerUiState(
                currentUser = managerUser(),
                warehouses = listOf(warehouse(SOURCE), warehouse(DESTINATION)),
                selectedWarehouseId = SOURCE,
                transferEditor = TransferEditorState(
                    destinationWarehouseId = DESTINATION,
                    destinationWarehouseIds = setOf(DESTINATION),
                    driverSnapshot = " Driver ",
                    scheduledDate = "2026-09-07",
                    candidates = listOf(candidate),
                    selectedAssetIds = setOf(candidate.id),
                    idempotencyKey = "transfer-create-key",
                ),
            ),
        )
        val calls = mutableListOf<List<Any?>>()
        val created = logisticsDocument(
            id = "transfer-1",
            version = 1,
            documentType = "TRANSFER",
            state = "DRAFT",
            warehouseId = SOURCE,
            destinationWarehouseId = DESTINATION,
        )
        val coordinator = ManagerTransferCoordinator(
            runtime = runtime(state, this),
            apiProvider = {
                api { name, args ->
                    check(name == "createTransfer")
                    calls += args.dropLast(1)
                    created
                }
            },
            resolveAssetLabels = { emptyMap() },
            enqueueUpload = {},
            commandKeys = StableCommandKeys { "unused-key" },
            clock = Clock.fixed(Instant.parse("2026-09-06T09:00:00Z"), ZoneOffset.UTC),
        )
        var saved = 0

        coordinator.createTransfer { saved += 1 }
        runCurrent()

        val call = calls.single()
        assertThat(call[0]).isEqualTo("transfer-create-key")
        val request = call[1] as CreateTransferRequest
        assertThat(request.warehouseId).isEqualTo(SOURCE)
        assertThat(request.destinationWarehouseId).isEqualTo(DESTINATION)
        assertThat(request.driverSnapshot).isEqualTo("Driver")
        assertThat(request.scheduledDate).isEqualTo("2026-09-07")
        assertThat(request.lines).hasSize(1)
        assertThat(request.lines.single().assetId).isEqualTo(candidate.id)
        assertThat(request.lines.single().assetVersion).isEqualTo(14)
        assertThat(saved).isEqualTo(1)
        assertThat(state.value.transferEditor).isNull()
        assertThat(state.value.transfers).containsExactly(created)
    }

    @Test
    fun `return estimate command uses refreshed document fence and ready media`() = runTest {
        val displayed = returnDocument(version = 8)
        val current = displayed.copy(version = 9, updatedAt = "2026-09-06T09:05:00Z")
        val updated = current.copy(version = 10, state = "ESTIMATE_PENDING")
        val reference = MediaReferenceDto("media-return", 2)
        val source = ReturnEstimateSourceDto(
            returnId = current.id,
            lineId = current.lines.single().id,
            warehouseId = SOURCE,
            rentalItemId = current.lines.single().assetId,
            estimateId = "estimate-1",
        )
        val startCalls = mutableListOf<List<Any?>>()
        val api = api { name, args ->
            when (name) {
                "returnDocument" -> current
                "ownerMedia" -> MediaPageDto(listOf(mediaAsset(reference)))
                "startReturnEstimates" -> {
                    startCalls += args.dropLast(1)
                    updated
                }
                "returnEstimateSources" -> listOf(source)
                else -> error("Unexpected gateway call: $name")
            }
        }
        val state = MutableStateFlow(
            ManagerUiState(
                selectedWarehouseId = SOURCE,
                selectedReturn = displayed,
                returns = listOf(displayed),
                returnPhotoUris = mapOf(displayed.lines.single().id to emptyList()),
                returnReadyMedia = mapOf(displayed.lines.single().id to emptyList()),
            ),
        )
        val refresh = RecordingRefresh()
        val coordinator = ManagerReturnCoordinator(
            runtime = runtime(state, this),
            api = api,
            backgroundUploads = CompletableDeferred<BackgroundUploadCoordinator>(),
            commandKeys = StableCommandKeys { "return-estimate-key" },
            maintenanceRefresh = refresh,
        )
        var sources: List<ReturnEstimateSourceDto>? = null
        var queued = 0

        coordinator.startReturnEstimates(
            onSourcesReady = { sources = it },
            onQueued = { queued += 1 },
        )
        runCurrent()

        val call = startCalls.single()
        assertThat(call[0]).isEqualTo(current.id)
        assertThat(call[1]).isEqualTo(9)
        assertThat(call[2]).isEqualTo("return-estimate-key")
        val request = call[3] as StartReturnEstimatesRequest
        assertThat(request.lines).hasSize(1)
        assertThat(request.lines.single().lineId).isEqualTo(current.lines.single().id)
        assertThat(request.lines.single().references).containsExactly(reference)
        assertThat(sources).containsExactly(source)
        assertThat(queued).isEqualTo(0)
        assertThat(refresh.maintenanceCalls).isEqualTo(1)
        assertThat(state.value.selectedReturn).isNull()
        assertThat(state.value.returns).containsExactly(updated)
    }

    private fun runtime(state: MutableStateFlow<ManagerUiState>, scope: CoroutineScope) =
        ManagerCommandRuntime(state, scope, { "HTTP ${it.code()}" }, {})

    private fun api(handle: (String, Array<out Any?>) -> Any?): RwmsApi = Proxy.newProxyInstance(
        RwmsApi::class.java.classLoader,
        arrayOf(RwmsApi::class.java),
    ) { _, method, args -> handle(method.name, requireNotNull(args)) } as RwmsApi

    private fun managerUser() = CurrentUserDto(
        id = "manager-1",
        username = "manager",
        displayName = "Manager",
        principalType = "HUMAN",
        globalRole = "MANAGER",
        rentalAccess = true,
        warehouseAccessAll = true,
    )

    private fun warehouse(id: String) = WarehouseDto(
        id = id,
        version = 1,
        name = id,
        city = "Moscow",
        timeZone = "Europe/Moscow",
        active = true,
    )

    private fun returnDocument(version: Long) = logisticsDocument(
        id = "return-1",
        version = version,
        documentType = "RETURN",
        state = RETURN_INSPECTION_REQUIRED,
        warehouseId = SOURCE,
        lines = listOf(
            LogisticsLineDto(
                id = "return-line-1",
                version = 4,
                lineNumber = 1,
                assetId = "asset-1",
                assetVersion = 14,
                state = "PENDING",
            ),
        ),
    )

    private fun logisticsDocument(
        id: String,
        version: Long,
        documentType: String,
        state: String,
        warehouseId: String,
        destinationWarehouseId: String? = null,
        lines: List<LogisticsLineDto> = emptyList(),
    ) = LogisticsDocumentDto(
        id = id,
        version = version,
        documentType = documentType,
        state = state,
        warehouseId = warehouseId,
        destinationWarehouseId = destinationWarehouseId,
        lines = lines,
        createdAt = "2026-09-06T08:00:00Z",
        updatedAt = "2026-09-06T09:00:00Z",
    )

    private fun mediaAsset(reference: MediaReferenceDto) = MediaAssetDto(
        id = reference.mediaId,
        folderId = "folder-1",
        clientReferenceId = null,
        fileName = "return.jpg",
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

    private class RecordingRefresh : ManagerMaintenanceRefreshPort {
        var maintenanceCalls = 0
        override suspend fun refreshMaintenance(force: Boolean) {
            maintenanceCalls += 1
        }

        override suspend fun refreshRepairTaskBoard(force: Boolean) = Unit
        override suspend fun refreshAcceptance() = Unit
    }

    private companion object {
        const val SOURCE = "warehouse-source"
        const val DESTINATION = "warehouse-destination"
    }
}
