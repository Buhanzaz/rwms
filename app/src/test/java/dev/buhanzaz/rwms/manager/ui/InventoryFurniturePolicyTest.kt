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
    fun `supplement keeps the previous furniture observation instead of registry contents`() {
        val chair = equipment("chair", "Стул")
        val table = equipment("table", "Стол")
        val seed = finding(
            contents = listOf(mapOf("equipmentId" to chair.id, "quantity" to 1)),
            equipmentObservation = ObservationDto(
                "PRESENT",
                listOf(mapOf("equipmentId" to chair.id, "quantity" to 4L)),
            ),
        ).inventoryFurnitureReinspectionSeed(
            furnitureCatalog = listOf(chair, table),
            mode = InventoryReinspectionMode.SUPPLEMENT,
        )

        assertThat(seed.observationRequested).isTrue()
        assertThat(seed.quantities).containsExactly("chair", "4", "table", "0")
    }

    @Test
    fun `replace resets furniture confirmation and starts from registry contents`() {
        val chair = equipment("chair", "Стул")
        val seed = finding(
            contents = listOf(mapOf("equipmentId" to chair.id, "quantity" to 1)),
            equipmentObservation = ObservationDto(
                "PRESENT",
                listOf(mapOf("equipmentId" to chair.id, "quantity" to 4L)),
            ),
        ).inventoryFurnitureReinspectionSeed(
            furnitureCatalog = listOf(chair),
            mode = InventoryReinspectionMode.REPLACE,
        )

        assertThat(seed.observationRequested).isNull()
        assertThat(seed.quantities).containsExactly("chair", "1")
    }

    @Test
    fun `supplement preserves the explicit no furniture answer`() {
        val chair = equipment("chair", "Стул")
        val seed = finding(
            contents = listOf(mapOf("equipmentId" to chair.id, "quantity" to 3)),
            equipmentObservation = ObservationDto("EXPLICIT_EMPTY", emptyList<Any?>()),
        ).inventoryFurnitureReinspectionSeed(
            furnitureCatalog = listOf(chair),
            mode = InventoryReinspectionMode.SUPPLEMENT,
        )

        assertThat(seed.observationRequested).isFalse()
        assertThat(seed.quantities).containsExactly("chair", "0")
    }

    @Test
    fun `observed furniture is saved as inspection evidence`() {
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

        val observation = editor.inventoryEquipmentObservation()

        assertThat(observation.presence).isEqualTo("PRESENT")
        @Suppress("UNCHECKED_CAST")
        val observed = observation.value as List<Map<String, Any?>>
        assertThat(observed.single()).containsExactly(
            "equipmentId", "chair",
            "equipmentName", "Стул",
            "equipmentCategory", "FURNITURE",
            "catalogVersion", 1L,
            "quantity", 2L,
        )
    }

    @Test
    fun `zero furniture is an explicit empty inspection observation`() {
        val chair = equipment("chair", "Стул")
        val editor = InventoryEditorState(
            findingId = "finding-1",
            number = "БЫТ-001",
            outcome = "MATCHED",
            equipmentCatalog = listOf(chair),
            equipmentObservationRequested = true,
            equipmentQuantities = mapOf(chair.id to "0"),
        )

        val observation = editor.inventoryEquipmentObservation()

        assertThat(observation.presence).isEqualTo("EXPLICIT_EMPTY")
        assertThat(observation.value).isEqualTo(emptyList<Map<String, Any?>>())
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

    private fun finding(
        contents: List<Any?>,
        equipmentObservation: ObservationDto = ObservationDto("ABSENT"),
    ) = InventoryFindingDto(
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
        equipmentObservation = equipmentObservation,
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
