package dev.buhanzaz.rwms.manager.ui.components

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import org.junit.Test

class ManagerWarehouseSelectorPolicyTest {
    @Test
    fun `warehouse selector exposes every accessible warehouse by its server name`() {
        val selections = managerWarehouseSelections(
            warehouses = listOf(
                warehouse(
                    id = "0c1b9a1d-0aa6-4745-bb91-4a2fbd159a2c",
                    name = "СПБ",
                    city = "Санкт-Петербург",
                ),
                warehouse(
                    id = "4c9b663b-8fb3-4a9f-b484-0e1416aa4d97",
                    name = "МСК",
                    city = "Москва",
                ),
                warehouse(
                    id = "bb1538a5-333b-4aa2-8f3a-6a5e835b9fd6",
                    name = "СПБ2",
                    city = "Санкт-Петербург",
                ),
            ),
        )

        assertThat(selections).containsExactly(
            ManagerWarehouseSelection(
                warehouseId = "0c1b9a1d-0aa6-4745-bb91-4a2fbd159a2c",
                label = "СПБ",
            ),
            ManagerWarehouseSelection(
                warehouseId = "4c9b663b-8fb3-4a9f-b484-0e1416aa4d97",
                label = "МСК",
            ),
            ManagerWarehouseSelection(
                warehouseId = "bb1538a5-333b-4aa2-8f3a-6a5e835b9fd6",
                label = "СПБ2",
            ),
        ).inOrder()
    }

    @Test
    fun `warehouse menu exposes every other accessible warehouse and keeps its UUID`() {
        val warehouses = listOf(
            warehouse(id = "spb", name = "СПБ", city = "Санкт-Петербург"),
            warehouse(id = "msk", name = "МСК", city = "Москва"),
            warehouse(id = "spb2", name = "СПБ2", city = "Санкт-Петербург"),
        )

        assertThat(managerAlternativeWarehouseSelections(warehouses, selectedWarehouseId = "spb"))
            .containsExactly(
                ManagerWarehouseSelection(warehouseId = "msk", label = "МСК"),
                ManagerWarehouseSelection(warehouseId = "spb2", label = "СПБ2"),
            ).inOrder()
    }

    @Test
    fun `selected warehouse label uses its name with a city fallback`() {
        assertThat(
            managerHeaderWarehouseLabel(
                warehouse(id = "spb2", name = "СПБ2", city = "Санкт-Петербург"),
            ),
        ).isEqualTo("СПБ2")
        assertThat(managerHeaderWarehouseLabel(warehouse(id = "kzn", name = "", city = "Казань")))
            .isEqualTo("Казань")
        assertThat(managerHeaderWarehouseLabel(null)).isEqualTo("Склад")
    }

    private fun warehouse(id: String, name: String, city: String) = WarehouseDto(
        id = id,
        version = 1,
        name = name,
        city = city,
        timeZone = "Europe/Moscow",
        active = true,
    )
}
