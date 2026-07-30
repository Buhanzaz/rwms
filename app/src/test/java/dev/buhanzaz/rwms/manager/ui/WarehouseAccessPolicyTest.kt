package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.WarehouseAccessDto
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import org.junit.Test

class WarehouseAccessPolicyTest {
    @Test
    fun `global administrator sees every active service warehouse`() {
        val visible = WarehouseAccessPolicy.visibleWarehouses(
            user(accessAll = true, warehouseIds = emptyList()),
            listOf(warehouse("one"), warehouse("two")),
        )

        assertThat(visible.map { it.id }).containsExactly("one", "two").inOrder()
    }

    @Test
    fun `warehouse manager sees only assigned service warehouses`() {
        val visible = WarehouseAccessPolicy.visibleWarehouses(
            user(accessAll = false, warehouseIds = listOf("two")),
            listOf(warehouse("one"), warehouse("two")),
        )

        assertThat(visible.map { it.id }).containsExactly("two")
    }

    @Test
    fun `warehouse access levels are ordered and fail closed`() {
        val user = user(accessAll = false, warehouseIds = emptyList()).copy(
            warehouseAccesses = listOf(
                WarehouseAccessDto("view", "VIEW"),
                WarehouseAccessDto("edit", "EDIT"),
                WarehouseAccessDto("manage", "MANAGE"),
            ),
        )

        assertThat(WarehouseAccessPolicy.hasAccess(user, "view", "VIEW")).isTrue()
        assertThat(WarehouseAccessPolicy.hasAccess(user, "view", "EDIT")).isFalse()
        assertThat(WarehouseAccessPolicy.hasAccess(user, "edit", "VIEW")).isTrue()
        assertThat(WarehouseAccessPolicy.hasAccess(user, "edit", "MANAGE")).isFalse()
        assertThat(WarehouseAccessPolicy.hasAccess(user, "manage", "MANAGE")).isTrue()
        assertThat(WarehouseAccessPolicy.hasAccess(user, "missing", "VIEW")).isFalse()
    }

    private fun user(
        accessAll: Boolean,
        warehouseIds: List<String>,
    ) = CurrentUserDto(
        id = "user",
        username = "manager",
        displayName = "Manager",
        principalType = "USER",
        globalRole = "WAREHOUSE_MANAGER",
        rentalAccess = false,
        warehouseAccessAll = accessAll,
        warehouseAccesses = warehouseIds.map { WarehouseAccessDto(it, "EDIT") },
    )

    private fun warehouse(id: String) = WarehouseDto(
        id = id,
        version = 1,
        name = id,
        city = "City",
        timeZone = "Europe/Moscow",
        active = true,
    )
}
