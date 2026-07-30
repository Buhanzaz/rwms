package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import org.junit.Test

class InventoryMaintenancePlanPolicyTest {
    @Test
    fun `inventory plan reuses maintenance editor without losing priority or stages`() {
        val line = MaintenanceLineEditorState(
            id = "line-1",
            catalogNodeId = "node-1",
            description = "Замена ДВП",
            lineType = "WORK",
            unit = "ед.",
            quantity = "1",
            unitPrice = "1500.00",
            normativeMinutes = 45,
            comment = "",
        )
        val stage = MaintenanceStageEditorState(
            id = "stage-1",
            kind = "REPAIR_WORK",
            routing = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR"),
            includedLineIds = listOf(line.id),
            primaryLineId = line.id,
            groupComment = "",
        )
        val inventory = InventoryEditorState(
            findingId = "finding-1",
            number = "БЫТ-001",
            outcome = "MATCHED",
            planLines = listOf(line),
            planStages = listOf(stage),
            planPriority = 2,
        )

        val maintenance = inventory.toMaintenancePlanEditor()
        val changed = inventory.withMaintenancePlanEditor(
            maintenance.copy(priority = 5),
        )

        assertThat(maintenance.sourceParty).isEqualTo("Инвентаризация")
        assertThat(maintenance.lines).containsExactly(line)
        assertThat(maintenance.stages).containsExactly(stage)
        assertThat(changed.planPriority).isEqualTo(5)
        assertThat(changed.planLines).containsExactly(line)
    }

    @Test
    fun `inventory repair source falls back for legacy repair only`() {
        assertThat(repairSourceLabel("INVENTORY", null)).isEqualTo("Инвентаризация")
        assertThat(repairSourceLabel("INVENTORY", "Инвентаризация склада"))
            .isEqualTo("Инвентаризация склада")
        assertThat(repairSourceLabel("DIRECT", null)).isEqualTo("—")
    }
}
