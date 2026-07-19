import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import { EquipmentWriteOffsPage } from "@/features/write-offs/equipment-write-offs-page"

const mocks = vi.hoisted(() => ({
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
  listEquipmentDispositionItems: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: mocks.useAuth }))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: mocks.useWarehouse,
}))
vi.mock("@/api/equipment-api", async () => {
  const actual = await vi.importActual<typeof import("@/api/equipment-api")>(
    "@/api/equipment-api"
  )

  return {
    ...actual,
    listEquipmentDispositionItems: mocks.listEquipmentDispositionItems,
  }
})

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"

function user(level: "VIEW" | "MANAGE"): CurrentUser {
  return {
    id: "user-1",
    username: "operator",
    displayName: "Operator",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

function renderPage(level: "VIEW" | "MANAGE") {
  mocks.useAuth.mockReturnValue({
    accessToken: "asset-token",
    currentUser: user(level),
  })
  mocks.useWarehouse.mockReturnValue({
    selectedWarehouseId: WAREHOUSE_ID,
  })
  mocks.listEquipmentDispositionItems.mockResolvedValue([])

  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false } },
        })
      }
    >
      <EquipmentWriteOffsPage />
    </QueryClientProvider>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("equipment disposition warehouse access", () => {
  it("keeps VIEW users read-only", () => {
    renderPage("VIEW")

    const button = screen.getByRole("button", {
      name: "Операция с оборудованием",
    }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
  })

  it("enables the operation for MANAGE users", () => {
    renderPage("MANAGE")

    const button = screen.getByRole("button", {
      name: "Операция с оборудованием",
    }) as HTMLButtonElement
    expect(button.disabled).toBe(false)
  })
})
