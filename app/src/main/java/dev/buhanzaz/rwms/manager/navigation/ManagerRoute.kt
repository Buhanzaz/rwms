package dev.buhanzaz.rwms.manager.navigation

import android.net.Uri

sealed class ManagerRoute(val route: String) {
    data object Home : ManagerRoute("manager-home")
    data object Logistics : ManagerRoute("manager-logistics")
    data object Returns : ManagerRoute("manager-returns")
    data object ReturnInspection : ManagerRoute("manager-return-inspection")
    data object Shipments : ManagerRoute("manager-shipments")
    data object ShipmentDetail : ManagerRoute("manager-shipment-detail")
    data object Transfers : ManagerRoute("manager-transfers")
    data object TransferCreate : ManagerRoute("manager-transfer-create")
    data object TransferDetail : ManagerRoute("manager-transfer-detail")
    data object Inventory : ManagerRoute("manager-inventory")
    data object InventoryEditor : ManagerRoute("manager-inventory-editor")
    data object InventoryPhotos : ManagerRoute("manager-inventory-photos")
    data object InventoryFurnitureDecision : ManagerRoute("manager-inventory-furniture-decision")
    data object InventoryFurniture : ManagerRoute("manager-inventory-furniture")
    data object InventoryCatalog : ManagerRoute("manager-inventory-catalog")
    data object InventoryConfirmation : ManagerRoute("manager-inventory-confirmation")
    data object Maintenance : ManagerRoute("manager-maintenance")
    data object Estimates : ManagerRoute("manager-estimates")
    data object Repairs : ManagerRoute("manager-repairs")
    data object RepairQueue : ManagerRoute("manager-repair-queue")
    data object Acceptance : ManagerRoute("manager-maintenance-acceptance")
    data object MaintenanceEditor : ManagerRoute("manager-maintenance-editor")
    data object MaintenanceFurniture : ManagerRoute("manager-maintenance-furniture")
    data object PhotoCapture : ManagerRoute(
        "manager-photo-capture?target={target}&lineId={lineId}",
    ) {
        const val TARGET_INVENTORY = "inventory"
        const val TARGET_RETURN = "return"
        const val TARGET_MAINTENANCE = "maintenance"
        const val TARGET_ACCEPTANCE = "acceptance"
        const val TARGET_TRANSFER = "transfer"

        fun inventoryRoute(): String = "manager-photo-capture?target=$TARGET_INVENTORY&lineId="

        fun returnRoute(lineId: String): String =
            "manager-photo-capture?target=$TARGET_RETURN&lineId=${Uri.encode(lineId)}"

        fun maintenanceRoute(): String =
            "manager-photo-capture?target=$TARGET_MAINTENANCE&lineId="

        fun acceptanceRoute(): String =
            "manager-photo-capture?target=$TARGET_ACCEPTANCE&lineId="

        fun transferRoute(lineId: String): String =
            "manager-photo-capture?target=$TARGET_TRANSFER&lineId=${Uri.encode(lineId)}"
    }
}
