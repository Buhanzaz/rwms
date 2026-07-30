package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import org.junit.Test

class MaintenanceEditorStatePolicyTest {
    @Test
    fun `read only maintenance editor accepts only wizard step changes`() {
        val original = editor(readOnly = true).copy(
            sourceParty = "Original source",
            lines = listOf(line("line-1")),
            priority = 3,
            step = 0,
        )

        val result = readOnlyMaintenanceEditorStepChange(
            original = original,
            candidate = original.copy(
                sourceParty = "Must not change",
                lines = listOf(line("line-2")),
                priority = 1,
                step = 2,
            ),
        )

        assertThat(result).isEqualTo(original.copy(step = 2))
    }

    @Test
    fun `starting and closing an editor clear stale asset search state`() {
        val item = RentalItemDto(
            id = "asset-1",
            version = 1,
            warehouseId = "warehouse-1",
            number = "CAB-001",
            status = "FREE",
        )
        val initial = ManagerUiState(
            assetSearch = "CAB",
            assetSearchResults = listOf(item),
            assetSearchCompletedQuery = "CAB",
            assetSearchFailedQuery = "OLDER",
            maintenanceEditor = editor(),
        )

        val started = initial.withStartedMaintenanceEditor(editor())
        val closed = started.withClosedMaintenanceEditor()

        assertThat(started.assetSearch).isEmpty()
        assertThat(started.assetSearchResults).isEmpty()
        assertThat(started.assetSearchCompletedQuery).isNull()
        assertThat(started.assetSearchFailedQuery).isNull()
        assertThat(started.maintenanceEditor).isNotNull()
        assertThat(closed.assetSearch).isEmpty()
        assertThat(closed.assetSearchResults).isEmpty()
        assertThat(closed.assetSearchCompletedQuery).isNull()
        assertThat(closed.assetSearchFailedQuery).isNull()
        assertThat(closed.maintenanceEditor).isNull()
    }

    @Test
    fun `existing repair keeps immutable asset date and source while editing its plan`() {
        val original = editor().copy(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = "repair-1",
            expectedVersion = 4,
            documentState = "QUEUED",
            selectedAsset = asset("asset-1"),
            dispatchDate = "2026-07-27",
            sourceParty = "Original source",
        )
        val candidate = original.copy(
            selectedAsset = asset("asset-2"),
            dispatchDate = "2026-08-01",
            sourceParty = "Changed source",
            documentState = "DRAFT",
            lines = listOf(line("line-1")),
            priority = 1,
        )

        val result = preserveMaintenanceImmutableFields(original, candidate)

        assertThat(result.selectedAsset).isEqualTo(original.selectedAsset)
        assertThat(result.dispatchDate).isEqualTo(original.dispatchDate)
        assertThat(result.sourceParty).isEqualTo(original.sourceParty)
        assertThat(result.documentState).isEqualTo("QUEUED")
        assertThat(result.lines).isEqualTo(candidate.lines)
        assertThat(result.priority).isEqualTo(candidate.priority)
    }

    @Test
    fun `rework keeps immutable source identity while its reason remains editable`() {
        val original = editor().copy(
            mode = MaintenanceEditorMode.REPAIR,
            repairKind = "REWORK",
            sourceRepairId = "source-repair-1",
            sourceRepairExpectedVersion = 8,
            reworkReason = "Первоначальная причина",
        )
        val candidate = original.copy(
            repairKind = "PRIMARY",
            sourceRepairId = "other-repair",
            sourceRepairExpectedVersion = 99,
            reworkReason = "Уточнённая причина",
        )

        val result = preserveMaintenanceImmutableFields(original, candidate)

        assertThat(result.repairKind).isEqualTo("REWORK")
        assertThat(result.sourceRepairId).isEqualTo("source-repair-1")
        assertThat(result.sourceRepairExpectedVersion).isEqualTo(8)
        assertThat(result.reworkReason).isEqualTo("Уточнённая причина")
    }

