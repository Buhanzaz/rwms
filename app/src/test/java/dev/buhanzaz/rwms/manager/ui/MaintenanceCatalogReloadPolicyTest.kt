package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MaintenanceCatalogReloadPolicyTest {
    @Test
    fun `empty cache is reloaded even for the same warehouse`() {
        assertThat(
            shouldReloadMaintenanceCatalog(
                requestedWarehouseId = "warehouse-1",
                cachedWarehouseId = "warehouse-1",
                cachedRevision = null,
                cachedNodeCount = 0,
            ),
        ).isTrue()
    }

    @Test
    fun `complete cache is reused only for its warehouse`() {
        val revision = ActiveMaintenanceCatalogRevision("catalog-1", 17)

        assertThat(
            shouldReloadMaintenanceCatalog(
                requestedWarehouseId = "warehouse-1",
                cachedWarehouseId = "warehouse-1",
                cachedRevision = revision,
                cachedNodeCount = 232,
            ),
        ).isFalse()
        assertThat(
            shouldReloadMaintenanceCatalog(
                requestedWarehouseId = "warehouse-2",
                cachedWarehouseId = "warehouse-1",
                cachedRevision = revision,
                cachedNodeCount = 232,
            ),
        ).isTrue()
    }
}
