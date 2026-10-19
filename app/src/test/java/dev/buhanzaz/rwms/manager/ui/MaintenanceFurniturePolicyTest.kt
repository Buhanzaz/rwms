package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.EquipmentContentDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import org.junit.Test

class MaintenanceFurniturePolicyTest {
    @Test
    fun `composition loads only active furniture and current cabin quantities`() {
        val chair = equipment(id = "chair", name = "Стул")
        val table = equipment(id = "table", name = "Стол")
        val catalog = listOf(
            chair,
            equipment(id = "inactive", name = "Старый шкаф", active = false),
            equipment(id = "lamp", name = "Лампа", category = "ELECTRICAL"),
            table,
        )
        val rentalItem = rentalItem(
            contents = listOf(
                EquipmentContentDto(chair.id, chair.name, 2, "CABIN_NON_RENTED"),
                EquipmentContentDto(chair.id, chair.name, 1, "CABIN_NON_RENTED"),
                EquipmentContentDto("inactive", "Старый шкаф", 4, "CABIN_NON_RENTED"),
            ),
        )

        val furniture = catalog.maintenanceFurnitureCatalog()
        val quantities = rentalItem.maintenanceFurnitureInitialQuantities(furniture)

        assertThat(furniture.map(EquipmentCatalogItemDto::id)).containsExactly("chair", "table")
        assertThat(quantities).containsExactly("chair", "3", "table", "0")
    }

    @Test
    fun `desired composition keeps positive selected rows and rejects non integers`() {
        val chair = equipment(id = "chair", name = "Стул")
        val table = equipment(id = "table", name = "Стол")
        val editor = MaintenanceFurnitureEditorState(
            mode = MaintenanceEditorMode.ESTIMATE,
            rentalItem = rentalItem(),
            furnitureCatalog = listOf(chair, table),
            quantities = mapOf(chair.id to "4", table.id to "0"),
        )

        assertThat(editor.maintenanceFurnitureCompositionValidationError()).isNull()
        assertThat(editor.maintenanceFurnitureDesiredContents())
            .containsExactly(
                dev.buhanzaz.rwms.manager.network.CabinFurnitureRequirementDto(chair.id, 4),
            )

        val invalid = editor.copy(quantities = editor.quantities + (chair.id to "четыре"))
        assertThat(invalid.maintenanceFurnitureCompositionValidationError())
            .isEqualTo("Количество для «Стул» должно быть целым числом")
    }

    @Test
    fun `zero-only composition continues without asking for a furniture disposition`() {
        val chair = equipment(id = "chair", name = "Стул")
        val editor = MaintenanceFurnitureEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            rentalItem = rentalItem(),
            furnitureCatalog = listOf(chair),
            quantities = mapOf(chair.id to "0"),
        )

        assertThat(editor.hasObservedFurniture()).isFalse()
        assertThat(editor.copy(quantities = mapOf(chair.id to "1")).hasObservedFurniture()).isTrue()
    }

    private fun equipment(
        id: String,
        name: String,
        category: String = "FURNITURE",
        active: Boolean = true,
    ) = EquipmentCatalogItemDto(
        id = id,
        version = 1,
        name = name,
        category = category,
        active = active,
    )

    private fun rentalItem(
        contents: List<EquipmentContentDto> = emptyList(),
    ) = RentalItemDto(
        id = "rental-item",
        version = 1,
        warehouseId = "warehouse",
        number = "БК-001",
        status = "REPAIR",
        contents = contents,
    )
}
