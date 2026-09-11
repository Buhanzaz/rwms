import { useLocation } from "react-router-dom"

import App from "@/App"
import { ProductionApp } from "@/apps/production/production-app"
import { AuthenticatedApplication } from "@/features/auth/authenticated-application"
import type { CurrentUser } from "@/features/auth/auth-model"

const PANEL_ROLES = new Set<CurrentUser["globalRole"]>([
  "SYSTEM_ADMIN",
  "WMS_ADMIN",
  "WAREHOUSE_MANAGER",
  "VIEWER",
])

export function ProtectedApplication() {
  const { pathname } = useLocation()

  return (
    <AuthenticatedApplication
      isAllowed={(user) => PANEL_ROLES.has(user.globalRole)}
      accessDeniedMessage="Эта учётная запись не имеет доступа к рабочей панели RWMS."
    >
      {pathname === "/production" || pathname.startsWith("/production/") ? (
        <ProductionApp />
      ) : (
        <App />
      )}
    </AuthenticatedApplication>
  )
}
