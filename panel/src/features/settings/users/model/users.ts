import type {
  UserGlobalRole,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"

export type AdminUserWarehouseAccess = {
  warehouseId: string
  accessLevel: WarehouseAccessLevel
  comment: string | null
  active: boolean
}

export type AdminUser = {
  id: string
  version: number
  username: string
  firstName: string | null
  lastName: string | null
  email: string | null
  timeZoneId: string | null
  active: boolean
  globalRole: UserGlobalRole
  warehouseAccesses: AdminUserWarehouseAccess[]
}

export type AdminUserProfileInput = {
  username: string
  firstName: string | null
  lastName: string | null
  email: string | null
  timeZoneId: string | null
  active: boolean
  globalRole: UserGlobalRole
}

export type CreateAdminUserInput = AdminUserProfileInput & {
  password: string
  warehouseAccesses: AdminUserWarehouseAccess[]
}

export type ReplaceWarehouseAccessesInput = {
  accesses: AdminUserWarehouseAccess[]
}

export const userGlobalRoleLabels: Record<UserGlobalRole, string> = {
  SYSTEM_ADMIN: "Системный администратор",
  WMS_ADMIN: "Администратор WMS",
  WAREHOUSE_MANAGER: "Руководитель склада",
  RENTAL_MANAGER: "Менеджер аренды",
  VIEWER: "Наблюдатель",
}

export const warehouseAccessLevelLabels: Record<WarehouseAccessLevel, string> =
  {
    VIEW: "Просмотр",
    EDIT: "Редактирование",
    MANAGE: "Управление",
  }

export function getAdminUserDisplayName(user: AdminUser) {
  const fullName = [user.firstName, user.lastName].filter(Boolean).join(" ")
  return fullName || user.username
}
