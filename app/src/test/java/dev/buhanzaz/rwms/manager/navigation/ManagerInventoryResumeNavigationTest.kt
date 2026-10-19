package dev.buhanzaz.rwms.manager.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies reconstruction of the inventory back stack after Android process death. */
class ManagerInventoryResumeNavigationTest {
    @Test
    fun `confirmation recovery includes furniture when it was requested`() {
        assertThat(
            managerInventoryResumeBackStack(
                targetRoute = ManagerRoute.InventoryConfirmation.route,
                equipmentObservationRequested = true,
            ),
        )
            .containsExactly(
                ManagerRoute.Inventory.route,
                ManagerRoute.InventoryEditor.route,
                ManagerRoute.InventoryPhotos.route,
                ManagerRoute.InventoryFurniture.route,
                ManagerRoute.InventoryCatalog.route,
                ManagerRoute.InventoryInspectionDetails.route,
                ManagerRoute.InventoryConfirmation.route,
            ).inOrder()
    }

    @Test
    fun `confirmation recovery skips furniture when it was absent`() {
        assertThat(
            managerInventoryResumeBackStack(
                targetRoute = ManagerRoute.InventoryConfirmation.route,
                equipmentObservationRequested = false,
            ),
        )
            .containsExactly(
                ManagerRoute.Inventory.route,
                ManagerRoute.InventoryEditor.route,
                ManagerRoute.InventoryPhotos.route,
                ManagerRoute.InventoryCatalog.route,
                ManagerRoute.InventoryInspectionDetails.route,
                ManagerRoute.InventoryConfirmation.route,
            ).inOrder()
    }

    @Test
    fun `confirmation recovery skips furniture when it was not chosen`() {
        assertThat(
            managerInventoryResumeBackStack(
                targetRoute = ManagerRoute.InventoryConfirmation.route,
                equipmentObservationRequested = null,
            ),
        )
            .containsExactly(
                ManagerRoute.Inventory.route,
                ManagerRoute.InventoryEditor.route,
                ManagerRoute.InventoryPhotos.route,
                ManagerRoute.InventoryCatalog.route,
                ManagerRoute.InventoryInspectionDetails.route,
                ManagerRoute.InventoryConfirmation.route,
            ).inOrder()
    }

    @Test
    fun `furniture target remains recoverable when it was later marked absent`() {
        assertThat(
            managerInventoryResumeBackStack(
                targetRoute = ManagerRoute.InventoryFurniture.route,
                equipmentObservationRequested = false,
            ),
        )
            .containsExactly(
                ManagerRoute.Inventory.route,
                ManagerRoute.InventoryEditor.route,
                ManagerRoute.InventoryPhotos.route,
                ManagerRoute.InventoryFurniture.route,
            ).inOrder()
    }

    @Test
    fun `unknown recovery target stops at editor`() {
        assertThat(
            managerInventoryResumeBackStack(
                targetRoute = "manager-home",
                equipmentObservationRequested = null,
            ),
        )
            .containsExactly(ManagerRoute.Inventory.route, ManagerRoute.InventoryEditor.route)
            .inOrder()
    }
}
