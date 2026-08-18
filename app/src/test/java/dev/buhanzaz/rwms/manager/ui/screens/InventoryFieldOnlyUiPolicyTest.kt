package dev.buhanzaz.rwms.manager.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InventoryFieldOnlyUiPolicyTest {
    @Test
    fun `empty inventory directs the manager to the panel-owned session start`() {
        assertThat(INVENTORY_FIELD_ONLY_EMPTY_STATE_DESCRIPTION).contains("в панели")
    }

    @Test
    fun `after rent finding explains estimate reconciliation without a repair task`() {
        assertThat(inventoryAfterRentEstimateNotice("AFTER_RENT"))
            .isEqualTo(INVENTORY_AFTER_RENT_ESTIMATE_NOTICE)
        assertThat(INVENTORY_AFTER_RENT_ESTIMATE_NOTICE).contains("черновиком сметы")
        assertThat(INVENTORY_AFTER_RENT_ESTIMATE_NOTICE).contains("не создаётся")
        assertThat(inventoryAfterRentEstimateNotice("REPAIR")).isNull()
        assertThat(inventoryAfterRentEstimateNotice(null)).isNull()
    }
}
