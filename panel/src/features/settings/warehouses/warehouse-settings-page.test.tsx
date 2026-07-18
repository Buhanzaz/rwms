import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import { WarehouseSettingsPage } from "@/features/settings/warehouses/warehouse-settings-page"

const {
  createWarehouse,
  deactivateWarehouse,
  listWarehouses,
  replaceWarehouse,
  reloadWarehouses,
} = vi.hoisted(() => ({
  createWarehouse: vi.fn(),
  deactivateWarehouse: vi.fn(),
  listWarehouses: vi.fn(),
  replaceWarehouse: vi.fn(),
  reloadWarehouses: vi.fn(),
}))

vi.mock("@/api/warehouse-api", () => ({
  createWarehouse,
  deactivateWarehouse,
  listWarehouses,
  replaceWarehouse,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "access-token",
    currentUser: { globalRole: "SYSTEM_ADMIN" },
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({ reloadWarehouses }),
}))

const warehouse = {
  id: "00000000-0000-4000-8000-000000000001",
  serviceId: "00000000-0000-4000-8000-000000000001",
  version: 3,
  code: "WH_NORTH",
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: 2,
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <WarehouseSettingsPage />
    </QueryClientProvider>
  )
}

function setField(
  user: ReturnType<typeof userEvent.setup>,
  name: string,
  value: string
) {
  return user
    .clear(screen.getByLabelText(name))
    .then(() => user.type(screen.getByLabelText(name), value))
}

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("WarehouseSettingsPage", () => {
  it("creates a warehouse through the canonical idempotent command", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse])
    createWarehouse.mockResolvedValue(warehouse)
    reloadWarehouses.mockResolvedValue(undefined)
    vi.stubGlobal("crypto", {
      randomUUID: () => "00000000-0000-4000-8000-000000000002",
    })

    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать склад" })
    )
    await setField(user, "Код", "wh_south")
    await setField(user, "Название", "Южный склад")
    await setField(user, "Город", "Москва")
    await setField(user, "Временная зона", "Europe/Moscow")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(createWarehouse).toHaveBeenCalledWith(
        "access-token",
        "00000000-0000-4000-8000-000000000002",
        {
          code: "WH_SOUTH",
          name: "Южный склад",
          city: "Москва",
          address: null,
          timeZone: "Europe/Moscow",
          sortOrder: null,
        }
      )
    )
  })

  it("refreshes canonical data and closes a stale edit instead of overwriting it", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse])
    replaceWarehouse.mockRejectedValue(new ApiError("Конфликт версий", 409))
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await screen.findAllByRole("button", { name: "Изменить" })
    await user.click(screen.getAllByRole("button", { name: "Изменить" })[0]!)
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(replaceWarehouse).toHaveBeenCalledWith(
        "access-token",
        warehouse.id,
        warehouse.version,
        {
          code: warehouse.code,
          name: warehouse.name,
          city: warehouse.city,
          address: warehouse.address,
          timeZone: warehouse.timeZone,
          active: warehouse.active,
          sortOrder: warehouse.sortOrder,
        }
      )
      expect(reloadWarehouses).toHaveBeenCalled()
      expect(screen.queryByRole("dialog")).toBeNull()
    })
  })
})
