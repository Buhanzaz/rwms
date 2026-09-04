import App from "@/App"
import { AuthenticatedApplication } from "@/features/auth/authenticated-application"
import type { CurrentUser } from "@/features/auth/auth-model"

const PANEL_ROLES = new Set<CurrentUser["globalRole"]>([
  "SYSTEM_ADMIN",
  "WMS_ADMIN",
  "WAREHOUSE_MANAGER",
  "VIEWER",
])

export function ProtectedApplication() {
  return (
    <AuthenticatedApplication
      isAllowed={(user) => PANEL_ROLES.has(user.globalRole)}
      accessDeniedMessage="Эта учётная запись не имеет доступа к рабочей панели RWMS."
    >
      <App />
    </AuthenticatedApplication>
  )
}
