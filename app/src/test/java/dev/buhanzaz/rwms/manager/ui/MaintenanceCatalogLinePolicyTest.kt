package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.FurnitureEquipmentReferenceDto
import org.junit.Test

class MaintenanceCatalogLinePolicyTest {
    @Test
    fun `linked catalog selection adds work and material with quantity and comment`() {
        val work = node(id = "work", type = "WORK")
        val material = node(id = "material", type = "MATERIAL")

        val result = applyMaintenanceCatalogNodes(
            editor = editor(),
            nodes = listOf(work, material),
            quantity = "2",
            comment = "Осмотр",
        )

        assertThat(result.lines.map(MaintenanceLineEditorState::catalogNodeId))
            .containsExactly("work", "material")
            .inOrder()
        assertThat(result.lines.map(MaintenanceLineEditorState::quantity))
            .containsExactly("2", "2")
        assertThat(result.lines.map(MaintenanceLineEditorState::comment))
            .containsExactly("Осмотр", "Осмотр")
    }

    @Test
    fun `repeated catalog selection increments quantity and merges comments`() {
        val work = node(id = "work", type = "WORK")
        val first = applyMaintenanceCatalogNodes(
            editor = editor(),
            nodes = listOf(work),
            quantity = "1.5",
            comment = "Первый",
        )

        val result = applyMaintenanceCatalogNodes(
            editor = first,
            nodes = listOf(work, work),
            quantity = "0.5",
            comment = "Второй",
        )

        assertThat(result.lines).hasSize(1)
        assertThat(result.lines.single().quantity).isEqualTo("2")
        assertThat(result.lines.single().comment).isEqualTo("Первый; Второй")
    }

    @Test
    fun `direct repair excludes furniture tree while estimate keeps configured furniture`() {
        val root = node(
            id = "furniture",
            type = "CATEGORY",
            includeInEstimate = false,
            furnitureCategory = true,
        )
        val work = node(id = "furniture-work", type = "WORK", parentId = root.id)
        val unconfiguredMaterial = node(
            id = "furniture-material",
            type = "MATERIAL",
            parentId = root.id,
        )
        val configuredMaterial = unconfiguredMaterial.copy(
            id = "configured-material",
            furnitureEquipment = FurnitureEquipmentReferenceDto(
                equipmentId = "equipment-1",
                equipmentName = "Стол",
            ),
        )
        val nodesById = listOf(root, work, unconfiguredMaterial, configuredMaterial)
            .associateBy(CatalogNodeDto::id)

        assertThat(
            work.isAvailableForMaintenanceMode(MaintenanceEditorMode.REPAIR, nodesById),
        ).isFalse()
        assertThat(
            work.isAvailableForMaintenanceMode(MaintenanceEditorMode.ESTIMATE, nodesById),
        ).isTrue()
        assertThat(
            unconfiguredMaterial.isAvailableForMaintenanceMode(
                MaintenanceEditorMode.ESTIMATE,
                nodesById,
            ),
        ).isFalse()
        assertThat(
            configuredMaterial.isAvailableForMaintenanceMode(
                MaintenanceEditorMode.ESTIMATE,
                nodesById,
            ),
        ).isTrue()
    }

    private fun editor() = MaintenanceEditorState(
        mode = MaintenanceEditorMode.ESTIMATE,
        entityId = null,
        expectedVersion = null,
        readOnly = false,
        selectedAsset = null,
        dispatchDate = "2026-07-27",
        sourceParty = "",
        lines = emptyList(),
        photoUris = emptyList(),
        readyMedia = emptyList(),
        priority = 3,
        step = 3,
    )

    private fun node(
        id: String,
        type: String,
        parentId: String? = null,
        includeInEstimate: Boolean = true,
        furnitureCategory: Boolean = false,
    ) = CatalogNodeDto(
        id = id,
        catalogVersionId = "catalog-1",
        nodeType = type,
        name = id,
        active = true,
        parentNodeId = parentId,
        furnitureCategory = furnitureCategory,
        unit = "шт.",
        unitPrice = "10.00",
        durationMinutes = if (type == "WORK") 30 else 0,
        includeInEstimate = includeInEstimate,
        commonItem = false,
        showInMainMenu = type == "CATEGORY",
    )
}
