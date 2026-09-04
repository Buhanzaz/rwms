import { cleanup, render, screen } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"

const source: WarehouseInfo = {
  id: "11111111-1111-4111-8111-111111111111",
  version: 1,
  name: "MSK",
  city: "Москва",
  address: null,
  latitude: null,
  longitude: null,
  timeZone: "Europe/Moscow",
  active: true,
  lifecycleState: "ACTIVE",
  sortOrder: 1,
  representative: false,
  production: true,
  mainWarehouse: false,
  representativeParentWarehouseId: null,
}

const target: WarehouseInfo = {
  ...source,
  id: "22222222-2222-4222-8222-222222222222",
  name: "SPB",
  city: "Санкт-Петербург",
  production: false,
  mainWarehouse: true,
}

vi.mock("@/apps/admin/admin-object-scope", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("@/apps/admin/admin-object-scope")>()
  return {
    ...original,
    AdminObjectScope: ({
      children,
    }: {
      children: (
        warehouse: WarehouseInfo,
        selector: React.ReactNode
      ) => React.ReactNode
    }) =>
      children(
        source,
        <button type="button" role="combobox" aria-label="Объект">
          MSK
        </button>
      ),
  }
})

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({ warehouses: [source, target] }),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "admin-token" }),
}))

vi.mock("@/features/settings/fleet/admin-catalog-settings-page", () => ({
  AdminCatalogSettingsPage: ({ kind }: { kind: string }) => (
    <div>Каталог: {kind}</div>
  ),
}))

vi.mock("@/features/settings/logistics", () => ({
  LogisticsSettingsPage: ({ section }: { section: string }) => (
    <div>Логистика: {section}</div>
  ),
}))

vi.mock("@/features/settings/logistics/repair-capacity-settings-card", () => ({
  RepairCapacitySettingsCard: () => <div>Ремонтные места: сервер</div>,
}))

vi.mock(
  "@/features/settings/estimates-repairs/estimate-creation-window-settings-card",
  () => ({
    EstimateCreationWindowSettingsCard: ({
      warehouseId,
    }: {
      warehouseId: string
    }) => <div>Срок сметы: {warehouseId}</div>,
  })
)

vi.mock("@/features/settings/kpi/repair-complexity-settings-card", () => ({
  WarehouseRepairComplexitySettingsCard: ({
    warehouseId,
  }: {
    warehouseId: string
  }) => <div>Сложность ремонта: {warehouseId}</div>,
}))

vi.mock("@/features/settings/task-board/task-board-settings-page", () => ({
  TaskBoardSettingsPage: ({ section }: { section: string }) => (
    <div>Доска: {section}</div>
  ),
}))

import { ObjectSettingsPage } from "@/apps/admin/object-settings-page"

function renderSection(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/object-settings" element={<ObjectSettingsPage />} />
        <Route
          path="/admin/object-settings/:section"
          element={<ObjectSettingsPage />}
        />
      </Routes>
    </MemoryRouter>
  )
}

afterEach(cleanup)

describe("ObjectSettingsPage", () => {
  it.each([
    ["drivers", "Логистика: drivers"],
    ["transport", "Каталог: vehicle"],
    ["trailers", "Каталог: trailer"],
    ["brigades", "Доска: groups"],
    ["workers", "Доска: workers"],
    ["repair-places", "Ремонтные места: сервер"],
    ["maintenance", `Срок сметы: ${source.id}`],
  ])("routes %s to its moved object setting", (section, expected) => {
    renderSection(`/admin/object-settings/${section}`)

    expect(screen.getByText(expected)).toBeTruthy()
    const selector = screen.getByRole("combobox", { name: "Объект" })
    const drivers = screen.getByRole("tab", { name: "Водители" })
    expect(
      selector.compareDocumentPosition(drivers) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(screen.getByRole("tab", { name: "Транспорт" })).toBeTruthy()
    expect(screen.getByRole("tab", { name: "Прицепы" })).toBeTruthy()
    expect(screen.queryByRole("tab", { name: "Инвентаризация" })).toBeNull()
    expect(
      screen.queryByText(/Доступны только объекты с производством/i)
    ).toBeNull()
    expect(screen.queryByText("Основной склад")).toBeNull()

    if (section === "maintenance") {
      expect(
        screen.getByText(`Сложность ремонта: ${source.id}`)
      ).toBeTruthy()
    }
  })

  it("redirects the object-settings root to drivers", async () => {
    renderSection("/admin/object-settings")

    expect(await screen.findByText("Логистика: drivers")).toBeTruthy()
  })
})
