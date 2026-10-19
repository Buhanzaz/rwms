import { render, screen } from "@testing-library/react"
import type { ReactNode } from "react"
import { MemoryRouter } from "react-router-dom"
import { beforeEach, describe, expect, it, vi } from "vitest"

const mocks = vi.hoisted(() => ({
  app: vi.fn(),
  authenticatedApplication: vi.fn(),
  productionApp: vi.fn(),
}))

vi.mock("@/App", () => ({
  default: () => {
    mocks.app()
    return <div>Основная панель</div>
  },
}))

vi.mock("@/apps/production/production-app", () => ({
  ProductionApp: () => {
    mocks.productionApp()
    return <div>Производство</div>
  },
}))

vi.mock("@/features/auth/authenticated-application", () => ({
  AuthenticatedApplication: ({ children }: { children: ReactNode }) => {
    mocks.authenticatedApplication()
    return <>{children}</>
  },
}))

import { ProtectedApplication } from "@/features/auth/protected-application"

describe("ProtectedApplication production routing", () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it("selects ProductionApp for every production path", () => {
    render(
      <MemoryRouter initialEntries={["/production/task-board"]}>
        <ProtectedApplication />
      </MemoryRouter>
    )

    expect(screen.getByText("Производство")).toBeTruthy()
    expect(mocks.productionApp).toHaveBeenCalledTimes(1)
    expect(mocks.app).not.toHaveBeenCalled()
    expect(mocks.authenticatedApplication).toHaveBeenCalledTimes(1)
  })

  it("keeps the existing App for non-production paths", () => {
    render(
      <MemoryRouter initialEntries={["/warehouse"]}>
        <ProtectedApplication />
      </MemoryRouter>
    )

    expect(screen.getByText("Основная панель")).toBeTruthy()
    expect(mocks.app).toHaveBeenCalledTimes(1)
    expect(mocks.productionApp).not.toHaveBeenCalled()
  })
})
