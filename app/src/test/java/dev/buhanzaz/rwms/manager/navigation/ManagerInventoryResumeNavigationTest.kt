package dev.buhanzaz.rwms.manager.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies reconstruction of the inventory back stack after Android process death. */
class ManagerInventoryResumeNavigationTest {
    @Test
    fun `confirmation recovery rebuilds every preceding inventory step`() {
        assertThat(managerInventoryResumeBackStack(ManagerRoute.InventoryConfirmation.route))
            .containsExactly(
                ManagerRoute.Inventory.route,
                ManagerRoute.InventoryEditor.route,
                ManagerRoute.InventoryPhotos.route,
                ManagerRoute.InventoryFurnitureDecision.route,
                ManagerRoute.InventoryFurniture.route,
                ManagerRoute.InventoryCatalog.route,
                ManagerRoute.InventoryInspectionDetails.route,
                ManagerRoute.InventoryConfirmation.route,
            ).inOrder()
    }

    @Test
    fun `unknown recovery target stops at editor`() {
        assertThat(managerInventoryResumeBackStack("manager-home"))
            .containsExactly(ManagerRoute.Inventory.route, ManagerRoute.InventoryEditor.route)
            .inOrder()
    }
}
