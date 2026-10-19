import { createContext } from "react"

import type { CurrentUser } from "@/features/auth/auth-model"

export type AuthStatus = "loading" | "authenticated" | "unauthenticated"

export type AuthContextValue = {
  status: AuthStatus
  accessToken: string | null
  currentUser: CurrentUser | null
  error: string | null
  beginLogin: (returnTo?: string) => Promise<void>
  completeLogin: () => Promise<string>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
