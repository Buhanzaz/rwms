import { StrictMode, useState } from "react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
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
  it("shows progress and the fresh retry error instead of keeping the old callback message", async () => {
    const completeLogin = vi
      .fn()
      .mockRejectedValue(new Error("Старая ошибка callback"))
    let finish: () => void = () => undefined
    function RetryHarness() {
      const [error, setError] = useState<string | null>(null)
      return (
        <MemoryRouter>
          <AuthContext.Provider
            value={{
              status: "unauthenticated",
              accessToken: null,
              currentUser: null,
              error,
              completeLogin,
              logout: vi.fn(),
              beginLogin: async () => {
                await new Promise<void>((resolve) => {
                  finish = resolve
                })
                setError("Сервис входа временно недоступен")
              },
            }}
          >
            <AuthCallbackPage />
          </AuthContext.Provider>
        </MemoryRouter>
      )
    }
    render(<RetryHarness />)
    fireEvent.click(await screen.findByRole("button", { name: "Войти снова" }))
    expect(
      screen
        .getByRole("button", { name: "Подключаемся…" })
        .hasAttribute("disabled")
    ).toBe(true)

    await act(async () => finish())

    expect((await screen.findByRole("alert")).textContent).toBe(
      "Сервис входа временно недоступен"
    )
    expect(screen.queryByText("Старая ошибка callback")).toBeNull()
    expect(
      screen
        .getByRole("button", { name: "Войти снова" })
        .hasAttribute("disabled")
    ).toBe(false)
  })

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
