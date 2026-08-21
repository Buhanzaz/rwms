package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.TaskBoardColumnDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import org.junit.Test

class MaintenanceRoutingPolicyTest {
    @Test
    fun `custom maintenance rows use live repair and holding queues from the board`() {
        val board = board(
            column("repair", "Внутренние работы", "REPAIR"),
            column("movement", "Перемещение", "MOVEMENT"),
            column("holding", "Ожидание", "HOLDING"),
            column("repair", "Внутренние работы", "REPAIR"),
            column("furniture", "Мебель", "FURNITURE_MOVEMENT"),
        )

        assertThat(board.maintenanceWorkRoutingOptions()).containsExactly(
            RoutingSnapshotDto("repair", "Внутренние работы", "REPAIR"),
            RoutingSnapshotDto("holding", "Ожидание", "HOLDING"),
        ).inOrder()
    }

    @Test
    fun `movement to repair keeps selected driver priority and never injects board stages`() {
        val workRoute = RoutingSnapshotDto("repair", "Ремонт", "REPAIR")
        val editor = editorWithWork(workRoute)
            .copy(priority = 5)
            .withMovementToRepair(required = true)
            .withLogisticsPlanningMode(LOGISTICS_PLANNING_MODE_FIXED_DATE)
            .withLogisticsScheduledDate("2026-08-03")

        val stages = planMaintenanceStages(editor, MaintenanceLineEditorState::customRouting)

        assertThat(stages.map { stage -> stage.kind }).containsExactly("REPAIR_WORK")
        assertThat(stages.single().includedLineIds).containsExactly("work-1")
        assertThat(editor.movementToRepair).isTrue()
        assertThat(editor.logisticsPlanningMode).isEqualTo(LOGISTICS_PLANNING_MODE_FIXED_DATE)
        assertThat(editor.logisticsScheduledDate).isEqualTo("2026-08-03")
        assertThat(editor.priority).isEqualTo(5)
        assertThat(editor.logisticsPlanningValidationError()).isNull()
        assertThat(editor.withMovementToRepair(false).run {
            listOf(movementToRepair, logisticsPlanningMode, logisticsScheduledDate)
        }).containsExactly(false, null, null)
    }

    @Test
    fun `fixed logistics date is required only for movement to repair`() {
        val missingDate = editorWithWork(RoutingSnapshotDto("repair", "Ремонт", "REPAIR"))
            .withMovementToRepair(true)
            .withLogisticsPlanningMode(LOGISTICS_PLANNING_MODE_FIXED_DATE)

        assertThat(missingDate.logisticsPlanningValidationError())
            .isEqualTo("Выберите дату перемещения в формате ГГГГ-ММ-ДД")
        assertThat(missingDate.withLogisticsPlanningMode(LOGISTICS_PLANNING_MODE_AUTO)
            .logisticsPlanningValidationError()).isNull()
    }

    @Test
    fun `catalog display color accepts only configured RGB values and picks readable text`() {
        assertThat(catalogDisplayColorArgb("#0A5A8B")).isEqualTo(0xFF0A5A8BL)
        assertThat(catalogDisplayColorArgb("0A5A8B")).isNull()
        assertThat(catalogDisplayColorArgb("#0A5A8B77")).isNull()
        assertThat(catalogDisplayColorNeedsLightContent("#0A5A8B")).isTrue()
        assertThat(catalogDisplayColorNeedsLightContent("#F5D000")).isFalse()
    }

    private fun editorWithWork(route: RoutingSnapshotDto): MaintenanceEditorState =
        MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = null,
            dispatchDate = "2026-07-29",
            sourceParty = "app-приложение",
            lines = listOf(
                MaintenanceLineEditorState(
                    id = "work-1",
                    catalogNodeId = null,
                    description = "Ремонт стен",
                    lineType = "WORK",
                    unit = "ед.",
                    quantity = "1",
                    unitPrice = "0.00",
                    normativeMinutes = 30,
                    comment = "",
                    customRouting = route,
                ),
            ),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = 3,
            step = 5,
        )

    private fun board(vararg columns: TaskBoardColumnDto): TaskBoardSnapshotDto = TaskBoardSnapshotDto(
        warehouseId = "warehouse-1",
        columns = columns.toList(),
    )

    private fun column(id: String, name: String, type: String): TaskBoardColumnDto =
        TaskBoardColumnDto(
            queueId = id,
            queueName = name,
            queueType = type,
            sortOrder = 0,
        )
}
