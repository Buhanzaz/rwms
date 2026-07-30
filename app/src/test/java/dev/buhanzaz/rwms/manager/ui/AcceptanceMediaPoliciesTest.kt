package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairPlanDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.TaskEvidenceDto
import org.junit.Test

class AcceptanceMediaPoliciesTest {
    @Test
    fun `linked estimate cabin photos use estimate owner scope and ordered references`() {
        val repair = repair(estimateId = "estimate-1")
        val estimate = EstimateDto(
            id = "estimate-1",
            warehouseId = "warehouse-1",
            rentalItemId = "asset-1",
            version = 2,
            lifecycle = "COMPLETED",
            currentRevision = 1,
            mediaReferences = listOf(
                MediaReferenceDto("cover-media", 2),
                MediaReferenceDto("second-media", 1),
            ),
            createdAt = "2026-07-27T09:00:00Z",
        )

        val collection = acceptanceCabinMedia(repair, estimate)

        assertThat(collection.items.map { it.reference.mediaId })
            .containsExactly("cover-media", "second-media")
            .inOrder()
        assertThat(collection.items.map(AcceptanceScopedMedia::ownerType).distinct())
            .containsExactly("MAINTENANCE_ESTIMATE")
        assertThat(collection.items.map(AcceptanceScopedMedia::ownerId).distinct())
            .containsExactly("estimate-1")
        assertThat(collection.items.map(AcceptanceScopedMedia::context).distinct())
            .containsExactly("ESTIMATE")
    }

    @Test
    fun `direct and rework cabin photos use their repair owner`() {
        val direct = repair(
            mediaReferences = listOf(MediaReferenceDto("direct-media", 3)),
        )
        val rework = repair(
            id = "rework-1",
            kind = "REWORK",
            estimateId = "legacy-estimate",
            mediaReferences = listOf(MediaReferenceDto("rework-media", 4)),
        )

        val directCollection = acceptanceCabinMedia(direct, null)
        val reworkCollection = acceptanceCabinMedia(rework, null)

        assertThat(directCollection.items.single()).isEqualTo(
            AcceptanceScopedMedia(
                MediaReferenceDto("direct-media", 3),
                "MAINTENANCE_REPAIR",
                "repair-1",
                "REPAIR",
            ),
        )
        assertThat(reworkCollection.items.single()).isEqualTo(
            AcceptanceScopedMedia(
                MediaReferenceDto("rework-media", 4),
                "MAINTENANCE_REPAIR",
                "rework-1",
                "REPAIR",
            ),
        )
    }

    @Test
    fun `stage evidence keeps exact task board entry owner media and generation`() {
        val stage = stage(
            evidence = listOf(
                evidence(
                    evidenceId = "evidence-1",
                    entryId = "entry-1",
                    mediaId = "media-1",
                    generation = 7,
                ),
                evidence(
                    evidenceId = "evidence-2",
                    entryId = "entry-2",
                    mediaId = "media-2",
                    generation = 9,
                ),
            ),
        )

        val collection = acceptanceStageMedia(stage)

        assertThat(collection.items).containsExactly(
            AcceptanceScopedMedia(
                MediaReferenceDto("media-1", 7),
                "TASK_BOARD_ENTRY",
                "entry-1",
                "WORK_RESULT",
            ),
            AcceptanceScopedMedia(
                MediaReferenceDto("media-2", 9),
                "TASK_BOARD_ENTRY",
                "entry-2",
                "WORK_RESULT",
            ),
        ).inOrder()
        assertThat(acceptanceStageMedia(stage(evidence = emptyList())).items).isEmpty()
    }

    @Test
    fun `acceptance can submit only after a local or ready acceptance photo exists`() {
        val editor = MaintenanceAcceptanceEditorState(
            repair = repair(),
            asset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БЫТ-001",
                status = "WAITING_REPAIR_CHECK",
            ),
            cabinPhotos = AcceptanceMediaCollection(
                title = "Фото бытовки",
                items = emptyList(),
                emptyMessage = "Нет фото",
            ),
        )

        assertThat(editor.hasAcceptanceEvidence()).isFalse()
        assertThat(editor.copy(photoUris = listOf("content://camera/1")).hasAcceptanceEvidence())
            .isTrue()
        assertThat(editor.copy(readyMedia = listOf(MediaReferenceDto("media-1", 1))).hasAcceptanceEvidence())
            .isTrue()
    }

    private fun repair(
        id: String = "repair-1",
        kind: String = "PRIMARY",
        estimateId: String? = null,
        mediaReferences: List<MediaReferenceDto> = emptyList(),
    ): RepairDto = RepairDto(
        id = id,
        rootRepairId = id,
        estimateId = estimateId,
        warehouseId = "warehouse-1",
        rentalItemId = "asset-1",
        origin = "DIRECT",
        kind = kind,
        executionState = "COMPLETED",
        acceptanceState = "PENDING",
        version = 5,
        dispatchDate = "2026-07-27",
        plan = RepairPlanDto(
            repairId = id,
            repairVersion = 5,
            stages = listOf(stage()),
        ),
        mediaReferences = mediaReferences,
        createdAt = "2026-07-27T09:00:00Z",
        updatedAt = "2026-07-27T10:00:00Z",
    )

    private fun stage(
        evidence: List<TaskEvidenceDto> = emptyList(),
    ): RepairStageDto = RepairStageDto(
        id = "stage-1",
        kind = "REPAIR_WORK",
        order = 0,
        state = "DONE",
        routing = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR"),
        evidence = evidence,
    )

    private fun evidence(
        evidenceId: String,
        entryId: String,
        mediaId: String,
        generation: Long,
    ): TaskEvidenceDto = TaskEvidenceDto(
        evidenceId = evidenceId,
        entryId = entryId,
        workerId = "worker-1",
        mediaId = mediaId,
        mediaGeneration = generation,
        capturedAt = "2026-07-27T09:30:00Z",
        recordedAt = "2026-07-27T09:31:00Z",
        state = "READY",
    )
}
