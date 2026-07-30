package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.InventoryCurrentSnapshotDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import org.junit.Test

class InventoryFurniturePolicyTest {
    @Test
    fun `inventory starts furniture counters from the current asset snapshot`() {
        val chair = equipment("chair", "Стул")
        val table = equipment("table", "Стол")
        val quantities = finding(
            contents = listOf(
                mapOf("equipmentId" to chair.id, "quantity" to 2.0),
                mapOf("equipmentId" to chair.id, "quantity" to "1"),
                mapOf("equipmentId" to "lamp", "quantity" to 7),
            ),
        ).inventoryFurnitureInitialQuantities(listOf(chair, table))

        assertThat(quantities).containsExactly("chair", "3", "table", "0")
    }

    @Test
    fun `observed furniture becomes a desired logistics composition rather than an asset write`() {
        val chair = equipment("chair", "Стул")
        val table = equipment("table", "Стол")
        val editor = InventoryEditorState(
            findingId = "finding-1",
            number = "БЫТ-001",
            outcome = "MATCHED",
            equipmentCatalog = listOf(chair, table),
            equipmentObservationRequested = true,
            equipmentQuantities = mapOf(chair.id to "2", table.id to "0"),
        )

        assertThat(editor.inventoryFurnitureDesiredContents())
            .containsExactly(dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto("chair", 2))
    }

    @Test
    fun `no furniture assertion does not request a logistics task`() {
        val editor = InventoryEditorState(
            findingId = "finding-1",
            number = "БЫТ-001",
            outcome = "MATCHED",
            equipmentObservationRequested = false,
        )

        assertThat(editor.inventoryFurnitureDesiredContents()).isNull()
    }

    @Test
    fun `cannot reconcile a declared furniture inspection without the panel furniture catalog`() {
        val editor = InventoryEditorState(
            findingId = "finding-1",
            number = "БЫТ-001",
            outcome = "MATCHED",
            equipmentObservationRequested = true,
        )

        assertThat(editor.inventoryEquipmentObservationValidationError())
            .contains("нет активных позиций мебели")
    }

    private fun equipment(id: String, name: String) = EquipmentCatalogItemDto(
        id = id,
        version = 1,
        name = name,
        category = "FURNITURE",
        active = true,
    )

    private fun finding(contents: List<Any?>) = InventoryFindingDto(
        id = "finding-1",
        inventoryId = "inventory-1",
        findingRevision = 1,
        origin = "MATCHED",
        inspection = "NOT_INSPECTED",
        reconciliation = "MATCHED",
        assetId = "asset-1",
        assetVersion = 1,
        displayCanonicalNumber = "БЫТ-001",
        identityMatchKey = "БЫТ-001",
        passportObservation = ObservationDto("ABSENT"),
        equipmentObservation = ObservationDto("ABSENT"),
        mutationState = "IDLE",
        comment = "",
        currentSnapshot = InventoryCurrentSnapshotDto(
            assetId = "asset-1",
            assetVersion = 1,
            warehouseId = "warehouse-1",
            status = "WAREHOUSE",
            displayCanonicalNumber = "БЫТ-001",
            contentsSnapshot = contents,
        ),
    )
}
