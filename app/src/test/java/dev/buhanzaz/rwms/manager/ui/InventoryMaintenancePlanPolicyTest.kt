package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import org.junit.Test

class InventoryMaintenancePlanPolicyTest {
    @Test
    fun `inventory plan keeps only priority and repair delivery intent`() {
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
            planMovementToRepair = true,
            planLogisticsPlanningMode = LOGISTICS_PLANNING_MODE_AUTO,
            planLogisticsScheduledDate = null,
        )

        val maintenance = inventory.toMaintenancePlanEditor()
        val changed = inventory.withMaintenancePlanEditor(
            maintenance.copy(priority = 5),
        )

        assertThat(maintenance.sourceParty).isEqualTo("Инвентаризация")
        assertThat(maintenance.lines).containsExactly(line)
        assertThat(maintenance.stages).containsExactly(stage)
        assertThat(maintenance.movementToRepair).isTrue()
        assertThat(maintenance.logisticsPlanningMode)
            .isEqualTo(LOGISTICS_PLANNING_MODE_AUTO)
        assertThat(maintenance.logisticsScheduledDate).isNull()
        assertThat(changed.planPriority).isEqualTo(5)
        assertThat(changed.planLines).containsExactly(line)
        assertThat(changed.planMovementToRepair).isTrue()
        assertThat(changed.planLogisticsPlanningMode)
            .isEqualTo(LOGISTICS_PLANNING_MODE_AUTO)
        assertThat(changed.planLogisticsScheduledDate).isNull()
    }

    @Test
    fun `inventory repair source uses the inventory default when no party was supplied`() {
        assertThat(repairSourceLabel("INVENTORY", null)).isEqualTo("Инвентаризация")
        assertThat(repairSourceLabel("INVENTORY", "Инвентаризация склада"))
            .isEqualTo("Инвентаризация склада")
        assertThat(repairSourceLabel("DIRECT", null)).isEqualTo("—")
    }

    @Test
    fun `inventory plan keeps repeated catalog work as distinct stages`() {
        val firstWork = planLine(id = "work-1", comment = "Первая работа")
        val secondWork = planLine(id = "work-2", comment = "Вторая работа")
        val route = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR")
        val firstStage = MaintenanceStageEditorState(
            id = "stage-1",
            kind = "REPAIR_WORK",
            routing = route,
            includedLineIds = listOf(firstWork.id),
            primaryLineId = firstWork.id,
            groupComment = firstWork.comment,
        )
        val secondStage = MaintenanceStageEditorState(
            id = "stage-2",
            kind = "REPAIR_WORK",
            routing = route,
            includedLineIds = listOf(secondWork.id),
            primaryLineId = secondWork.id,
            groupComment = secondWork.comment,
        )
        val inventory = InventoryEditorState(
            findingId = "finding-1",
            number = "БЫТ-001",
            outcome = "MATCHED",
            planLines = listOf(firstWork, secondWork),
            planStages = listOf(firstStage, secondStage),
        )

        val maintenance = inventory.toMaintenancePlanEditor()
        val roundTripped = inventory.withMaintenancePlanEditor(maintenance)

        assertThat(maintenance.lines.map(MaintenanceLineEditorState::id))
            .containsExactly(firstWork.id, secondWork.id)
            .inOrder()
        assertThat(maintenance.stages.map(MaintenanceStageEditorState::primaryLineId))
            .containsExactly(firstWork.id, secondWork.id)
            .inOrder()
        assertThat(roundTripped.planStages).containsExactly(firstStage, secondStage).inOrder()
    }

    private fun planLine(id: String, comment: String) = MaintenanceLineEditorState(
        id = id,
        catalogNodeId = "catalog-work",
        description = "Замена ДВП",
        lineType = "WORK",
        unit = "ед.",
        quantity = "1",
        unitPrice = "1500.00",
        normativeMinutes = 45,
        comment = comment,
    )
}