    @Test
    fun `rework can continue without new child photo while primary repair still requires one`() {
        val primaryRepair = editor().copy(
            mode = MaintenanceEditorMode.REPAIR,
            lines = listOf(line("line-1")),
        )
        val rework = primaryRepair.copy(
            repairKind = "REWORK",
            sourceRepairId = "source-repair-1",
            sourceRepairExpectedVersion = 8,
            reworkReason = "Исправить результат",
        )

        assertThat(maintenanceRequiresPhotos(primaryRepair)).isTrue()
        assertThat(maintenanceRequiresPhotos(rework)).isFalse()
        assertThat(maintenanceRequiresPhotos(editor().copy(lines = emptyList()))).isTrue()
        assertThat(isEmptyMaintenanceOutcome(rework.copy(lines = emptyList()))).isFalse()
    }

    @Test
    fun `new local title is first before existing ready media after upload`() {
        val firstLocal = "content://rwms/first.jpg"
        val titleLocal = "content://rwms/title.jpg"
        val editor = editor().copy(
            photoUris = listOf(firstLocal, titleLocal),
            readyMedia = listOf(
                MediaReferenceDto("ready-1", 1),
                MediaReferenceDto("ready-2", 2),
            ),
            coverPhotoKey = maintenanceLocalPhotoKey(titleLocal),
        )
        val orderedLocalUris = orderedMaintenanceLocalPhotoUris(editor)

        val combined = orderedMaintenanceMediaReferences(
            editor = editor,
            uploadedReferences = listOf(
                MediaReferenceDto("uploaded-title", 3),
                MediaReferenceDto("uploaded-first", 4),
            ),
        )

        assertThat(orderedLocalUris).containsExactly(titleLocal, firstLocal).inOrder()
        assertThat(combined.map(MediaReferenceDto::mediaId)).containsExactly(
            "uploaded-title",
            "uploaded-first",
            "ready-1",
            "ready-2",
        ).inOrder()
    }

    @Test
    fun `selected ready title is first and deleting local title clears selection`() {
        val titleUri = "content://rwms/title.jpg"
        val readyEditor = editor().copy(
            readyMedia = listOf(
                MediaReferenceDto("ready-1", 1),
                MediaReferenceDto("ready-title", 2),
            ),
            coverPhotoKey = maintenanceReadyPhotoKey("ready-title"),
        )
        val localEditor = editor().copy(
            photoUris = listOf(titleUri, "content://rwms/other.jpg"),
            coverPhotoKey = maintenanceLocalPhotoKey(titleUri),
        )

        assertThat(
            orderedMaintenanceMediaReferences(readyEditor, emptyList())
                .map(MediaReferenceDto::mediaId),
        ).containsExactly("ready-title", "ready-1").inOrder()

        val removed = removeMaintenanceLocalPhoto(localEditor, titleUri)
        assertThat(removed.photoUris).doesNotContain(titleUri)
        assertThat(removed.coverPhotoKey).isNull()
        assertThat(maintenanceHasCoverPhoto(removed)).isFalse()
    }

    @Test
    fun `completed maintenance upload is retained when the following command must be retried`() {
        val ordinaryUri = "content://rwms/ordinary.jpg"
        val titleUri = "content://rwms/title.jpg"
        val beforeCommand = editor().copy(
            photoUris = listOf(ordinaryUri, titleUri),
            readyMedia = listOf(MediaReferenceDto("existing", 1)),
            readyPhotoUris = mapOf("existing" to "https://example.test/existing.jpg"),
            coverPhotoKey = maintenanceLocalPhotoKey(titleUri),
        )
        val uploadOrder = orderedMaintenanceLocalPhotoUris(beforeCommand)

        val retained = retainCompletedMaintenanceUploads(
            editor = beforeCommand,
            uploadedLocalUris = uploadOrder,
            uploadedReferences = listOf(
                MediaReferenceDto("uploaded-title", 2),
                MediaReferenceDto("uploaded-ordinary", 3),
            ),
        )

        assertThat(uploadOrder).containsExactly(titleUri, ordinaryUri).inOrder()
        assertThat(retained.photoUris).isEmpty()
        assertThat(orderedMaintenanceLocalPhotoUris(retained)).isEmpty()
        assertThat(retained.readyMedia.map(MediaReferenceDto::mediaId)).containsExactly(
            "uploaded-title",
            "uploaded-ordinary",
            "existing",
        ).inOrder()
        assertThat(retained.readyPhotoUris).containsEntry("uploaded-title", titleUri)
        assertThat(retained.readyPhotoUris).containsEntry("uploaded-ordinary", ordinaryUri)
        assertThat(retained.coverPhotoKey)
            .isEqualTo(maintenanceReadyPhotoKey("uploaded-title"))
        assertThat(maintenanceHasCoverPhoto(retained)).isTrue()
    }

