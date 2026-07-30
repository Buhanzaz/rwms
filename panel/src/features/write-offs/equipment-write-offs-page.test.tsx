import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import { EquipmentWriteOffsPage } from "@/features/write-offs/equipment-write-offs-page"
import type { EquipmentDispositionListItemDto } from "@/types/equipment"

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
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

function disposition(
  id: string,
  overrides: Partial<EquipmentDispositionListItemDto> = {}
): EquipmentDispositionListItemDto {
  return {
    id,
    version: 1,
    equipmentId: `equipment-${id}`,
    sourceBalanceId: `source-${id}`,
    targetBalanceId: `target-${id}`,
    quantity: 2,
    kind: "EQUIPMENT_WRITTEN_OFF",
    occurredAt: "2026-07-20T10:00:00Z",
    equipmentName: "Стул",
    ...overrides,
  }
}

function renderPage(
  level: "VIEW" | "MANAGE",
  items: EquipmentDispositionListItemDto[] = []
) {
  mocks.useAuth.mockReturnValue({
    accessToken: "asset-token",
    currentUser: user(level),
  })
  mocks.useWarehouse.mockReturnValue({
    selectedWarehouseId: WAREHOUSE_ID,
  })
  mocks.listEquipmentDispositionItems.mockResolvedValue(items)

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
  it("shows and collapses name, operation and date filters", async () => {
    const user = userEvent.setup()
    renderPage("VIEW", [
      disposition("1"),
      disposition("2", {
        equipmentName: "Стол",
        kind: "EQUIPMENT_LOST",
        occurredAt: "2026-07-21T10:00:00Z",
      }),
    ])

    await screen.findAllByText("Списание")
    const search = screen.getByRole("searchbox", {
      name: "Поиск операций оборудования",
    })
    expect(
      search.closest('[data-slot="page-toolbar-content"]')?.className
    ).toContain("max-w-xl")
    const filtersPanel = document.getElementById("equipment-write-off-filters")
    expect(filtersPanel).not.toBeNull()
    const filters = within(filtersPanel!)
    expect(filters.getByRole("button", { name: "Название" })).toBeTruthy()
    expect(filters.getByRole("button", { name: "Операция" })).toBeTruthy()
    expect(filters.getByLabelText("Дата с")).toBeTruthy()
    expect(filters.getByLabelText("Дата по")).toBeTruthy()

    const toggle = screen.getByRole("button", { name: "Скрыть фильтры" })
    expect(toggle.getAttribute("aria-controls")).toBe(
      "equipment-write-off-filters"
    )
    await user.click(toggle)
    expect(filtersPanel?.hidden).toBe(true)
    expect(
      screen.getByRole("button", { name: "Показать фильтры" })
    ).toBeTruthy()
  })

  it("keeps a full-height grid with headers when no equipment operations exist", async () => {
    const { container } = renderPage("VIEW")

    expect(
      await screen.findByRole("button", { name: "Наименование" })
    ).toBeTruthy()
    const grid = container.querySelector<HTMLElement>(
      '[data-slot="operations-list-grid"]'
    )
    expect(grid).not.toBeNull()
    expect(grid?.className).toContain("min-h-full")
    expect(grid?.parentElement?.className).toContain("flex-1")
    expect(grid?.parentElement?.className).not.toContain("hidden")
    expect(screen.queryByText("Операций оборудования пока нет.")).toBeNull()
  })

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
