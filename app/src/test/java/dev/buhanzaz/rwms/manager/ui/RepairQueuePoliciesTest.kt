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
    fun `repair maps only to exact external task route and matching maintenance source`() {
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
            externalTaskId = "external-task-1",
            routeIndex = 2,
            source = TaskBoardSourceDto("MAINTENANCE_REPAIR", "repair-1"),
        )
        val board = board(entries = listOf(matchingEntry))

        val mapped = createRepairQueueItems(listOf(board), listOf(repair))
        val wrongRoute = createRepairQueueItems(
            listOf(board(entries = listOf(matchingEntry.copy(routeIndex = 1)))),
            listOf(repair),
        )
        val wrongSource = createRepairQueueItems(
            listOf(
                board(
                    entries = listOf(
                        matchingEntry.copy(
                            source = TaskBoardSourceDto("MAINTENANCE_REPAIR", "repair-2"),
                        ),
                    ),
                ),
            ),
            listOf(repair),
        )

        assertThat(mapped).hasSize(1)
        assertThat(mapped.single().entry.id).isEqualTo("entry-1")
        assertThat(mapped.single().stage.id).isEqualTo("stage-1")
        assertThat(wrongRoute).isEmpty()
        assertThat(wrongSource).isEmpty()
    }

    @Test
    fun `only real active waiting entries can be moved`() {
        val movable = entry()

        assertThat(repairQueueEntryCanMove(movable)).isTrue()
        assertThat(repairQueueEntryCanMove(movable.copy(entryType = "SHADOW"))).isFalse()
        assertThat(repairQueueEntryCanMove(movable.copy(taskStatus = "DONE"))).isFalse()
        assertThat(repairQueueEntryCanMove(movable.copy(status = "IN_PROGRESS"))).isFalse()
        assertThat(repairQueueEntryCanMove(movable.copy(status = "PAUSED"))).isFalse()
    }

    @Test
    fun `target index stays after immutable prefix and adjusts for same date removal`() {
        val date = "2026-07-27"
        val immutable = queueItem(
            repairId = "repair-immutable",
            date = date,
            entry = entry(id = "immutable", status = "IN_PROGRESS"),
        )
        val active = queueItem(
            repairId = "repair-active",
            date = date,
            entry = entry(id = "active"),
        )
        val tail = queueItem(
            repairId = "repair-tail",
            date = date,
            entry = entry(id = "tail"),
        )
        val items = listOf(immutable, active, tail)

        assertThat(minimumRepairQueueInsertionIndex(items)).isEqualTo(1)
        assertThat(
            normalizedRepairQueueTargetIndex(
                targetItems = items,
                activeItem = active,
                proposedIndex = 3,
                targetDate = date,
            ),
        ).isEqualTo(2)
        assertThat(
            normalizedRepairQueueTargetIndex(
                targetItems = listOf(immutable, tail),
                activeItem = active,
                proposedIndex = 0,
                targetDate = "2026-07-28",
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `queue dates include available selected and mapped entry dates in order`() {
        val boards = listOf(
            board(
                selectedDate = "2026-07-28",
                availableDates = listOf("2026-07-29", "2026-07-27"),
            ),
        )
        val items = listOf(
            queueItem(
                repairId = "repair-1",
                date = "2026-07-30",
                entry = entry(),
            ),
        )

        assertThat(repairQueueDates(boards, items)).containsExactly(
            "2026-07-27",
            "2026-07-28",
            "2026-07-29",
            "2026-07-30",
        ).inOrder()
    }

    @Test
    fun `empty-space drop resolves only an actual date column`() {
        val columns = listOf(
            RepairQueueDateColumnRange("2026-07-27", left = 12f, right = 228f),
            RepairQueueDateColumnRange("2026-07-28", left = 238f, right = 454f),
        )

        assertThat(repairQueueDateAtHorizontalPosition(columns, 120f))
            .isEqualTo("2026-07-27")
        assertThat(repairQueueDateAtHorizontalPosition(columns, 232f)).isNull()
        assertThat(repairQueueDateAtHorizontalPosition(columns, 500f)).isNull()
    }

    @Test
    fun `empty-space move appends after the source is removed`() {
        val date = "2026-07-27"
        val immutable = queueItem(
            repairId = "repair-immutable",
            date = date,
            entry = entry(id = "immutable", status = "IN_PROGRESS"),
        )
        val active = queueItem(
            repairId = "repair-active",
            date = date,
            entry = entry(id = "active"),
        )
        val tail = queueItem(
            repairId = "repair-tail",
            date = date,
            entry = entry(id = "tail"),
        )

        assertThat(
            repairQueueEndTargetIndex(
                targetItems = listOf(immutable, active, tail),
                activeItem = active,
            ),
        ).isEqualTo(2)
        assertThat(
            repairQueueEndTargetIndex(
                targetItems = listOf(immutable, tail),
                activeItem = active,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `new date must be an ISO calendar date`() {
        assertThat(isRepairQueueCalendarDate("2026-07-29")).isTrue()
        assertThat(isRepairQueueCalendarDate("2024-02-29")).isTrue()
        assertThat(isRepairQueueCalendarDate("2026-02-29")).isFalse()
        assertThat(isRepairQueueCalendarDate("2026-7-29")).isFalse()
        assertThat(isRepairQueueCalendarDate("29-07-2026")).isFalse()
    }

    @Test
    fun `date column reorder preserves local order and reconciles server dates`() {
        val manuallyOrdered = reorderRepairQueueDateColumn(
            dates = listOf("2026-07-27", "2026-07-28", "2026-07-29"),
            activeDate = "2026-07-29",
            targetIndex = 0,
        )

        assertThat(manuallyOrdered).containsExactly(
            "2026-07-29",
            "2026-07-27",
            "2026-07-28",
        ).inOrder()
        assertThat(
            reconcileRepairQueueDateColumnOrder(
                currentOrder = manuallyOrdered,
                availableDates = listOf("2026-07-27", "2026-07-29", "2026-07-30"),
            ),
        ).containsExactly(
            "2026-07-29",
            "2026-07-27",
            "2026-07-30",
        ).inOrder()
    }

    @Test
    fun `date exchange swaps only headers so physical card columns can stay put`() {
        assertThat(
            swapRepairQueueDateColumns(
                dates = listOf("2026-07-30", "2026-07-28", "2026-07-31"),
                firstDate = "2026-07-30",
                secondDate = "2026-07-28",
            ),
        ).containsExactly(
            "2026-07-28",
            "2026-07-30",
            "2026-07-31",
        ).inOrder()
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
        stages: List<RepairStageDto>,
    ): RepairDto = RepairDto(
        id = id,
        rootRepairId = id,
        warehouseId = "warehouse-1",
        rentalItemId = "asset-1",
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
        id: String = "entry-1",
        externalTaskId: String = "external-task-1",
        routeIndex: Int = 0,
        source: TaskBoardSourceDto? = null,
        status: String = "WAITING",
    ): TaskBoardEntryDto = TaskBoardEntryDto(
        id = id,
        version = 6,
        taskId = "task-$id",
        externalTaskId = externalTaskId,
        source = source,
        taskVersion = 8,
        title = "Ремонт бытовки",
        unitNumber = "БЫТ-001",
        taskStatus = "ACTIVE",
        scheduledDate = "2026-07-27",
        priority = 3,
        pinned = false,
        queueId = "queue-1",
        routeIndex = routeIndex,
        queuePosition = 0,
        entryType = "REAL",
        status = status,
        activeWorkSeconds = 0,
    )

    private fun board(
        entries: List<TaskBoardEntryDto> = emptyList(),
        selectedDate: String = "2026-07-27",
        availableDates: List<String> = listOf(selectedDate),
    ): TaskBoardSnapshotDto = TaskBoardSnapshotDto(
        warehouseId = "warehouse-1",
        selectedDate = selectedDate,
        availableDates = availableDates,
        columns = listOf(
            TaskBoardColumnDto(
                queueId = "queue-1",
                queueName = "Ремонт",
                queueType = "REPAIR",
                sortOrder = 0,
                entries = entries,
            ),
        ),
    )

    private fun queueItem(
        repairId: String,
        date: String,
        entry: TaskBoardEntryDto,
    ): RepairQueueItem {
        val repair = repair(
            id = repairId,
            stages = listOf(
                stage(
                    id = "stage-$repairId",
                    order = entry.routeIndex,
                    state = entry.status,
                    externalTaskId = entry.externalTaskId.orEmpty(),
                ),
            ),
        )
        return RepairQueueItem(
            repair = repair,
            stage = repair.plan.stages.single(),
            entry = entry.copy(scheduledDate = date),
            queue = board(entries = listOf(entry)).columns.single(),
            date = date,
            boardOrder = 0,
        )
    }
}
