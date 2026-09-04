import type { WarehouseInfo } from "@/api/warehouse-api"

export function isConfigurableObject(warehouse: WarehouseInfo) {
  return (
    warehouse.lifecycleState !== "INACTIVE" &&
    !warehouse.representative &&
    (warehouse.production === true || warehouse.mainWarehouse === true)
  )
}
