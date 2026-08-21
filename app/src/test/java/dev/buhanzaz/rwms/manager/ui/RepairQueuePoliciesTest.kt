package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.DeliverySnapshotDto
import dev.buhanzaz.rwms.manager.network.EstimateLineDto
import dev.buhanzaz.rwms.manager.network.RepairDto
import dev.buhanzaz.rwms.manager.network.RepairPlanDto
import dev.buhanzaz.rwms.manager.network.RepairStageDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.network.TaskBoardColumnDto
import dev.buhanzaz.rwms.manager.network.TaskBoardEntryDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSourceDto
import dev.buhanzaz.rwms.manager.network.TaskSyncSnapshotDto
import org.junit.Test

/** Verifies the ManagerApp projection of maintenance repairs onto the aggregate ordinary board. */
class RepairQueuePoliciesTest {
    @Test
    fun `operational stage excludes finished stages and prefers in progress`() {
        val repair = repair(
            stages = listOf(
                stage(id = "done", order = 0, state = "DONE"),
                stage(id = "waiting", order = 1, state = "WAITING"),
                stage(id = "active", order = 4, state = "IN_PROGRESS"),
            ),
        )

        assertThat(operationalRepairStage(repair)?.id).isEqualTo("active")
        assertThat(
            operationalRepairStage(
                repair(
                    stages = listOf(
                        stage(id = "done", order = 0, state = "DONE"),
                        stage(id = "cancelled", order = 1, state = "CANCELLED"),
                    ),
                ),
            ),
        ).isNull()
    }

    @Test
    fun `repair maps only to exact real external route and maintenance source`() {
        val repair = repair(
            id = "repair-1",
            stages = listOf(
                stage(
                    id = "stage-1",
                    order = 2,
                    state = "WAITING",
                    externalTaskId = "external-task-1",
                ),
            ),
        )
        val matchingEntry = entry(
            id = "entry-1",
            repairId = "repair-1",
            externalTaskId = "external-task-1",
            routeIndex = 2,
        )

        val mapped = createRepairQueueItems(board(column(entries = listOf(matchingEntry))), listOf(repair))
        val wrongRoute = createRepairQueueItems(
            board(column(entries = listOf(matchingEntry.copy(routeIndex = 1)))),
            listOf(repair),
        )
        val wrongSource = createRepairQueueItems(
            board(
                column(
                    entries = listOf(
                        matchingEntry.copy(
                            source = TaskBoardSourceDto("MAINTENANCE_REPAIR", "repair-2"),
                        ),
                    ),
                ),
            ),
            listOf(repair),
        )
        val missingSource = createRepairQueueItems(
            board(column(entries = listOf(matchingEntry.copy(source = null)))),
            listOf(repair),
        )
        val shadow = createRepairQueueItems(
            board(column(entries = listOf(matchingEntry.copy(entryType = "SHADOW")))),
            listOf(repair),
        )

        assertThat(mapped).hasSize(1)
        assertThat(mapped.single().entry.id).isEqualTo("entry-1")
        assertThat(mapped.single().stage.id).isEqualTo("stage-1")
        assertThat(wrongRoute).isEmpty()
        assertThat(wrongSource).isEmpty()
        assertThat(missingSource).isEmpty()
        assertThat(shadow).isEmpty()
    }

    @Test
    fun `aggregate sections follow queue and card order without grouping by scheduled date`() {
        val firstQueue = column(
            id = "queue-first",
            name = "Электрика",
            sortOrder = 10,
            entries = listOf(
                entry(
                    id = "entry-a",
                    repairId = "repair-a",
                    queueId = "queue-first",
                    queuePosition = 2,
                    scheduledDate = "2026-08-21",
                ),
                entry(
                    id = "entry-c",
                    repairId = "repair-c",
                    queueId = "queue-first",
                    queuePosition = 0,
                    scheduledDate = "2026-09-04",
                ),
            ),
        )
        val secondQueue = column(
            id = "queue-second",
            name = "Внутренние работы",
            sortOrder = 20,
            entries = listOf(
                entry(
                    id = "entry-b",
                    repairId = "repair-b",
                    queueId = "queue-second",
                    queuePosition = 0,
                    scheduledDate = "2026-08-01",
                ),
            ),
        )
        val board = board(secondQueue, firstQueue)
        val items = createRepairQueueItems(
            board,
            listOf(repair("repair-b"), repair("repair-a"), repair("repair-c")),
        )

        val sections = repairQueueSections(board, items)

        assertThat(sections.map { it.queue.queueId })
            .containsExactly("queue-first", "queue-second")
            .inOrder()
        assertThat(sections.first().items.map { it.repair.id })
            .containsExactly("repair-c", "repair-a")
            .inOrder()
        assertThat(sections.last().items.map { it.repair.id })
            .containsExactly("repair-b")
    }

