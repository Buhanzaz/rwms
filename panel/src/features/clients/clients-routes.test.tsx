import { cleanup, render, screen } from "@testing-library/react"
import type { PropsWithChildren } from "react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/features/orders/orders-routes", () => ({
  VaultPanelOrdersModuleAdapter: ({ children }: PropsWithChildren) => (
    <>{children}</>
  ),
}))
vi.mock("@/features/clients/pages/clients-list-page", () => ({
  ClientsListPage: () => <div>clients-list-route</div>,
}))
vi.mock("@/features/clients/pages/client-create-page", () => ({
  ClientCreatePage: () => <div>client-create-route</div>,
}))
vi.mock("@/features/clients/pages/client-detail-page", () => ({
  ClientDetailPage: () => <div>client-detail-route</div>,
}))

import { ClientsRoutes } from "@/features/clients/clients-routes"

function renderRoute(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/clients/*" element={<ClientsRoutes />} />
      </Routes>
    </MemoryRouter>
  )
}

afterEach(cleanup)

describe("ClientsRoutes", () => {
  it.each([
    ["/clients", "clients-list-route"],
    ["/clients/new", "client-create-route"],
    ["/clients/11111111-1111-4111-8111-111111111111", "client-detail-route"],
  ])("maps %s to the current client screen", (path, marker) => {
    renderRoute(path)
    expect(screen.getByText(marker)).toBeTruthy()
  })
})
