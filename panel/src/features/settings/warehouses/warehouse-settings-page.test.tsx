import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import { WarehouseSettingsPage } from "@/features/settings/warehouses/warehouse-settings-page"

const {
  completeWarehouseInactivation,
  createWarehouse,
  listWarehouses,
  replaceWarehouse,
  reloadWarehouses,
  scheduleWarehouseTimeZone,
  startWarehouseDraining,
} = vi.hoisted(() => ({
  completeWarehouseInactivation: vi.fn(),
  createWarehouse: vi.fn(),
  listWarehouses: vi.fn(),
  replaceWarehouse: vi.fn(),
  reloadWarehouses: vi.fn(),
  scheduleWarehouseTimeZone: vi.fn(),
  startWarehouseDraining: vi.fn(),
}))

vi.mock("@/api/warehouse-api", () => ({
  completeWarehouseInactivation,
  createWarehouse,
  listWarehouses,
  replaceWarehouse,
  scheduleWarehouseTimeZone,
  startWarehouseDraining,
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
  version: 3,
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  lifecycleState: "ACTIVE",
  sortOrder: 2,
}

const inactiveWarehouse = {
  ...warehouse,
  id: "00000000-0000-4000-8000-000000000003",
  name: "Южный склад",
  city: "Москва",
  active: false,
  lifecycleState: "INACTIVE",
}

const drainingWarehouse = {
  ...warehouse,
  id: "00000000-0000-4000-8000-000000000004",
  version: 5,
  name: "Склад на выводе",
  active: false,
  lifecycleState: "DRAINING",
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
  it("keeps selected filters, search and create action when filters are collapsed", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse, inactiveWarehouse])

    renderPage()

    await screen.findByRole("button", { name: "Статус" })
    await user.click(screen.getByRole("button", { name: "Статус" }))
    await user.click(screen.getByLabelText("Активные"))
    await user.click(screen.getByRole("button", { name: "Применить" }))

    await waitFor(() => expect(screen.queryByText("Южный склад")).toBeNull())

    await user.click(
      screen.getByRole("button", { name: "Скрыть фильтры складов" })
    )

    expect(
      document
        .getElementById("warehouse-settings-filters")
        ?.hasAttribute("hidden")
    ).toBe(true)
    expect(
      screen.getByRole("searchbox", { name: "Поиск складов" })
    ).toBeTruthy()
    expect(screen.getByRole("button", { name: "Создать склад" })).toBeTruthy()

    await user.click(
      screen.getByRole("button", { name: "Показать фильтры складов" })
    )

    const filtersPanel = document.getElementById("warehouse-settings-filters")
    if (filtersPanel === null) throw new Error("Не найдена панель фильтров")

    await user.click(
      within(filtersPanel).getByRole("button", { name: /Статус/ })
    )
    expect(screen.getByLabelText("Активные").getAttribute("data-state")).toBe(
      "checked"
    )
  })

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
    await setField(user, "Название", "Южный склад")
    await setField(user, "Город", "Москва")
    await setField(user, "Временная зона", "Europe/Moscow")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(createWarehouse).toHaveBeenCalledWith(
        "access-token",
        "00000000-0000-4000-8000-000000000002",
        {
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
          name: warehouse.name,
          city: warehouse.city,
          address: warehouse.address,
          timeZone: warehouse.timeZone,
          sortOrder: warehouse.sortOrder,
        }
      )
      expect(reloadWarehouses).toHaveBeenCalled()
      expect(screen.queryByRole("dialog")).toBeNull()
    })
  })

  it("starts the irreversible draining lifecycle with an expected version", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse])
    startWarehouseDraining.mockResolvedValue({
      ...warehouse,
      version: 4,
      active: false,
      lifecycleState: "DRAINING",
    })
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Начать вывод" }))[0]!
    )
    const dialog = screen.getByRole("alertdialog")
    expect(within(dialog).getByText(/Переход необратим/)).toBeTruthy()
    await user.click(
      within(dialog).getByRole("button", { name: "Начать вывод" })
    )

    await waitFor(() =>
      expect(startWarehouseDraining).toHaveBeenCalledWith(
        "access-token",
        warehouse.id,
        warehouse.version
      )
    )
  })

  it("completes inactivation only through the dedicated lifecycle command", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([drainingWarehouse])
    completeWarehouseInactivation.mockResolvedValue({
      ...drainingWarehouse,
      version: 6,
      lifecycleState: "INACTIVE",
    })
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Завершить вывод" }))[0]!
    )
    const dialog = screen.getByRole("alertdialog")
    expect(within(dialog).getByText(/подтверждения готовности/)).toBeTruthy()
    await user.click(
      within(dialog).getByRole("button", { name: "Завершить вывод" })
    )

    await waitFor(() =>
      expect(completeWarehouseInactivation).toHaveBeenCalledWith(
        "access-token",
        drainingWarehouse.id,
        drainingWarehouse.version
      )
    )
  })

  it("schedules an effective-dated timezone change without rewriting metadata", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse])
    scheduleWarehouseTimeZone.mockResolvedValue({
      warehouseId: warehouse.id,
      warehouseVersion: 4,
      timeZone: "Europe/Samara",
      effectiveFrom: "2099-09-01T00:00:00+04:00",
    })
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Сменить часовой пояс",
        })
      )[0]!
    )
    await setField(user, "Новая временная зона", "Europe/Samara")
    await setField(user, "Начать с даты и времени", "2099-09-01T00:00:00+04:00")
    await user.click(screen.getByRole("button", { name: "Запланировать" }))

    await waitFor(() =>
      expect(scheduleWarehouseTimeZone).toHaveBeenCalledWith(
        "access-token",
        warehouse.id,
        warehouse.version,
        "Europe/Samara",
        "2099-09-01T00:00:00+04:00"
      )
    )
  })
})