    @Test
    fun `only completed estimate and queued repair use pre-start save behavior`() {
        assertThat(
            maintenanceDocumentAlreadySubmitted(
                editor().copy(documentState = "COMPLETED"),
            ),
        ).isTrue()
        assertThat(
            maintenanceDocumentAlreadySubmitted(
                editor().copy(
                    mode = MaintenanceEditorMode.REPAIR,
                    documentState = "QUEUED",
                ),
            ),
        ).isTrue()
        assertThat(maintenanceDocumentAlreadySubmitted(editor())).isFalse()
    }

    @Test
    fun `material without work produces a standalone routed repair stage`() {
        val routing = RoutingSnapshotDto("queue-material", "Материалы", "REPAIR")
        val material = MaintenanceLineEditorState(
            id = "material-1",
            catalogNodeId = null,
            description = "Крепёж",
            lineType = "MATERIAL",
            unit = "шт.",
            quantity = "2",
            unitPrice = "10.00",
            normativeMinutes = 0,
            comment = "",
            customRouting = routing,
        )

        val stages = planMaintenanceStages(
            editor = editor().copy(lines = listOf(material)),
            routingForLine = MaintenanceLineEditorState::customRouting,
        )

        assertThat(stages).hasSize(1)
        assertThat(stages.single().kind).isEqualTo("REPAIR_WORK")
        assertThat(stages.single().includedLineIds).containsExactly("material-1")
        assertThat(stages.single().primaryLineId).isNull()
        assertThat(stages.single().routing).isEqualTo(routing)
    }

    @Test
    fun `work and material with the same route may share one stage`() {
        val routing = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR")
        val work = line("work-1").copy(catalogNodeId = null, customRouting = routing)
        val material = MaintenanceLineEditorState(
            id = "material-1",
            catalogNodeId = null,
            description = "Материал",
            lineType = "MATERIAL",
            unit = "шт.",
            quantity = "1",
            unitPrice = "0.00",
            normativeMinutes = 0,
            comment = "",
            customRouting = routing,
        )

        val stages = planMaintenanceStages(
            editor = editor().copy(lines = listOf(work, material)),
            routingForLine = MaintenanceLineEditorState::customRouting,
        )

        assertThat(stages).hasSize(1)
        assertThat(stages.single().includedLineIds)
            .containsExactly("work-1", "material-1")
            .inOrder()
        assertThat(stages.single().primaryLineId).isEqualTo("work-1")
    }

    private fun editor(readOnly: Boolean = false): MaintenanceEditorState = MaintenanceEditorState(
        mode = MaintenanceEditorMode.ESTIMATE,
        entityId = null,
        expectedVersion = null,
        readOnly = readOnly,
        selectedAsset = null,
        dispatchDate = "2026-07-27",
        sourceParty = "",
        lines = emptyList(),
        photoUris = emptyList(),
        readyMedia = emptyList(),
        priority = 3,
        step = 0,
    )

    private fun line(id: String): MaintenanceLineEditorState = MaintenanceLineEditorState(
        id = id,
        catalogNodeId = "catalog-$id",
        description = "Work",
        lineType = "WORK",
        unit = "hour",
        quantity = "1",
        unitPrice = "0.00",
        normativeMinutes = 15,
        comment = "",
    )

    private fun asset(id: String): RentalItemDto = RentalItemDto(
        id = id,
        version = 1,
        warehouseId = "warehouse-1",
        number = "CAB-$id",
        status = "FREE",
    )
}
