import { cleanup, render, screen } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/features/auth/auth-provider", () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
}))
vi.mock("@/features/auth/protected-application", () => ({
  ProtectedApplication: () => <div>Защищённое приложение</div>,
}))
vi.mock("@/features/auth/auth-callback-page", () => ({
  AuthCallbackPage: () => <div>OAuth callback</div>,
}))
vi.mock("@/features/assistant/pages/public-client-presentation-page", () => ({
  PublicClientPresentationPage: () => <div>Публичное предложение</div>,
}))
vi.mock("@/features/rental-items/public-cabin-photo-presentation-page", () => ({
  PublicCabinPhotoPresentationPage: () => <div>Публичные фотографии</div>,
}))
vi.mock(
  "@/features/logistics/contractor-route-share/public-contractor-route-page",
  () => ({ PublicContractorRoutePage: () => <div>Маршрут подрядчика</div> })
)

import { AuthRouter } from "@/features/auth/auth-router"

afterEach(cleanup)

describe("contractor route public navigation", () => {
  it("opens the scoped route outside the authenticated application", () => {
    render(
      <MemoryRouter initialEntries={["/contractor-routes/signed-token"]}>
        <AuthRouter />
      </MemoryRouter>
    )

    expect(screen.getByText("Маршрут подрядчика")).toBeTruthy()
    expect(screen.queryByText("Защищённое приложение")).toBeNull()
  })
})
