import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import {
  getWarehouseAccessLevel,
  hasWarehouseAccess,
} from "@/features/auth/warehouse-access"
import type {
  InventoryActorSnapshot,
  InventoryPermission,
} from "@/features/inventory/model/inventory"

const permissionsByAccess: Record<WarehouseAccessLevel, InventoryPermission[]> =
  {
    VIEW: ["VIEW"],
    EDIT: ["VIEW", "EDIT"],
    MANAGE: ["VIEW", "EDIT", "MANAGE"],
  }

export function hasInventoryWarehouseAccess(
  user: CurrentUser | null,
  warehouseId: string,
  requiredLevel: WarehouseAccessLevel
) {
  return hasWarehouseAccess(user, warehouseId, requiredLevel)
}

export function getInventoryActor(
  user: CurrentUser | null,
  warehouseId: string
): InventoryActorSnapshot | null {
  if (user === null) {
    return null
  }

  const access = getWarehouseAccessLevel(user, warehouseId)

  if (!access) {
    return null
  }

  return {
    id: user.id,
    displayName: user.displayName || user.username,
    permissions: [...permissionsByAccess[access]],
    authorizedWarehouseIds: user.warehouseAccessAll
      ? null
      : user.warehouseAccesses.map((candidate) => candidate.warehouseId),
  }
}
