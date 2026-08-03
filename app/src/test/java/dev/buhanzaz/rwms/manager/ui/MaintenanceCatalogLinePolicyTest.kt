package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.FurnitureEquipmentReferenceDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import org.junit.Test

class MaintenanceCatalogLinePolicyTest {
    @Test
    fun `linked catalog selection applies its comment only to the work`() {
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
            .containsExactly("Осмотр", "")
    }

    @Test
    fun `selected existing work keeps its comment while the material aggregates`() {
        val work = node(id = "work", type = "WORK")
        val material = node(id = "material", type = "MATERIAL")
        val first = applyMaintenanceCatalogNodes(
            editor = editor(),
            nodes = listOf(work, material),
            quantity = "1",
            comment = "Первый",
        )
        val existingWork = first.lines.single { line -> line.lineType == "WORK" }

        val result = applyMaintenanceCatalogNodes(
            editor = first,
            nodes = listOf(work, material),
            quantity = "0.5",
            comment = "Второй",
            existingWorkLineId = existingWork.id,
        )

        assertThat(result.lines).hasSize(2)
        assertThat(result.lines.single { line -> line.lineType == "WORK" }).isEqualTo(
            existingWork.copy(quantity = "1.5", comment = "Первый; Второй"),
        )
        assertThat(result.lines.single { line -> line.lineType == "MATERIAL" })
            .isEqualTo(first.lines.single { line -> line.lineType == "MATERIAL" }.copy(quantity = "1.5"))
    }

    @Test
    fun `new repeated catalog work has its own stage on the same queue`() {
        val work = node(id = "work", type = "WORK")
        val material = node(id = "material", type = "MATERIAL")
        val first = applyMaintenanceCatalogNodes(
            editor = editor(),
            nodes = listOf(work, material),
            quantity = "1",
            comment = "Первая работа",
        )

        val result = applyMaintenanceCatalogNodes(
            editor = first,
            nodes = listOf(work, material),
            quantity = "2",
            comment = "Вторая работа",
        )
        val works = result.lines.filter { line -> line.lineType == "WORK" }
        val materialLine = result.lines.single { line -> line.lineType == "MATERIAL" }
        val route = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR")
        val stages = planMaintenanceStages(result) { route }

        assertThat(works).hasSize(2)
        assertThat(works[0].id).isNotEqualTo(works[1].id)
        assertThat(works.map(MaintenanceLineEditorState::comment))
            .containsExactly("Первая работа", "Вторая работа")
            .inOrder()
        assertThat(materialLine.quantity).isEqualTo("3")
        assertThat(materialLine.comment).isEmpty()
        assertThat(stages.map { stage -> stage.primaryLineId })
            .containsExactly(works[0].id, works[1].id)
            .inOrder()
        assertThat(stages).hasSize(2)
    }

    @Test
    fun `different catalog works have distinct stages on the same queue`() {
        val firstWork = node(id = "first-work", type = "WORK")
        val secondWork = node(id = "second-work", type = "WORK")
        val first = applyMaintenanceCatalogNodes(
            editor = editor(),
            nodes = listOf(firstWork),
            quantity = "1",
            comment = "Первая работа",
        )
        val result = applyMaintenanceCatalogNodes(
            editor = first,
            nodes = listOf(secondWork),
            quantity = "1",
            comment = "Вторая работа",
        )
        val works = result.lines.filter { line -> line.lineType == "WORK" }
        val route = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR")

        val stages = planMaintenanceStages(result) { route }

        assertThat(stages).hasSize(2)
        assertThat(stages.map { stage -> stage.primaryLineId })
            .containsExactly(works[0].id, works[1].id)
            .inOrder()
        assertThat(stages.map { stage -> stage.includedLineIds })
            .containsExactly(listOf(works[0].id), listOf(works[1].id))
            .inOrder()
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
