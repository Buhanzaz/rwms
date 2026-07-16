import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import type {
  InventoryActorSnapshot,
  InventoryPermission,
} from "@/features/inventory/model/inventory"

const accessRank: Record<WarehouseAccessLevel, number> = {
  VIEW: 1,
  EDIT: 2,
  MANAGE: 3,
}

export function hasInventoryWarehouseAccess(
  user: CurrentUser | null,
  warehouseId: string,
  requiredLevel: WarehouseAccessLevel
) {
  if (user === null) {
    return false
  }

  if (user.warehouseAccessAll) {
    return true
  }

  const access = user.warehouseAccesses.find(
    (candidate) => candidate.warehouseId === warehouseId
  )

  return (
    access !== undefined &&
    accessRank[access.level] >= accessRank[requiredLevel]
  )
}

export function getInventoryActor(
  user: CurrentUser | null,
  warehouseId: string
): InventoryActorSnapshot | null {
  if (user === null) {
    return null
  }

  const access = user.warehouseAccessAll
    ? "MANAGE"
    : user.warehouseAccesses.find(
        (candidate) => candidate.warehouseId === warehouseId
      )?.level

  if (!access) {
    return null
  }

  const permissions = (
    ["VIEW", "EDIT", "MANAGE"] as InventoryPermission[]
  ).filter((permission) => accessRank[permission] <= accessRank[access])

  return {
    id: user.id,
    displayName: user.displayName || user.username,
    permissions,
    authorizedWarehouseIds: user.warehouseAccessAll
      ? null
      : user.warehouseAccesses.map((candidate) => candidate.warehouseId),
  }
}
