export type UserGlobalRole =
  | "SYSTEM_ADMIN"
  | "WMS_ADMIN"
  | "WAREHOUSE_MANAGER"
  | "RENTAL_MANAGER"
  | "VIEWER"

export type WarehouseAccessLevel = "VIEW" | "EDIT" | "MANAGE"

export type CurrentUserWarehouseAccess = {
  warehouseId: string
  level: WarehouseAccessLevel
}

export type CurrentUser = {
  id: string
  username: string
  displayName: string
  firstName: string | null
  lastName: string | null
  email: string | null
  principalType: "USER"
  globalRole: UserGlobalRole
  rentalAccess: boolean
  warehouseAccessAll: boolean
  warehouseAccesses: CurrentUserWarehouseAccess[]
}

export function isGlobalAdministrator(role: UserGlobalRole) {
  return role === "SYSTEM_ADMIN" || role === "WMS_ADMIN"
}
