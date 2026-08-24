import { Route, Routes } from "react-router-dom"

import { AuthCallbackPage } from "@/features/auth/auth-callback-page"
import { AuthProvider } from "@/features/auth/auth-provider"
import { ProtectedApplication } from "@/features/auth/protected-application"
import { PublicClientPresentationPage } from "@/features/assistant/pages/public-client-presentation-page"
import { PublicCabinPhotoPresentationPage } from "@/features/rental-items/public-cabin-photo-presentation-page"

export function AuthRouter() {
  return (
    <Routes>
      <Route
        path="/offer/:token"
        element={<PublicClientPresentationPage />}
      />
      <Route
        path="/photos/:token"
        element={<PublicCabinPhotoPresentationPage />}
      />
      <Route
        path="*"
        element={
          <AuthProvider>
            <Routes>
              <Route
                path="/auth/callback"
                element={<AuthCallbackPage />}
              />
              <Route path="*" element={<ProtectedApplication />} />
            </Routes>
          </AuthProvider>
        }
      />
    </Routes>
  )
}
