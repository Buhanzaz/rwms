import { Route, Routes } from "react-router-dom"

import { AdminApp } from "@/apps/admin/admin-app"
import { AuthCallbackPage } from "@/features/auth/auth-callback-page"
import { AuthProvider } from "@/features/auth/auth-provider"
import { AuthenticatedApplication } from "@/features/auth/authenticated-application"
import { ADMIN_AUTH_CONFIG } from "@/features/auth/auth-config"

export function AdminRoot() {
  return (
    <AuthProvider runtime={ADMIN_AUTH_CONFIG}>
      <Routes>
        <Route
          path={ADMIN_AUTH_CONFIG.callbackPath}
          element={<AuthCallbackPage />}
        />
        <Route
          path="*"
          element={
            <AuthenticatedApplication
              isAllowed={(user) => user.globalRole === "SYSTEM_ADMIN"}
              accessDeniedMessage="Административная панель доступна только системному администратору."
            >
              <AdminApp />
            </AuthenticatedApplication>
          }
        />
      </Routes>
    </AuthProvider>
  )
}
