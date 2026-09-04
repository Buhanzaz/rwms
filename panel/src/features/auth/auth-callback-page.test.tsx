import { StrictMode } from "react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { render, waitFor } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import {
  navigateAfterLogin,
  toApplicationRouterPath,
  toExternalApplicationPath,
} from "@/features/auth/auth-callback-navigation"
import { AuthCallbackPage } from "@/features/auth/auth-callback-page"
import {
  AuthContext,
  type AuthContextValue,
} from "@/features/auth/auth-context"

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

  it("performs a full-page return to the standalone logistics workspace", () => {
    const navigate = vi.fn()
    const assign = vi.fn()

    navigateAfterLogin("/logistics-panel/?warehouse=spb", navigate, assign)

    expect(assign).toHaveBeenCalledWith("/logistics-panel/?warehouse=spb")
    expect(navigate).not.toHaveBeenCalled()
  })

  it("keeps ordinary panel destinations inside the panel router", () => {
    const navigate = vi.fn()
    const assign = vi.fn()

    navigateAfterLogin("/logistics/transfers", navigate, assign)

    expect(navigate).toHaveBeenCalledWith("/logistics/transfers", {
      replace: true,
    })
    expect(assign).not.toHaveBeenCalled()
  })

  it("maps between a basename router location and its external manager URL", () => {
    expect(toExternalApplicationPath("/orders?status=draft", "/manager")).toBe(
      "/manager/orders?status=draft"
    )
    expect(
      toApplicationRouterPath("/manager/orders?status=draft", "/manager")
    ).toBe("/orders?status=draft")
    expect(toApplicationRouterPath("/orders", "/manager")).toBe("/")
  })
})
