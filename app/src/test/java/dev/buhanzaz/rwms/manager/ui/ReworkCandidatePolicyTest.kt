package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.ReworkCandidateDto
import org.junit.Test

class ReworkCandidatePolicyTest {
    @Test
    fun `work and material repeats are selected independently with new child ids`() {
        val work = candidate(
            sourceLineId = "source-work",
            lineageRootLineId = "root-work",
            lineType = "WORK",
            description = "Замена ДВП",
        )
        val material = candidate(
            sourceLineId = "source-material",
            lineageRootLineId = "root-material",
            lineType = "MATERIAL",
            description = "ДВП",
        )
        val initial = reworkEditor(listOf(work, material))

        val withWork = initial.toggleReworkCandidate(work) { "child-work" }
        val withBoth = withWork.toggleReworkCandidate(material) { "child-material" }
        val materialOnly = withBoth.toggleReworkCandidate(work) { "unused" }

        assertThat(withBoth.lines.map(MaintenanceLineEditorState::id))
            .containsExactly("child-work", "child-material")
            .inOrder()
        assertThat(withBoth.lines.map(MaintenanceLineEditorState::reworkDisposition))
            .containsExactly("REPEAT", "REPEAT")
        assertThat(materialOnly.lines.single().sourceLineId).isEqualTo("source-material")
    }

    @Test
    fun `selecting same lineage toggles existing repeat instead of duplicating it`() {
        val candidate = candidate(
            sourceLineId = "source-work",
            lineageRootLineId = "root-work",
            lineType = "WORK",
            description = "Каркас",
        )
        val selected = reworkEditor(listOf(candidate))
            .toggleReworkCandidate(candidate) { "child-1" }
        val cleared = selected.toggleReworkCandidate(
            candidate.copy(sourceLineId = "newer-source-line"),
        ) { "child-2" }

        assertThat(selected.lines).hasSize(1)
        assertThat(cleared.lines).isEmpty()
    }

    private fun reworkEditor(candidates: List<ReworkCandidateDto>) =
        MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = null,
            dispatchDate = "2026-07-29",
            sourceParty = "",
            lines = emptyList(),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = 3,
            step = 3,
            repairKind = "REWORK",
            sourceRepairId = "source-repair",
            sourceRepairExpectedVersion = 5,
            reworkCandidates = candidates,
        )

    private fun candidate(
        sourceLineId: String,
        lineageRootLineId: String,
        lineType: String,
        description: String,
    ) = ReworkCandidateDto(
        sourceRepairId = "source-repair",
        sourceLineId = sourceLineId,
        lineageRootLineId = lineageRootLineId,
        line = EstimateLineDto(
            id = sourceLineId,
            lineType = lineType,
            description = description,
            unit = "ед.",
            quantity = "1",
            unitPrice = "100.00",
            lineTotal = "100.00",
        ),
    )
}
