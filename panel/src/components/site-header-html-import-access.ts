import type { CurrentUser } from "@/features/auth/auth-model"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"

export function canShowWarehouseHtmlImport(
  pathname: string,
  currentUser: CurrentUser | null,
  warehouseId: string | null
) {
  return (
    pathname === "/warehouse" &&
    warehouseId !== null &&
    hasWarehouseAccess(currentUser, warehouseId, "EDIT")
  )
}
