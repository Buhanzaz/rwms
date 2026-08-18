package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanLineDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanStageDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import org.junit.Test

class InventoryMaintenancePlanPolicyTest {
    @Test
    fun `inventory plan keeps priority and the selected capital repair route`() {
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
            planForceCapitalRepair = true,
            planMovementToRepair = false,
            planLogisticsPlanningMode = null,
            planLogisticsScheduledDate = null,
        )

        val maintenance = inventory.toMaintenancePlanEditor()
        val changed = inventory.withMaintenancePlanEditor(
            maintenance.copy(priority = 5, forceCapitalRepair = false),
        )

        assertThat(maintenance.sourceParty).isEqualTo("Инвентаризация")
        assertThat(maintenance.lines).containsExactly(line)
        assertThat(maintenance.stages).containsExactly(stage)
        assertThat(maintenance.forceCapitalRepair).isTrue()
        assertThat(maintenance.movementToRepair).isFalse()
        assertThat(maintenance.logisticsPlanningMode).isNull()
        assertThat(maintenance.logisticsScheduledDate).isNull()
        assertThat(changed.planPriority).isEqualTo(5)
        assertThat(changed.planForceCapitalRepair).isFalse()
        assertThat(changed.planLines).containsExactly(line)
        assertThat(changed.planMovementToRepair).isFalse()
        assertThat(changed.planLogisticsPlanningMode).isNull()
        assertThat(changed.planLogisticsScheduledDate).isNull()
    }

    @Test
    fun `empty inventory plan clears hidden capital repair choice`() {
        val inventory = InventoryEditorState(
            findingId = "finding-1",
            number = "БЫТ-001",
            outcome = "MATCHED",
            planForceCapitalRepair = true,
        )

        val maintenance = inventory.toMaintenancePlanEditor()
        val roundTripped = inventory.withMaintenancePlanEditor(
            maintenance.copy(forceCapitalRepair = true),
        )

        assertThat(maintenance.forceCapitalRepair).isFalse()
        assertThat(roundTripped.planForceCapitalRepair).isFalse()
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

    @Test
    fun `frozen inventory allocation consumes repeated and manual lines exactly once`() {
        val routeId = "queue-1"
        val catalogNodeId = "catalog-work"
        val lines = listOf(
            frozenLine("work-1", "CATALOG", "WORK", catalogNodeId, routeId),
            frozenLine("work-2", "CATALOG", "WORK", catalogNodeId, routeId),
            frozenLine("custom-work", "MANUAL", "WORK", null, routeId),
            frozenLine("custom-material", "MANUAL", "MATERIAL", null, routeId),
        )
        val stages = (0..2).map { order ->
            InventoryFrozenPlanStageDto(
                id = "stage-$order",
                order = order,
                catalogNodeId = catalogNodeId,
                catalogNodeName = "Замена ДВП",
                kind = "REPAIR_WORK",
                routingQueueId = routeId,
                routingQueueName = "Ремонт",
                routingQueueType = "REPAIR",
                photoRequired = false,
                normativeDurationMinutes = 45,
            )
        }

        val result = inventoryFrozenPlanLineIndexesByStage(lines, stages)

        assertThat(result).containsExactly(
            "stage-0", listOf(0),
            "stage-1", listOf(1),
            "stage-2", listOf(2, 3),
        ).inOrder()
        assertThat(result.values.flatten()).containsExactly(0, 1, 2, 3).inOrder()
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

    private fun frozenLine(
        id: String,
        sourceKind: String,
        lineType: String,
        catalogNodeId: String?,
        routingQueueId: String,
    ) = InventoryFrozenPlanLineDto(
        id = id,
        sourceKind = sourceKind,
        lineType = lineType,
        catalogNodeId = catalogNodeId,
        routingQueueId = routingQueueId,
        routingQueueName = "Ремонт",
        routingQueueType = "REPAIR",
        description = id,
        normalizedDescription = id,
        unit = "шт.",
        quantity = "1",
        unitPriceMinor = 100L,
        normativeMinutes = "1",
    )
}
