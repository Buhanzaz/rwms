import { Route, Routes } from "react-router-dom"

import { ManagerApp } from "@/apps/manager/manager-app"
import { AuthCallbackPage } from "@/features/auth/auth-callback-page"
import { RENTAL_MANAGER_AUTH_CONFIG } from "@/features/auth/auth-config"
import { AuthProvider } from "@/features/auth/auth-provider"
import { AuthenticatedApplication } from "@/features/auth/authenticated-application"
import { canUseManagerApplication } from "@/apps/manager/manager-access"

const MANAGER_APPLICATION_BASE_PATH = "/manager"

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
