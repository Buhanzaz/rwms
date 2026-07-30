package dev.buhanzaz.rwms.manager.ui.screens

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.FurnitureEquipmentReferenceDto
import org.junit.Test

class MaintenanceCatalogUiPolicyTest {
    @Test
    fun `catalog is paged as a fixed three by three grid`() {
        val items = (1..20).toList()

        assertThat(MAINTENANCE_CATALOG_PAGE_SIZE).isEqualTo(9)
        assertThat(maintenanceCatalogPageCount(items.size)).isEqualTo(3)
        assertThat(maintenanceCatalogPage(items, 0)).containsExactlyElementsIn(1..9).inOrder()
        assertThat(maintenanceCatalogPage(items, 1)).containsExactlyElementsIn(10..18).inOrder()
        assertThat(maintenanceCatalogPage(items, 2)).containsExactly(19, 20).inOrder()
        assertThat(maintenanceCatalogPage(items, 99)).containsExactly(19, 20).inOrder()
    }

    @Test
    fun `full catalog label requires a two second hold`() {
        assertThat(MAINTENANCE_CATALOG_LABEL_HOLD_MILLIS).isEqualTo(2_000L)
    }

    @Test
    fun `search feedback moves above the field only while ime is visible`() {
        assertThat(maintenanceSearchFeedbackPlacement(imeVisible = true))
            .isEqualTo(MaintenanceSearchFeedbackPlacement.AboveField)
        assertThat(maintenanceSearchFeedbackPlacement(imeVisible = false))
            .isEqualTo(MaintenanceSearchFeedbackPlacement.BelowField)
    }

    @Test
    fun `repair hides furniture tree while estimate retains configured furniture`() {
        val regular = node(id = "regular", type = "CATEGORY", mainMenu = true)
        val furniture = node(
            id = "furniture",
            type = "CATEGORY",
            mainMenu = true,
            furnitureCategory = true,
        )
        val unconfiguredFurnitureMaterial = node(
            id = "furniture-material-unconfigured",
            type = "MATERIAL",
            parentId = furniture.id,
        )
        val configuredFurnitureMaterial = node(
            id = "furniture-material-configured",
            type = "MATERIAL",
            parentId = furniture.id,
            furnitureEquipment = FurnitureEquipmentReferenceDto("equipment-1", "Стул"),
        )
        val catalog = maintenanceCatalogIndex(
            listOf(regular, furniture, unconfiguredFurnitureMaterial, configuredFurnitureMaterial),
            emptyList(),
        )

        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
                excludeFurniture = true,
            ).map(CatalogNodeDto::id),
        ).containsExactly(regular.id)
        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
                path = listOf(furniture.id),
                excludeFurniture = false,
            ).map(CatalogNodeDto::id),
        ).containsExactly(configuredFurnitureMaterial.id)
    }

    @Test
    fun `catalog modes retain parent hierarchy and dependency related choices`() {
        val root = node(id = "root", type = "CATEGORY", mainMenu = true)
        val work = node(id = "work", type = "WORK", parentId = root.id)
        val material = node(id = "material", type = "MATERIAL", parentId = root.id)
        val looseMaterial = node(id = "loose-material", type = "MATERIAL", parentId = root.id)
        val catalog = maintenanceCatalogIndex(
            listOf(root, work, material, looseMaterial),
            listOf(link("work-material", work.id, material.id, "DEPENDENCY")),
        )

        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.WORKS_ONLY,
                path = listOf(root.id),
            ).map(CatalogNodeDto::id),
        ).containsExactly(work.id)
        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.MATERIALS_ONLY,
                path = listOf(root.id),
            ).map(CatalogNodeDto::id),
        ).containsExactly(looseMaterial.id, material.id, work.id)
            .inOrder()
        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
                pendingWorkId = work.id,
            ).map(CatalogNodeDto::id),
        ).containsExactly(material.id)
    }

    @Test
    fun `active root category remains visible when only its child positions enter estimates`() {
        val category = node(
            id = "standard-options",
            type = "CATEGORY",
            mainMenu = false,
        )
        val material = node(
            id = "hanger",
            type = "MATERIAL",
            parentId = category.id,
        )
        val catalog = maintenanceCatalogIndex(listOf(category, material), emptyList())

        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
            ).map(CatalogNodeDto::id),
        ).containsExactly(category.id)
        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
                path = listOf(category.id),
            ).map(CatalogNodeDto::id),
        ).containsExactly(material.id)
    }

    private fun node(
        id: String,
        type: String,
        parentId: String? = null,
        mainMenu: Boolean = false,
        furnitureCategory: Boolean = false,
        furnitureEquipment: FurnitureEquipmentReferenceDto? = null,
    ) = CatalogNodeDto(
        id = id,
        catalogVersionId = "catalog-1",
        nodeType = type,
        name = id,
        active = true,
        parentNodeId = parentId,
        furnitureCategory = furnitureCategory,
        furnitureEquipment = furnitureEquipment,
        unit = "шт.",
        unitPrice = "10.00",
        durationMinutes = if (type == "WORK") 30 else 0,
        includeInEstimate = type in setOf("WORK", "MATERIAL", "OPTION"),
        commonItem = false,
        showInMainMenu = mainMenu,
    )

    private fun link(
        id: String,
        fromNodeId: String,
        toNodeId: String,
        type: String,
    ) = CatalogLinkDto(
        id = id,
        catalogVersionId = "catalog-1",
        fromNodeId = fromNodeId,
        toNodeId = toNodeId,
        linkType = type,
        sortOrder = 0,
    )
}
