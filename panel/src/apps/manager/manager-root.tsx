import { Route, Routes } from "react-router-dom"

import { ManagerApp } from "@/apps/manager/manager-app"
import { AuthCallbackPage } from "@/features/auth/auth-callback-page"
import { RENTAL_MANAGER_AUTH_CONFIG } from "@/features/auth/auth-config"
import { AuthProvider } from "@/features/auth/auth-provider"
import { AuthenticatedApplication } from "@/features/auth/authenticated-application"

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
              isAllowed={(user) => user.globalRole === "RENTAL_MANAGER"}
              accessDeniedMessage="Приложение менеджеров доступно только менеджерам аренды."
            >
              <ManagerApp />
            </AuthenticatedApplication>
          }
        />
      </Routes>
    </AuthProvider>
  )
}
