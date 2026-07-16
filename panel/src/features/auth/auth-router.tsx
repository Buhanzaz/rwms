import { Route, Routes } from "react-router-dom"

import { AuthCallbackPage } from "@/features/auth/auth-callback-page"
import { AuthProvider } from "@/features/auth/auth-provider"
import { ProtectedApplication } from "@/features/auth/protected-application"

export function AuthRouter() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="*" element={<ProtectedApplication />} />
      </Routes>
    </AuthProvider>
  )
}
