package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.TaskBoardColumnDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import org.junit.Test

class MaintenanceRoutingPolicyTest {
    @Test
    fun `custom maintenance rows use live repair and holding queues from the board`() {
        val boards = listOf(
            board(
                column("repair", "Внутренние работы", "REPAIR"),
                column("movement", "Перемещение", "MOVEMENT"),
                column("holding", "Ожидание", "HOLDING"),
            ),
            board(
                column("repair", "Внутренние работы", "REPAIR"),
                column("furniture", "Мебель", "FURNITURE_MOVEMENT"),
            ),
        )

        assertThat(boards.maintenanceWorkRoutingOptions()).containsExactly(
            RoutingSnapshotDto("repair", "Внутренние работы", "REPAIR"),
            RoutingSnapshotDto("holding", "Ожидание", "HOLDING"),
        ).inOrder()
    }

    @Test
    fun `movement pair is available only for a single live movement queue`() {
        val unique = listOf(board(column("move", "Перемещение", "MOVEMENT")))
        val ambiguous = listOf(
            board(
                column("move-a", "Перемещение А", "MOVEMENT"),
                column("move-b", "Перемещение Б", "MOVEMENT"),
            ),
        )

        assertThat(unique.singleMaintenanceMovementRouting())
            .isEqualTo(RoutingSnapshotDto("move", "Перемещение", "MOVEMENT"))
        assertThat(unique.maintenanceMovementRoutingProblem()).isNull()
        assertThat(ambiguous.singleMaintenanceMovementRouting()).isNull()
        assertThat(ambiguous.maintenanceMovementRoutingProblem()).contains("несколько")
    }

    @Test
    fun `movement checkbox wraps work stages without replacing them`() {
        val workRoute = RoutingSnapshotDto("repair", "Ремонт", "REPAIR")
        val moveRoute = RoutingSnapshotDto("move", "Перемещение", "MOVEMENT")
        val editor = editorWithWork(workRoute).withRepairMovementStages(
            required = true,
            movementRouting = moveRoute,
        )

        val stages = planMaintenanceStages(editor, MaintenanceLineEditorState::customRouting)

        assertThat(stages.map { stage -> stage.kind }).containsExactly(
            "MOVE_TO_REPAIR",
            "REPAIR_WORK",
            "MOVE_FROM_REPAIR",
        ).inOrder()
        assertThat(stages[1].includedLineIds).containsExactly("work-1")
        assertThat(stages[0].routing).isEqualTo(moveRoute)
        assertThat(stages[2].routing).isEqualTo(moveRoute)
        assertThat(editor.withRepairMovementStages(false, null).stages).isEmpty()
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
        selectedDate = "2026-07-29",
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
