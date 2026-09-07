import { Route, Routes } from "react-router-dom"

import { ManagerApp } from "@/apps/manager/manager-app"
import { AuthCallbackPage } from "@/features/auth/auth-callback-page"
import { RENTAL_MANAGER_AUTH_CONFIG } from "@/features/auth/auth-config"
import { AuthProvider } from "@/features/auth/auth-provider"
import { AuthenticatedApplication } from "@/features/auth/authenticated-application"
import type { CurrentUser } from "@/features/auth/auth-model"

const MANAGER_APPLICATION_BASE_PATH = "/manager"

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

export function ManagerRoot() {
  return (
    <AuthProvider runtime={RENTAL_MANAGER_AUTH_CONFIG}>
      <Routes>
        <Route
          path="/auth/callback"
          element={
            <AuthCallbackPage
              applicationBasePath={MANAGER_APPLICATION_BASE_PATH}
            />
          }
        />
        <Route
          path="*"
          element={
            <AuthenticatedApplication
              applicationBasePath={MANAGER_APPLICATION_BASE_PATH}
              isAllowed={canUseManagerApplication}
              accessDeniedMessage="Для приложения менеджеров должен быть включён доступ к аренде."
            >
              <ManagerApp />
            </AuthenticatedApplication>
          }
        />
      </Routes>
    </AuthProvider>
  )
}
