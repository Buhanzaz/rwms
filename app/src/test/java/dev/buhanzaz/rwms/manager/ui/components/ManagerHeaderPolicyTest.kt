package dev.buhanzaz.rwms.manager.ui.components

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import org.junit.Test

class ManagerHeaderPolicyTest {
    @Test
    fun `back affordance uses an arrow without a visible text label`() {
        assertThat(MANAGER_BACK_GLYPH).isEqualTo("←")
        assertThat(MANAGER_BACK_GLYPH).doesNotContain(MANAGER_BACK_CONTENT_DESCRIPTION)
    }

    @Test
    fun `warehouse selector label uses name and city`() {
        assertThat(warehouse(name = "Склад Север", city = "Санкт-Петербург").label)
            .isEqualTo("Склад Север · Санкт-Петербург")
    }

    @Test
    fun `warehouse selector label omits a duplicated city`() {
        assertThat(warehouse(name = "Казань", city = "Казань").label)
            .isEqualTo("Казань")
    }

    private fun warehouse(name: String, city: String) = WarehouseDto(
        id = "warehouse-1",
        version = 1,
        name = name,
        city = city,
        timeZone = "Europe/Moscow",
        active = true,
    )
}
