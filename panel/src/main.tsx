import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { BrowserRouter } from "react-router-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"

import "./index.css"
import { AuthRouter } from "@/features/auth/auth-router"
import { Toaster } from "@/components/ui/sonner"

// Public offer routes and bootstrap work keep this root client. AuthProvider
// installs a separate revision-scoped client around protected routes.
const publicQueryClient = new QueryClient()

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={publicQueryClient}>
      <BrowserRouter>
        <AuthRouter />
      </BrowserRouter>
      <Toaster position="top-right" richColors />
    </QueryClientProvider>
  </StrictMode>
)
