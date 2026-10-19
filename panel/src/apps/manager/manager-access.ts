import type { CurrentUser } from "@/features/auth/auth-model"

const RENTAL_STAFF_ROLES = new Set<CurrentUser["globalRole"]>([
  "SYSTEM_ADMIN",
  "WMS_ADMIN",
  "WAREHOUSE_MANAGER",
  "RENTAL_MANAGER",
  "VIEWER",
])

export function canUseManagerApplication(user: CurrentUser) {
  return user.rentalAccess && RENTAL_STAFF_ROLES.has(user.globalRole)
}