    @Test
    fun `null aggregate snapshot produces no local repair cards`() {
        assertThat(createRepairQueueItems(null, listOf(repair("repair-1")))).isEmpty()
        assertThat(repairQueueSections(null, emptyList())).isEmpty()
    }

    @Test
    fun `queue info keeps works and materials as separate ordered groups`() {
        val groups = stage(
            id = "stage-lines",
            order = 0,
            state = "WAITING",
        ).copy(
            workLines = listOf(line("Работа 1"), line("Работа 2")),
            materialLines = listOf(line("Материал 1")),
        ).repairQueueLineGroups()

        assertThat(groups.works).containsExactly("Работа 1", "Работа 2").inOrder()
        assertThat(groups.materials).containsExactly("Материал 1")
    }

    private fun repair(
        id: String = "repair-1",
        stages: List<RepairStageDto> = listOf(
            stage(
                id = "stage-$id",
                order = 0,
                state = "WAITING",
                externalTaskId = "external-$id",
            ),
        ),
    ): RepairDto = RepairDto(
        id = id,
        rootRepairId = id,
        warehouseId = "warehouse-1",
        rentalItemId = "asset-$id",
        origin = "DIRECT",
        kind = "PRIMARY",
        executionState = "QUEUED",
        acceptanceState = "NOT_READY",
        version = 4,
        dispatchDate = "2026-07-27",
        plan = RepairPlanDto(
            repairId = id,
            repairVersion = 4,
            stages = stages,
        ),
        createdAt = "2026-07-27T09:00:00Z",
        updatedAt = "2026-07-27T10:00:00Z",
    )

    private fun stage(
        id: String,
        order: Int,
        state: String,
        externalTaskId: String = "external-$id",
    ): RepairStageDto = RepairStageDto(
        id = id,
        kind = "REPAIR_WORK",
        order = order,
        state = state,
        routing = RoutingSnapshotDto(
            queueId = "queue-1",
            queueName = "Ремонт",
            queueType = "REPAIR",
        ),
        taskSync = TaskSyncSnapshotDto(
            externalTaskId = externalTaskId,
            generationState = "REGISTERED",
            delivery = DeliverySnapshotDto(
                state = "DELIVERED",
                attempts = 1,
                updatedAt = "2026-07-27T10:00:00Z",
            ),
        ),
    )

    private fun line(description: String): EstimateLineDto = EstimateLineDto(
        id = description,
        lineType = "WORK",
        description = description,
        quantity = "1",
        unitPrice = "0.00",
        lineTotal = "0.00",
    )

    private fun entry(
        id: String,
        repairId: String,
        externalTaskId: String = "external-$repairId",
        routeIndex: Int = 0,
        queueId: String = "queue-1",
        queuePosition: Int = 0,
        scheduledDate: String = "2026-07-27",
    ): TaskBoardEntryDto = TaskBoardEntryDto(
        id = id,
        version = 6,
        taskId = "task-$id",
        externalTaskId = externalTaskId,
        source = TaskBoardSourceDto("MAINTENANCE_REPAIR", repairId),
        taskVersion = 8,
        title = "Ремонт бытовки",
        unitNumber = "БЫТ-001",
        taskStatus = "ACTIVE",
        scheduledDate = scheduledDate,
        priority = 3,
        pinned = false,
        queueId = queueId,
        routeIndex = routeIndex,
        queuePosition = queuePosition,
        entryType = "REAL",
        status = "WAITING",
        activeWorkSeconds = 0,
    )

    private fun board(vararg columns: TaskBoardColumnDto): TaskBoardSnapshotDto =
        TaskBoardSnapshotDto(
            warehouseId = "warehouse-1",
            columns = columns.toList(),
        )

    private fun column(
        id: String = "queue-1",
        name: String = "Ремонт",
        sortOrder: Int = 0,
        entries: List<TaskBoardEntryDto> = emptyList(),
    ): TaskBoardColumnDto = TaskBoardColumnDto(
        queueId = id,
        queueName = name,
        queueType = "REPAIR",
        sortOrder = sortOrder,
        entries = entries,
    )
}
