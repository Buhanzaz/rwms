package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import org.junit.Test

class InventoryFurnitureDispositionPolicyTest {
    @Test
    fun `keeping furniture creates a task plan for the observed composition`() {
        val observed = listOf(CabinFurnitureRequirementDto("chair", 2))
        val plan = inventoryFurnitureMovePlan(
            disposition = InventoryFurnitureDisposition.KEEP_IN_CABIN,
            inventoryId = "inventory-1",
            findingId = "finding-1",
            rentalItemId = "cabin-1",
            warehouseId = "warehouse-1",
            scheduledDate = "2026-07-28",
            observedContents = observed,
        )

        assertThat(plan.desiredContents).containsExactlyElementsIn(observed)
    }

    @Test
    fun `move to stock has an empty desired composition and a stable retry key`() {
        val observed = listOf(CabinFurnitureRequirementDto("chair", 2))
        val first = inventoryFurnitureMovePlan(
            disposition = InventoryFurnitureDisposition.MOVE_TO_STOCK,
            inventoryId = "inventory-1",
            findingId = "finding-1",
            rentalItemId = "cabin-1",
            warehouseId = "warehouse-1",
            scheduledDate = "2026-07-28",
            observedContents = observed,
        )
        val retry = inventoryFurnitureMovePlan(
            disposition = InventoryFurnitureDisposition.MOVE_TO_STOCK,
            inventoryId = "inventory-1",
            findingId = "finding-1",
            rentalItemId = "cabin-1",
            warehouseId = "warehouse-1",
            scheduledDate = "2026-07-28",
            observedContents = observed,
        )

        assertThat(first.desiredContents).isEmpty()
        assertThat(first.idempotencyKey).isEqualTo(retry.idempotencyKey)
        assertThat(first.scheduledDate).isEqualTo("2026-07-28")
    }

    @Test
    fun `a different scheduled date gets a different move key`() {
        val desired = listOf(CabinFurnitureRequirementDto("chair", 2))
        val today = inventoryFurnitureReconciliationIdempotencyKey(
            inventoryId = "inventory-1",
            findingId = "finding-1",
            rentalItemId = "cabin-1",
            scheduledDate = "2026-07-28",
            desiredContents = desired,
        )
        val nextDay = inventoryFurnitureReconciliationIdempotencyKey(
            inventoryId = "inventory-1",
            findingId = "finding-1",
            rentalItemId = "cabin-1",
            scheduledDate = "2026-07-29",
            desiredContents = desired,
        )

        assertThat(today).isNotEqualTo(nextDay)
    }

    @Test
    fun `a different observed composition gets a different retry key`() {
        val twoChairs = inventoryFurnitureReconciliationIdempotencyKey(
            inventoryId = "inventory-1",
            findingId = "finding-1",
            rentalItemId = "cabin-1",
            scheduledDate = "2026-07-28",
            desiredContents = listOf(CabinFurnitureRequirementDto("chair", 2)),
        )
        val threeChairs = inventoryFurnitureReconciliationIdempotencyKey(
            inventoryId = "inventory-1",
            findingId = "finding-1",
            rentalItemId = "cabin-1",
            scheduledDate = "2026-07-28",
            desiredContents = listOf(CabinFurnitureRequirementDto("chair", 3)),
        )

        assertThat(twoChairs).isNotEqualTo(threeChairs)
    }

    @Test
    fun `zero-only observed furniture skips the disposition prompt`() {
        val chair = EquipmentCatalogItemDto(
            id = "chair",
            version = 1,
            name = "Стул",
            category = "FURNITURE",
            active = true,
        )
        val editor = InventoryEditorState(
            findingId = "finding-1",
            number = "БЫТ-001",
            outcome = "FOUND",
            equipmentCatalog = listOf(chair),
            equipmentObservationRequested = true,
            equipmentQuantities = mapOf(chair.id to "0"),
        )

        assertThat(editor.hasObservedFurniture()).isFalse()
        assertThat(editor.copy(equipmentQuantities = mapOf(chair.id to "2")).hasObservedFurniture())
            .isTrue()
    }
}
