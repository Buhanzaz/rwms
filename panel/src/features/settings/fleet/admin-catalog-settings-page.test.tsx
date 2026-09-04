import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { ApiError } from "@/lib/api-client"

const catalogApi = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "admin-token" }),
}))

vi.mock(
  "@/features/settings/fleet/api/admin-catalog-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/fleet/api/admin-catalog-api")
      >()
    return {
      ...original,
      listAdminCatalogResources: catalogApi.list,
      createAdminCatalogResource: catalogApi.create,
      updateAdminCatalogResource: catalogApi.update,
      deleteAdminCatalogResource: catalogApi.remove,
    }
  }
)

vi.mock("sonner", () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}))

import { AdminCatalogSettingsPage } from "@/features/settings/fleet/admin-catalog-settings-page"

const SOURCE_ID = "11111111-1111-4111-8111-111111111111"
const VEHICLE_ID = "33333333-3333-4333-8333-333333333333"

function warehouse(id: string, name: string, city: string): WarehouseInfo {
  return {
    id,
    version: 1,
    name,
    city,
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
}

const source = warehouse(SOURCE_ID, "MSK", "Москва")
const vehicle = {
  id: VEHICLE_ID,
  version: 4,
  warehouseId: SOURCE_ID,
  name: "КамАЗ",
  registrationNumber: "А123ВС77",
  active: true,
  notes: "Манипулятор",
  capacity: 2,
}

const pointerCaptureDescriptors = new Map(
  [
    "hasPointerCapture",
    "setPointerCapture",
    "releasePointerCapture",
    "scrollIntoView",
  ].map((name) => [
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ])
)

beforeAll(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
  })
})

afterAll(() => {
  vi.unstubAllGlobals()
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <AdminCatalogSettingsPage kind="vehicle" warehouse={source} />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  catalogApi.list.mockResolvedValue([vehicle])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("AdminCatalogSettingsPage", () => {
  it("shows the fleet catalog as an operational grid without relocation", async () => {
    renderPage()

    expect(await screen.findByText("КамАЗ")).toBeTruthy()
    expect(screen.getByRole("columnheader", { name: "Транспорт" })).toBeTruthy()
    expect(screen.getByRole("columnheader", { name: "Активен" })).toBeTruthy()
    expect(
      screen.getByRole("columnheader", { name: "Комментарий" })
    ).toBeTruthy()
    expect(screen.queryByText("v4")).toBeNull()
    expect(screen.getByText("Манипулятор").getAttribute("title")).toBe(
      "Манипулятор"
    )
    expect(screen.queryByRole("button", { name: /переместить/i })).toBeNull()
  })

  it("moves the destructive action into the editor and keeps the guarded delete error", async () => {
    const user = userEvent.setup()
    catalogApi.remove.mockRejectedValue(
      new ApiError(
        "Delete is forbidden while the vehicle has retained driver-shift history",
        409,
        "VEHICLE_HAS_LINKED_SHIFTS"
      )
    )
    renderPage()

    expect(await screen.findByText("КамАЗ")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Изменить" }))
    const deleteButton = screen.getByRole("button", { name: "Удалить" })
    expect(deleteButton.getAttribute("data-variant")).toBe("destructive")
    await user.click(deleteButton)
    await user.click(screen.getByRole("button", { name: "Удалить" }))

    expect(
      await screen.findByText(
        "Транспорт нельзя удалить: с ним сохранена история смен."
      )
    ).toBeTruthy()
    expect(catalogApi.remove).toHaveBeenCalledWith(
      "admin-token",
      "vehicle",
      vehicle
    )
  })
})
