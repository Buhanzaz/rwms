package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairPlanDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Verifies ownership across every state mutation and simultaneously retained editor snapshot. */
class ManagerMediaStateFlowTest {
    @Test
    fun `shared editor URI stays pinned until its last state reference is removed`() {
        val claims = mutableMapOf<String, Int>()
        val state = ManagerMediaStateFlow(
            retain = { claims[it] = claims.getOrDefault(it, 0) + 1; true },
            release = { claims[it] = claims.getValue(it) - 1 },
        )
        val uri = "file:///remote/first.jpg"
        val inventory = InventoryEditorState("finding", "CAB-1", "MATCHED",
            persistedPhotoMedia = mapOf(uri to MediaReferenceDto("media", 1)))
        state.value = ManagerUiState(inventoryEditor = inventory)
        val maintenance = MaintenanceEditorState(
            mode = MaintenanceEditorMode.ESTIMATE, entityId = null, expectedVersion = null, readOnly = false,
            selectedAsset = null, dispatchDate = "2026-09-06", sourceParty = "", lines = emptyList(),
            photoUris = emptyList(), readyMedia = emptyList(), priority = 1, step = 1,
            readyPhotoUris = mapOf("one" to uri, "alias" to uri),
        )
        state.update { it.copy(maintenanceEditor = maintenance, acceptanceEditor = acceptance(uri)) }
        assertThat(claims[uri]).isEqualTo(1)

        state.update { it.copy(inventoryEditor = null, maintenanceEditor = null) }
        assertThat(claims[uri]).isEqualTo(1)
        state.update { it.copy(acceptanceEditor = null) }
        assertThat(claims[uri]).isEqualTo(0)
    }

    @Test
    fun `CAS failure has no ownership effects and successful publication pins before releasing`() = runTest {
        val events = mutableListOf<String>()
        lateinit var state: ManagerMediaStateFlow
        state = ManagerMediaStateFlow(
            retain = { events += "retain:$it:${state.value.inventoryEditor?.number}"; true },
            release = { events += "release:$it:${state.value.inventoryEditor?.number}" },
        )
        val first = inventory("one", "first")
        val second = inventory("two", "second")
        state.tryEmit(first)
        assertThat(state.compareAndSet(ManagerUiState(), second)).isFalse()
        assertThat(events).containsExactly("retain:first:null")

        state.emit(second)
        assertThat(events).containsExactly("retain:first:null", "retain:second:one", "release:first:two")
            .inOrder()
        state.value = second.copy(busy = true)
        assertThat(events).hasSize(3)
        state.close()
        state.close()
        assertThat(events.last()).isEqualTo("release:second:two")
        assertThat(events).hasSize(4)
        state.value = first
        assertThat(events).hasSize(4)
    }

    @Test
    fun `durable draft remapping releases remote ownership without claiming draft deletion`() {
        val released = mutableListOf<String>()
        val retained = mutableListOf<String>()
        val state = ManagerMediaStateFlow(
            retain = { retained += it; it == "remote" },
            release = released::add,
        )
        state.value = inventory("one", "remote")
        state.value = inventory("one", "draft")
        assertThat(released).containsExactly("remote")
        assertThat(retained).containsExactly("remote", "draft")
    }

    private fun inventory(number: String, uri: String) = ManagerUiState(
        inventoryEditor = InventoryEditorState("finding", number, "MATCHED",
            persistedPhotoMedia = mapOf(uri to MediaReferenceDto("media", 1))),
    )

    private fun acceptance(uri: String): MaintenanceAcceptanceEditorState {
        val media = AcceptanceInlineMediaState("Photos", listOf(uri))
        val repair = RepairDto(
            id = "repair", rootRepairId = "repair", warehouseId = "warehouse", rentalItemId = "asset",
            origin = "DIRECT_REPAIR", kind = "PRIMARY", executionState = "COMPLETED", acceptanceState = "PENDING",
            version = 1, dispatchDate = "2026-09-06", plan = RepairPlanDto("repair", 1, emptyList()),
            createdAt = "2026-09-06T00:00:00Z", updatedAt = "2026-09-06T00:00:00Z",
        )
        return MaintenanceAcceptanceEditorState(
            repair, RentalItemDto("asset", 1, "warehouse", "CAB-1", "AVAILABLE"),
            AcceptanceReviewMediaState(media, mapOf("stage" to mapOf("work" to media)), mapOf("stage" to media)),
        )
    }
}
