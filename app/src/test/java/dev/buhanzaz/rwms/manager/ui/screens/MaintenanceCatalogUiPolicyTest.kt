package dev.buhanzaz.rwms.manager.ui.screens

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.FurnitureEquipmentReferenceDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorMode
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState
import dev.buhanzaz.rwms.manager.ui.MaintenanceLineEditorState
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
    fun `quantity arrows step by one and never create a non-positive quantity`() {
        assertThat(maintenanceQuantityAfterStep("1", step = -1)).isEqualTo("1")
        assertThat(maintenanceQuantityAfterStep("2", step = -1)).isEqualTo("1")
        assertThat(maintenanceQuantityAfterStep("1,5", step = 1)).isEqualTo("2,5")
        assertThat(maintenanceQuantityAfterStep("invalid", step = 1)).isEqualTo("2")
    }

    @Test
    fun `photo drawer opens only after more than half of its height is revealed`() {
        assertThat(
            maintenanceCatalogPhotoDrawerShouldExpand(
                revealedHeightPx = 0f,
                maximumRevealHeightPx = 240f,
            ),
        ).isFalse()
        assertThat(
            maintenanceCatalogPhotoDrawerShouldExpand(
                revealedHeightPx = 120f,
                maximumRevealHeightPx = 240f,
            ),
        ).isFalse()
        assertThat(
            maintenanceCatalogPhotoDrawerShouldExpand(
                revealedHeightPx = 120.01f,
                maximumRevealHeightPx = 240f,
            ),
        ).isTrue()
        assertThat(
            maintenanceCatalogPhotoDrawerShouldExpand(
                revealedHeightPx = 500f,
                maximumRevealHeightPx = 0f,
            ),
        ).isFalse()
    }

    @Test
    fun `photo drawer preserves its last settled endpoint when available height changes`() {
        assertThat(
            maintenanceCatalogPhotoDrawerHeightAfterAvailableHeightChanged(
                revealedHeightPx = 120f,
                maximumRevealHeightPx = 240f,
                lastSnapWasExpanded = true,
                dragInProgress = false,
            ),
        ).isEqualTo(240f)
        assertThat(
            maintenanceCatalogPhotoDrawerHeightAfterAvailableHeightChanged(
                revealedHeightPx = 120f,
                maximumRevealHeightPx = 240f,
                lastSnapWasExpanded = false,
                dragInProgress = false,
            ),
        ).isEqualTo(0f)
        assertThat(
            maintenanceCatalogPhotoDrawerHeightAfterAvailableHeightChanged(
                revealedHeightPx = 300f,
                maximumRevealHeightPx = 240f,
                lastSnapWasExpanded = true,
                dragInProgress = true,
            ),
        ).isEqualTo(240f)
    }

    @Test
    fun `photo drawer keeps top-level and work-assigned condition photos in deterministic order`() {
        val firstReady = MediaReferenceDto("ready-1", 1)
        val duplicateReady = MediaReferenceDto("ready-2", 1)
        val missingReady = MediaReferenceDto("ready-missing", 1)
        val blankReady = MediaReferenceDto("ready-blank", 1)
        val assignedReady = MediaReferenceDto("ready-assigned", 1)
        val editor = editorWithLines(
            line(id = "work", catalogNodeId = "work", type = "WORK").copy(
                mediaReferences = listOf(assignedReady, firstReady),
                photoUris = listOf(
                    "file:///cache/assigned-local.jpg",
                    "file:///cache/ready-first.jpg",
                ),
            ),
        ).copy(
            readyMedia = listOf(firstReady, duplicateReady, missingReady, blankReady),
            readyPhotoUris = mapOf(
                firstReady.mediaId to "file:///cache/ready-first.jpg",
                duplicateReady.mediaId to "file:///cache/ready-first.jpg",
                blankReady.mediaId to "   ",
                assignedReady.mediaId to "file:///cache/ready-assigned.jpg",
            ),
            photoUris = listOf(
                "file:///cache/local-first.jpg",
                "file:///cache/ready-first.jpg",
                "",
                "file:///cache/local-first.jpg",
                "file:///cache/local-second.jpg",
            ),
        )

        assertThat(maintenanceCatalogPhotoUris(editor)).containsExactly(
            "file:///cache/ready-first.jpg",
            "file:///cache/ready-assigned.jpg",
            "file:///cache/local-first.jpg",
            "file:///cache/local-second.jpg",
            "file:///cache/assigned-local.jpg",
        ).inOrder()
    }

    @Test
    fun `catalog add exposes every matching work and keeps material-only adds commentless`() {
        val work = node(id = "work", type = "WORK")
        val material = node(id = "material", type = "MATERIAL")
        val editor = editorWithLines(
            line(id = "work-1", catalogNodeId = work.id, type = "WORK", comment = "Первая"),
            line(id = "work-2", catalogNodeId = work.id, type = "WORK", comment = "Вторая"),
            line(id = "material-1", catalogNodeId = material.id, type = "MATERIAL"),
        )

        assertThat(maintenanceCatalogExistingWorkCandidates(editor, listOf(work)).map { it.id })
            .containsExactly("work-1", "work-2")
            .inOrder()
        assertThat(maintenanceCatalogSelectionHasWork(listOf(work))).isTrue()
        assertThat(maintenanceCatalogSelectionHasWork(listOf(material))).isFalse()
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
    fun `outgoing arrows take priority over flat editor hierarchy`() {
        val exterior = node(id = "exterior", type = "CATEGORY", mainMenu = true)
        val roof = node(id = "roof", type = "CATEGORY", parentId = exterior.id)
        val walls = node(id = "walls", type = "CATEGORY", parentId = exterior.id)
        val frame = node(id = "frame", type = "CATEGORY", parentId = exterior.id)
        val accidentalFlatWork = node(id = "advertising", type = "WORK", parentId = exterior.id)
        val roofPainting = node(id = "roof-painting", type = "WORK", parentId = roof.id)
        val roofWaterproofing = node(id = "roof-waterproofing", type = "WORK", parentId = roof.id)
        val tapeRepair = node(id = "tape-repair", type = "WORK", parentId = roof.id)
        val sheetReplacement = node(id = "sheet-replacement", type = "WORK", parentId = roof.id)
        val tape = node(id = "waterproof-tape", type = "MATERIAL", parentId = roof.id)
        val catalog = maintenanceCatalogIndex(
            listOf(
                exterior,
                roof,
                walls,
                frame,
                accidentalFlatWork,
                roofPainting,
                roofWaterproofing,
                tapeRepair,
                sheetReplacement,
                tape,
            ),
            listOf(
                link("exterior-roof", exterior.id, roof.id, "FOLLOW_UP"),
                link("exterior-walls", exterior.id, walls.id, "FOLLOW_UP"),
                link("exterior-frame", exterior.id, frame.id, "FOLLOW_UP"),
                link("roof-painting", roof.id, roofPainting.id, "FOLLOW_UP"),
                link("roof-waterproofing", roof.id, roofWaterproofing.id, "FOLLOW_UP"),
                link("roof-tape-repair", roof.id, tapeRepair.id, "FOLLOW_UP"),
                link("roof-sheet-replacement", roof.id, sheetReplacement.id, "FOLLOW_UP"),
                link("repair-tape", tapeRepair.id, tape.id, "DEPENDENCY"),
            ),
        )

        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
                path = listOf(exterior.id),
            ).map(CatalogNodeDto::id),
        ).containsExactly(roof.id, walls.id, frame.id)
        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
                path = listOf(exterior.id, roof.id),
            ).map(CatalogNodeDto::id),
        ).containsExactly(roofPainting.id, roofWaterproofing.id, tapeRepair.id, sheetReplacement.id)
        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
                path = listOf(exterior.id, roof.id, tapeRepair.id),
            ).map(CatalogNodeDto::id),
        ).containsExactly(tape.id)
    }

    @Test
    fun `adding a work continues through its active follow-up without losing the branch`() {
        val windows = node(id = "windows", type = "CATEGORY", mainMenu = true)
        val pvcWindows = node(id = "pvc-windows", type = "SUBCATEGORY", parentId = windows.id)
        val dismantling = node(id = "dismantling", type = "WORK", parentId = pvcWindows.id)
        val installation = node(id = "installation", type = "WORK", parentId = pvcWindows.id)
        val sealant = node(id = "sealant", type = "MATERIAL", parentId = pvcWindows.id)
        val archivedInstallation = node(
            id = "archived-installation",
            type = "WORK",
            parentId = pvcWindows.id,
            active = false,
        )
        val catalog = maintenanceCatalogIndex(
            listOf(windows, pvcWindows, dismantling, installation, sealant, archivedInstallation),
            listOf(
                link("dismantling-installation", dismantling.id, installation.id, "FOLLOW_UP"),
                link("dismantling-sealant", dismantling.id, sealant.id, "FOLLOW_UP"),
                link(
                    "dismantling-archived-installation",
                    dismantling.id,
                    archivedInstallation.id,
                    "FOLLOW_UP",
                ),
            ),
        )

        val continuedPath = maintenanceCatalogPathAfterAdd(
            catalog = catalog,
            mode = MaintenanceCatalogMode.LINKED_SET,
            path = listOf(windows.id, pvcWindows.id),
            addedNodeId = dismantling.id,
        )

        assertThat(continuedPath).containsExactly(windows.id, pvcWindows.id, dismantling.id).inOrder()
        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.LINKED_SET,
                path = continuedPath,
            ).map(CatalogNodeDto::id),
        ).containsExactly(installation.id, sealant.id).inOrder()
    }

    @Test
    fun `adding a material follows an active next material but ignores inactive nodes`() {
        val root = node(id = "root", type = "CATEGORY", mainMenu = true)
        val foam = node(id = "foam", type = "MATERIAL", parentId = root.id)
        val sealant = node(id = "sealant", type = "MATERIAL", parentId = root.id)
        val archivedSealant = node(
            id = "archived-sealant",
            type = "MATERIAL",
            parentId = root.id,
            active = false,
        )
        val catalog = maintenanceCatalogIndex(
            listOf(root, foam, sealant, archivedSealant),
            listOf(
                link("foam-sealant", foam.id, sealant.id, "FOLLOW_UP"),
                link("foam-archived", foam.id, archivedSealant.id, "FOLLOW_UP"),
            ),
        )

        val continuedPath = maintenanceCatalogPathAfterAdd(
            catalog = catalog,
            mode = MaintenanceCatalogMode.MATERIALS_ONLY,
            path = listOf(root.id),
            addedNodeId = foam.id,
        )

        assertThat(continuedPath).containsExactly(root.id, foam.id).inOrder()
        assertThat(
            maintenanceCatalogVisibleNodes(
                catalog = catalog,
                mode = MaintenanceCatalogMode.MATERIALS_ONLY,
                path = continuedPath,
            ).map(CatalogNodeDto::id),
        ).containsExactly(sealant.id)
    }

    @Test
    fun `catalog cycle does not add duplicate navigation nodes after an add`() {
        val root = node(id = "root", type = "CATEGORY", mainMenu = true)
        val dismantling = node(id = "dismantling", type = "WORK", parentId = root.id)
        val installation = node(id = "installation", type = "WORK", parentId = root.id)
        val catalog = maintenanceCatalogIndex(
            listOf(root, dismantling, installation),
            listOf(
                link("dismantling-installation", dismantling.id, installation.id, "FOLLOW_UP"),
                link("installation-dismantling", installation.id, dismantling.id, "FOLLOW_UP"),
            ),
        )
        val afterDismantling = maintenanceCatalogPathAfterAdd(
            catalog = catalog,
            mode = MaintenanceCatalogMode.LINKED_SET,
            path = listOf(root.id),
            addedNodeId = dismantling.id,
        )

        val afterInstallation = maintenanceCatalogPathAfterAdd(
            catalog = catalog,
            mode = MaintenanceCatalogMode.LINKED_SET,
            path = afterDismantling,
            addedNodeId = installation.id,
        )

        assertThat(afterDismantling).containsExactly(root.id, dismantling.id).inOrder()
        assertThat(afterInstallation).containsExactly(root.id, dismantling.id).inOrder()
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
        active: Boolean = true,
    ) = CatalogNodeDto(
        id = id,
        catalogVersionId = "catalog-1",
        nodeType = type,
        name = id,
        active = active,
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

    private fun editorWithLines(vararg lines: MaintenanceLineEditorState) = MaintenanceEditorState(
        mode = MaintenanceEditorMode.ESTIMATE,
        entityId = null,
        expectedVersion = null,
        readOnly = false,
        selectedAsset = null,
        dispatchDate = "2026-08-03",
        sourceParty = "",
        lines = lines.toList(),
        photoUris = emptyList(),
        readyMedia = emptyList(),
        priority = 3,
        step = 3,
    )

    private fun line(
        id: String,
        catalogNodeId: String,
        type: String,
        comment: String = "",
    ) = MaintenanceLineEditorState(
        id = id,
        catalogNodeId = catalogNodeId,
        description = id,
        lineType = type,
        unit = "шт.",
        quantity = "1",
        unitPrice = "0.00",
        normativeMinutes = if (type == "WORK") 30 else 0,
        comment = comment,
    )
}
