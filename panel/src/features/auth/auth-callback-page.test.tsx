import { StrictMode } from "react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { render, waitFor } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { AuthCallbackPage } from "@/features/auth/auth-callback-page"
import { AuthContext, type AuthContextValue } from "@/features/auth/auth-context"

function renderCallback(value: AuthContextValue) {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={["/auth/callback?code=code&state=state"]}>
        <AuthContext.Provider value={value}>
          <Routes>
            <Route path="/auth/callback" element={<AuthCallbackPage />} />
            <Route path="/" element={<p>Главная</p>} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>
    </StrictMode>
  )
}

describe("AuthCallbackPage", () => {
  it("consumes the one-time OIDC callback only once under StrictMode", async () => {
    const completeLogin = vi.fn().mockResolvedValue("/")

    renderCallback({
      status: "loading",
      accessToken: null,
      currentUser: null,
      error: null,
      beginLogin: vi.fn(),
      completeLogin,
      logout: vi.fn(),
    })

    await waitFor(() => expect(completeLogin).toHaveBeenCalledTimes(1))
  })
})
