package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.PriorityVersionRequest
import dev.buhanzaz.rwms.manager.network.ReturnEstimateInspectionDto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadOperation
import dev.buhanzaz.rwms.manager.uploads.MaintenanceReplaceKind
import dev.buhanzaz.rwms.manager.uploads.MaintenanceUploadCommand
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MaintenanceReturnInspectionPolicyTest {
    @Test
    fun `only a submitted estimate removed from the observed queue is selected`() {
        val completion = operation("completion", mode = "ESTIMATE", submitted = true)
        val draft = operation("draft", mode = "ESTIMATE", submitted = false)
        val repair = operation("repair", mode = "REPAIR", submitted = true)

        val removed = removedEstimateCompletions(
            previous = listOf(completion, draft, repair),
            current = listOf(draft, repair),
        )

        assertThat(removed.map(BackgroundUploadOperation::id)).containsExactly("completion")
    }

    @Test
    fun `pending evidence is reread without invoking any completion command`() = runTest {
        val responses = ArrayDeque(
            listOf(
                ReturnEstimateInspectionDto("PENDING"),
                ReturnEstimateInspectionDto("PENDING"),
                ReturnEstimateInspectionDto("CONFIRMED", "inventory", "finding", "CAB-17"),
            ),
        )
        val waits = mutableListOf<Long>()

        val result = awaitReturnEstimateInspection(
            read = { responses.removeFirst() },
            delaysMs = listOf(100, 200, 400),
            wait = { waits.add(it) },
        )

        assertThat(result.state).isEqualTo("CONFIRMED")
        assertThat(waits).containsExactly(100L, 200L).inOrder()
    }

    @Test
    fun `draft estimate removal does not read inventory proof`() = runTest {
        var inspectionReads = 0

        val result = awaitCompletedReturnEstimateInspection(
            readEstimateLifecycle = { "DRAFT" },
            readInspection = {
                inspectionReads += 1
                ReturnEstimateInspectionDto("CONFIRMED", "inventory", "finding", "CAB-17")
            },
            delaysMs = emptyList(),
        )

        assertThat(result).isNull()
        assertThat(inspectionReads).isEqualTo(0)
    }

    @Test
    fun `completed estimate removal polls inventory proof`() = runTest {
        var inspectionReads = 0

        val result = awaitCompletedReturnEstimateInspection(
            readEstimateLifecycle = { "COMPLETED" },
            readInspection = {
                inspectionReads += 1
                ReturnEstimateInspectionDto("CONFIRMED", "inventory", "finding", "CAB-17")
            },
            delaysMs = emptyList(),
        )

        assertThat(result?.state).isEqualTo("CONFIRMED")
        assertThat(inspectionReads).isEqualTo(1)
    }

    @Test
    fun `confirmed acknowledgements remain FIFO and are cleared outside their scope`() {
        val first = confirmation("operation-1", "account-1", "warehouse-1")
        val second = confirmation("operation-2", "account-1", "warehouse-1")
        val queued = enqueueReturnConfirmation(
            enqueueReturnConfirmation(emptyList(), first),
            second,
        )

        assertThat(queued.map(InventoryReturnConfirmation::operationId))
            .containsExactly("operation-1", "operation-2")
            .inOrder()
        assertThat(
            retainReturnConfirmationsForScope(queued, "account-2", "warehouse-1"),
        ).isEmpty()
        assertThat(
            retainReturnConfirmationsForScope(queued, "account-1", "warehouse-1"),
        ).containsExactlyElementsIn(queued).inOrder()
    }

    private fun confirmation(operationId: String, accountId: String, warehouseId: String) =
        InventoryReturnConfirmation(
            operationId = operationId,
            estimateId = "estimate-$operationId",
            cabinNumber = "CAB-17",
            ownerAccountId = accountId,
            warehouseId = warehouseId,
        )

    private fun operation(id: String, mode: String, submitted: Boolean) =
        BackgroundUploadOperation(
            id = id,
            area = BackgroundUploadArea.MAINTENANCE,
            title = "Смета",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            maintenance = MaintenanceUploadCommand(
                mode = mode,
                entityId = "estimate-$id",
                warehouseId = "warehouse-1",
                expectedVersion = 1,
                dispatchDate = "2026-09-07",
                lines = emptyList(),
                stages = emptyList(),
                replaceKind = MaintenanceReplaceKind.NONE,
                submitRequest = if (submitted) {
                    PriorityVersionRequest(
                        expectedVersion = 1,
                        priority = 3,
                        movementToRepair = false,
                        logisticsPlanningMode = "AUTO",
                        logisticsScheduledDate = null,
                    )
                } else {
                    null
                },
            ),
            ownerAccountId = "account-1",
            warehouseId = "warehouse-1",
        )
}
