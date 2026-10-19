package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.ObservationInput
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadArea
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadOperation
import dev.buhanzaz.rwms.manager.uploads.InventoryUploadCommand
import org.junit.Test

class InventoryUploadCompletionRefreshPolicyTest {
    @Test
    fun `removed upload refreshes only its active inventory and warehouse`() {
        val removed = operation("operation-1", "inventory-1", "warehouse-1")

        assertThat(
            inventoryUploadRemovalRequiresRefresh(
                previous = listOf(removed),
                current = emptyList(),
                ownerAccountId = "account-1",
                inventoryId = "inventory-1",
                warehouseId = "warehouse-1",
            ),
        ).isTrue()
        assertThat(
            inventoryUploadRemovalRequiresRefresh(
                previous = listOf(removed),
                current = emptyList(),
                ownerAccountId = "account-1",
                inventoryId = "inventory-2",
                warehouseId = "warehouse-1",
            ),
        ).isFalse()
        assertThat(
            inventoryUploadRemovalRequiresRefresh(
                previous = listOf(removed),
                current = emptyList(),
                ownerAccountId = "account-1",
                inventoryId = "inventory-1",
                warehouseId = "warehouse-2",
            ),
        ).isFalse()
        assertThat(
            inventoryUploadRemovalRequiresRefresh(
                previous = listOf(removed),
                current = listOf(removed),
                ownerAccountId = "account-1",
                inventoryId = "inventory-1",
                warehouseId = "warehouse-1",
            ),
        ).isFalse()
        assertThat(
            inventoryUploadRemovalRequiresRefresh(
                previous = listOf(removed),
                current = emptyList(),
                ownerAccountId = "account-2",
                inventoryId = "inventory-1",
                warehouseId = "warehouse-1",
            ),
        ).isFalse()
    }

    private fun operation(
        id: String,
        inventoryId: String,
        warehouseId: String,
    ) = BackgroundUploadOperation(
        id = id,
        area = BackgroundUploadArea.INVENTORY,
        title = "Осмотр",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        inventory = InventoryUploadCommand(
            inventoryId = inventoryId,
            findingId = "finding-1",
            expectedFindingRevision = 1,
            inspection = "READY",
            comment = "",
            passportObservation = ObservationInput("ABSENT", null),
            equipmentObservation = ObservationInput("ABSENT", null),
        ),
        ownerAccountId = "account-1",
        warehouseId = warehouseId,
    )
}
