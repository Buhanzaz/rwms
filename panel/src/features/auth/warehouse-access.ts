import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"

const accessRank: Record<WarehouseAccessLevel, number> = {
  VIEW: 1,
  EDIT: 2,
  MANAGE: 3,
}

export function getWarehouseAccessLevel(
  user: CurrentUser | null,
  warehouseId: string
): WarehouseAccessLevel | null {
  if (user === null) {
    return null
  }

  if (user.warehouseAccessAll) {
    return "MANAGE"
  }

  return (
    user.warehouseAccesses.find(
      (candidate) => candidate.warehouseId === warehouseId
    )?.level ?? null
  )
}

export function hasWarehouseAccess(
  user: CurrentUser | null,
  warehouseId: string,
  requiredLevel: WarehouseAccessLevel
) {
  const level = getWarehouseAccessLevel(user, warehouseId)

  return level !== null && accessRank[level] >= accessRank[requiredLevel]
}
