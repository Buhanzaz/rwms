import type { InventoryInspectionState } from "@/features/inventory/model/inventory-service"

export type InventoryPermission = "VIEW" | "EDIT" | "MANAGE"

export type InventoryActorSnapshot = {
  id: string
  displayName: string
  permissions: InventoryPermission[]
  /** null means global warehouse access. */
  authorizedWarehouseIds: string[] | null
}

/**
 * Transitional presentation type used by the dossier projection. Inventory
 * commands and reads use the canonical service model in inventory-service.ts.
 */
export type InventoryInspectionStatus = InventoryInspectionState
